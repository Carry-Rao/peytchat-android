package cn.yzjtiantian.android.data.repository

import cn.yzjtiantian.android.core.Rpc
import cn.yzjtiantian.android.core.RpcException
import cn.yzjtiantian.android.core.Session
import cn.yzjtiantian.android.data.AppDatabase
import cn.yzjtiantian.android.data.dao.ContactRoleRow
import cn.yzjtiantian.android.data.dto.CardDto
import cn.yzjtiantian.android.data.dto.ChannelDto
import cn.yzjtiantian.android.data.dto.ContactDto
import cn.yzjtiantian.android.data.dto.ContactRoleDto
import cn.yzjtiantian.android.data.dto.CoreMessageDto
import cn.yzjtiantian.android.data.dto.InboxEventDto
import cn.yzjtiantian.android.data.dto.PeytStudioDto
import cn.yzjtiantian.android.data.dto.PinDto
import cn.yzjtiantian.android.data.dto.RoleDto
import cn.yzjtiantian.android.data.dto.WorkspaceDto
import cn.yzjtiantian.android.data.entity.ActivityEntity
import cn.yzjtiantian.android.data.entity.CardEntity
import cn.yzjtiantian.android.data.entity.ChannelEntity
import cn.yzjtiantian.android.data.entity.InboxEventEntity
import cn.yzjtiantian.android.data.entity.PinEntity
import cn.yzjtiantian.android.data.entity.RoleEntity
import cn.yzjtiantian.android.data.entity.WorkspaceEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

private const val PEYT_STUDIO_NAME = "PEYT Studio"
private const val SELF_CONTACT_ID = 1L

/**
 * Business-logic repository, ported from the desktop backend `commands.rs`.
 *
 * Combines local Room persistence (workspaces/channels/cards/inbox/activities)
 * with deltachat-core JSON-RPC calls (group chats, securejoin, messages).
 * All RPC calls operate on [Session.currentAccountId].
 */
class PeytRepository(
    private val rpc: Rpc,
    private val db: AppDatabase,
) {
    private fun accountId(): Long {
        val id = Session.currentAccountId
        if (id <= 0) throw RpcException("no account selected")
        return id
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    // ── low-level core RPC helpers ──────────────────────────────────────────

    private fun createGroupChat(name: String): Long {
        val result = rpc.callRaw(
            "create_group_chat",
            JSONArray().put(accountId()).put(name).put(false),
        ) as? Number ?: throw RpcException("create_group_chat returned no id")
        return result.toLong()
    }

    private fun sendText(chatId: Long, text: String): Long {
        val data = JSONObject().put("text", text)
        val result = rpc.callRaw(
            "send_msg",
            JSONArray().put(accountId()).put(chatId).put(data),
        ) as? Number ?: throw RpcException("send_msg returned no id")
        return result.toLong()
    }

    /** Public text-send used by the chat UI. */
    suspend fun sendMessage(chatId: Long, text: String): Long = sendText(chatId, text)

    /**
     * Message IDs for a chat, oldest-first. Mirrors desktop `get_chat_msgs`
     * windowing (last 50 items).
     */
    suspend fun getChatMessageIds(chatId: Long): List<Long> {
        val ids = rpc.callArray(
            "get_message_ids",
            JSONArray().put(accountId()).put(chatId).put(false).put(false),
        )
        val out = ArrayList<Long>(ids.length())
        for (i in 0 until ids.length()) {
            out.add(ids.optLong(i))
        }
        return out.takeLast(50)
    }

    /** Fetches one message from core. */
    suspend fun getChatMessage(msgId: Long): CoreMessageDto = getMessage(msgId)

    /** Rendered messages for a chat, oldest-first. */
    suspend fun getChatMessages(chatId: Long): List<cn.yzjtiantian.android.data.dto.ChatMessageDto> {
        val ids = getChatMessageIds(chatId)
        return ids.mapNotNull { id ->
            try {
                val m = getMessage(id)
                val isInfo = runCatching {
                    rpc.call(
                        "get_message",
                        JSONArray().put(accountId()).put(id),
                    ).optBoolean("isInfo", false)
                }.getOrDefault(false)
                cn.yzjtiantian.android.data.dto.ChatMessageDto(
                    msgId = m.id,
                    fromId = m.fromId,
                    fromName = if (m.fromId == SELF_CONTACT_ID) "我" else contactDisplayName(m.fromId),
                    text = m.text,
                    timestamp = m.timestamp,
                    isOut = m.fromId == SELF_CONTACT_ID,
                    isInfo = isInfo,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getMessage(msgId: Long): CoreMessageDto {
        val obj = rpc.call("get_message", JSONArray().put(accountId()).put(msgId))
        return CoreMessageDto(
            id = obj.optLong("id"),
            chatId = obj.optLong("chatId"),
            fromId = obj.optLong("fromId"),
            text = obj.optString("text", ""),
            timestamp = obj.optLong("timestamp"),
            viewType = obj.optString("viewType", "Text"),
        )
    }

    private fun getContact(contactId: Long): JSONObject =
        rpc.call("get_contact", JSONArray().put(accountId()).put(contactId))

    private fun contactDisplayName(contactId: Long): String {
        if (contactId == SELF_CONTACT_ID) {
            if (Session.displayName.isNotBlank()) return Session.displayName
            return rpc.callRaw(
                "get_config",
                JSONArray().put(accountId()).put("displayname"),
            ) as? String ?: "我"
        }
        return try {
            getContact(contactId).optString("displayName").ifBlank { "我" }
        } catch (_: Exception) {
            "我"
        }
    }

    private fun lookupContactIdByAddr(addr: String): Long? {
        if (addr.isEmpty()) return null
        val result = rpc.callRaw(
            "lookup_contact_id_by_addr",
            JSONArray().put(accountId()).put(addr),
        )
        return (result as? Number)?.toLong()
    }

    private fun getSecureJoinQr(chatId: Long?): String {
        val arr = JSONArray().put(accountId())
        if (chatId != null) arr.put(chatId) else arr.put(JSONObject.NULL)
        val qr = rpc.callRaw("get_chat_securejoin_qr_code", arr) as? String ?: ""
        return qr.replaceFirst("https://i.delta.chat/", "https://peyt.yzjtiantian.cn/")
    }

    private fun secureJoin(qr: String): Long {
        val normalized = qr.replaceFirst(
            "https://peyt.yzjtiantian.cn/",
            "https://i.delta.chat/",
        )
        android.util.Log.d("PEYT", "[secure_join] in=$qr -> out=$normalized")
        val result = rpc.callRaw("secure_join", JSONArray().put(accountId()).put(normalized)) as? Number
            ?: throw RpcException("secure_join returned no id")
        return result.toLong()
    }

    // ── workspace ───────────────────────────────────────────────────────────

    suspend fun listWorkspaces(): List<WorkspaceDto> =
        db.workspaceDao().listWorkspaces().map { it.toDto() }

    suspend fun createWorkspace(name: String): WorkspaceDto {
        val masterChatId = createGroupChat(name)
        val icon = name.firstOrNull()?.uppercase()
        val wsId = db.workspaceDao().insert(
            WorkspaceEntity(name = name, masterChatId = masterChatId, icon = icon, createdAt = now()),
        )
        for (chName in listOf("general", "announcements")) {
            val chId = createGroupChat(chName)
            db.channelDao().insert(
                ChannelEntity(workspaceId = wsId, chatId = chId, name = chName, category = "General", position = 0),
            )
        }
        db.roleDao().insert(RoleEntity(workspaceId = wsId, name = "core", color = null))
        return db.workspaceDao().findByMasterChat(masterChatId)!!.toDto()
    }

    suspend fun joinWorkspace(qr: String): WorkspaceDto {
        val masterChatId = secureJoin(qr)
        db.workspaceDao().findByMasterChat(masterChatId)?.let { return it.toDto() }
        val name = rpc.call(
            "get_basic_chat_info",
            JSONArray().put(accountId()).put(masterChatId),
        ).optString("name", "New Workspace")
        val icon = name.firstOrNull()?.uppercase()
        db.workspaceDao().insert(
            WorkspaceEntity(name = name, masterChatId = masterChatId, icon = icon, createdAt = now()),
        )
        return db.workspaceDao().findByMasterChat(masterChatId)!!.toDto()
    }

    suspend fun updateWorkspace(id: Long, name: String?, icon: String?) {
        val dao = db.workspaceDao()
        if (name != null) dao.updateName(id, name)
        if (icon != null) dao.updateIcon(id, icon)
    }

    suspend fun deleteWorkspace(id: Long) {
        db.workspaceDao().delete(id)
    }

    // ── channel ────────────────────────────────────────────────────────────

    suspend fun listChannels(workspaceId: Long): List<ChannelDto> =
        db.channelDao().listChannels(workspaceId).map {
            it.toDto(unread = freshMsgCount(it.chatId))
        }

    suspend fun createChannel(workspaceId: Long, name: String, category: String): ChannelDto {
        val chatId = createGroupChat(name)
        db.channelDao().insert(
            ChannelEntity(workspaceId = workspaceId, chatId = chatId, name = name, category = category, position = 0),
        )
        logActivity(workspaceId, chatId, "channel_create", "channel", chatId, name)
        return db.channelDao().findByChatId(chatId)!!.toDto(unread = 0)
    }

    suspend fun updateChannel(chatId: Long, name: String?, topic: String?, category: String?) {
        val dao = db.channelDao()
        if (name != null) dao.updateName(chatId, name)
        if (topic != null) dao.updateTopic(chatId, topic)
        if (category != null) dao.updateCategory(chatId, category)
    }

    suspend fun setChannelSpaceType(chatId: Long, spaceType: String) {
        db.channelDao().updateSpaceType(chatId, spaceType)
    }

    suspend fun getChannelSpaceType(chatId: Long): String? =
        db.channelDao().getSpaceType(chatId)

    private fun freshMsgCount(chatId: Long): Int {
        return try {
            (rpc.callRaw(
                "get_fresh_msg_cnt",
                JSONArray().put(accountId()).put(chatId),
            ) as? Number)?.toInt() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    // ── roles & pins ───────────────────────────────────────────────────────

    suspend fun listRoles(workspaceId: Long): List<RoleDto> =
        db.roleDao().listRoles(workspaceId).map { it.toDto() }

    suspend fun createRole(workspaceId: Long, name: String, color: String?): Long =
        db.roleDao().insert(RoleEntity(workspaceId = workspaceId, name = name, color = color))

    suspend fun setContactRole(workspaceId: Long, contactId: Long, roleId: Long) {
        db.roleDao().setContactRole(
            cn.yzjtiantian.android.data.entity.ContactRoleEntity(
                contactId = contactId,
                roleId = roleId,
                workspaceId = workspaceId,
            ),
        )
    }

    suspend fun listContactRoles(workspaceId: Long, contactId: Long): List<Long> =
        db.roleDao().listContactRoles(workspaceId, contactId)

    suspend fun listAllContactRoles(workspaceId: Long): List<ContactRoleDto> =
        db.roleDao().listAllContactRoles(workspaceId).map { it.toDto() }

    suspend fun listPins(channelChatId: Long): List<PinDto> =
        db.pinDao().listPins(channelChatId).map { it.toDto() }

    suspend fun togglePin(workspaceId: Long, channelChatId: Long, msgId: Long): Boolean {
        val dao = db.pinDao()
        val pinned = if (dao.exists(channelChatId, msgId) > 0) {
            dao.deleteByMsg(channelChatId, msgId)
            false
        } else {
            dao.insert(PinEntity(
                workspaceId = workspaceId,
                channelChatId = channelChatId,
                msgId = msgId,
                pinnedBy = SELF_CONTACT_ID,
                pinnedAt = now(),
            ))
            true
        }
        logActivity(workspaceId, channelChatId, "pin_toggle", "message", msgId, null)
        return pinned
    }

    // ── cards ──────────────────────────────────────────────────────────────

    suspend fun createCard(
        workspaceId: Long,
        chatId: Long,
        type: String,
        title: String,
        description: String?,
        assigneeContactId: Long?,
        dueDate: Long?,
    ): CardDto {
        val now = now()
        val createdBy = SELF_CONTACT_ID
        val cardId = db.cardDao().insert(CardEntity(
            workspaceId = workspaceId,
            channelChatId = chatId,
            type = type,
            title = title,
            description = description,
            status = "todo",
            assigneeContactId = assigneeContactId,
            dueDate = dueDate,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now,
            position = 0,
            sourceMsgId = null,
        ))
        val assigneeAddr = assigneeContactId?.let { contactAddr(it) } ?: ""
        val createdByAddr = contactAddr(SELF_CONTACT_ID)
        val cardJson = JSONObject()
            .put("action", "create")
            .put("id", cardId)
            .put("type", type)
            .put("title", title)
            .put("status", "todo")
            .put("assignee_addr", assigneeAddr)
            .put("due_date", dueDate ?: JSONObject.NULL)
            .put("description", description ?: JSONObject.NULL)
            .put("created_by_addr", createdByAddr)
            .put("created_at", now)
            .toString()
        val sentMsgId = sendText(chatId, "[CARD]$cardJson")
        db.cardDao().setMsgId(cardId, sentMsgId)
        logActivity(workspaceId, chatId, "card_create", "card", cardId, title)
        return db.cardDao().getById(cardId)!!.toDto()
    }

    suspend fun updateCard(
        cardId: Long,
        title: String?,
        description: String?,
        status: String?,
        assigneeContactId: Long?,
        dueDate: Long?,
    ): CardDto {
        val now = now()
        val dao = db.cardDao()
        if (title != null) dao.updateTitle(cardId, title, now)
        if (description != null) dao.updateDescription(cardId, description, now)
        if (status != null) dao.updateStatus(cardId, status, now)
        if (assigneeContactId != null) dao.updateAssignee(cardId, assigneeContactId, now)
        if (dueDate != null) dao.updateDueDate(cardId, dueDate, now)
        val row = dao.getById(cardId) ?: throw RpcException("card not found")
        val assigneeAddr = row.assigneeContactId?.let { contactAddr(it) } ?: ""
        val cardJson = JSONObject()
            .put("action", "update")
            .put("id", cardId)
            .put("type", row.type)
            .put("title", row.title)
            .put("status", row.status)
            .put("assignee_addr", assigneeAddr)
            .put("due_date", row.dueDate ?: JSONObject.NULL)
            .put("description", row.description ?: JSONObject.NULL)
            .put("created_at", row.createdAt)
            .toString()
        sendText(row.channelChatId, "[CARD]$cardJson")
        val payload = JSONObject()
            .put("title", row.title)
            .put("status", row.status)
            .put("description", row.description ?: JSONObject.NULL)
            .put("assignee_contact_id", row.assigneeContactId ?: JSONObject.NULL)
            .put("due_date", row.dueDate ?: JSONObject.NULL)
            .toString()
        logActivity(row.workspaceId, row.channelChatId, "card_update", "card", cardId, payload)
        return row.toDto()
    }

    suspend fun deleteCard(cardId: Long) {
        val row = db.cardDao().getById(cardId)
        db.cardDao().delete(cardId)
        if (row != null) {
            val cardJson = JSONObject()
                .put("action", "delete")
                .put("id", cardId)
                .put("title", row.title)
                .put("created_at", row.createdAt)
                .toString()
            sendText(row.channelChatId, "[CARD]$cardJson")
            logActivity(row.workspaceId, row.channelChatId, "card_delete", "card", cardId, null)
        }
    }

    suspend fun listCards(workspaceId: Long, chatId: Long): List<CardDto> =
        db.cardDao().listCards(workspaceId, chatId).map { it.toDto() }

    suspend fun getCard(cardId: Long): CardDto? =
        db.cardDao().getById(cardId)?.toDto()

    /**
     * Driven by `[CARD]` sync messages. Parses the JSON payload and
     * upserts/updates/deletes a card, deduplicating by
     * (channel_chat_id, title, created_at within 60s).
     */
    suspend fun upsertCardFromMsg(msgId: Long, cardJson: String): CardDto? {
        val payload = try {
            JSONObject(cardJson)
        } catch (_: Exception) {
            throw RpcException("invalid card json")
        }
        val action = payload.optString("action", "create")
        val title = payload.optString("title", "")
        val createdAt = payload.optLong("created_at", 0)

        val msg = getMessage(msgId)
        val channelChatId = msg.chatId
        val workspaceId = db.channelDao().getWorkspaceId(channelChatId)
            ?: throw RpcException("channel $channelChatId not found")

        val existingId = db.cardDao().findCardByDedup(channelChatId, title, createdAt)
        val now = now()

        return when {
            action == "delete" -> {
                existingId?.let { db.cardDao().delete(it) }
                null
            }
            existingId != null -> {
                val dao = db.cardDao()
                val status = payload.optStringOrNull("status")
                val description = payload.optStringOrNull("description")
                val dueDate = if (payload.has("due_date") && !payload.isNull("due_date")) payload.optLong("due_date") else null
                val assigneeCid = payload.optStringOrNull("assignee_addr")?.let { lookupContactIdByAddr(it) }
                if (description != null) dao.updateDescription(existingId, description, now)
                if (status != null) dao.updateStatus(existingId, status, now)
                if (assigneeCid != null) dao.updateAssignee(existingId, assigneeCid, now)
                if (dueDate != null) dao.updateDueDate(existingId, dueDate, now)
                db.cardDao().getById(existingId)?.toDto()
            }
            else -> {
                val type = payload.optString("type", "card")
                val status = payload.optString("status", "todo")
                val description = payload.optStringOrNull("description")
                val dueDate = if (payload.has("due_date") && !payload.isNull("due_date")) payload.optLong("due_date") else null
                val assigneeCid = payload.optStringOrNull("assignee_addr")?.let { lookupContactIdByAddr(it) }
                val createdBy = payload.optStringOrNull("created_by_addr")?.let { lookupContactIdByAddr(it) } ?: SELF_CONTACT_ID
                val cardId = db.cardDao().insert(CardEntity(
                    workspaceId = workspaceId,
                    channelChatId = channelChatId,
                    msgId = null,
                    type = type,
                    title = title,
                    description = description,
                    status = status,
                    assigneeContactId = assigneeCid,
                    dueDate = dueDate,
                    createdBy = createdBy,
                    createdAt = createdAt,
                    updatedAt = now,
                    position = 0,
                    sourceMsgId = msgId,
                ))
                db.cardDao().setMsgId(cardId, msgId)
                db.cardDao().getById(cardId)?.toDto()
            }
        }
    }

    suspend fun messageToCard(msgId: Long, workspaceId: Long, chatId: Long, type: String, title: String?): CardDto {
        val msg = getMessage(msgId)
        val resolvedTitle = title ?: truncate(msg.text, 40)
        val now = now()
        val createdBy = SELF_CONTACT_ID
        val cardId = db.cardDao().insert(CardEntity(
            workspaceId = workspaceId,
            channelChatId = chatId,
            type = type,
            title = resolvedTitle,
            description = null,
            status = "todo",
            assigneeContactId = null,
            dueDate = null,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now,
            position = 0,
            sourceMsgId = msgId,
        ))
        val createdByAddr = contactAddr(SELF_CONTACT_ID)
        val cardJson = JSONObject()
            .put("action", "create")
            .put("id", cardId)
            .put("type", type)
            .put("title", resolvedTitle)
            .put("status", "todo")
            .put("assignee_addr", "")
            .put("due_date", JSONObject.NULL)
            .put("description", JSONObject.NULL)
            .put("created_by_addr", createdByAddr)
            .put("created_at", now)
            .put("source_msg_id", msgId)
            .toString()
        val sentMsgId = sendText(chatId, "[CARD]$cardJson")
        db.cardDao().setMsgId(cardId, sentMsgId)
        logActivity(workspaceId, chatId, "message_to_card", "card", cardId, null)
        return db.cardDao().getById(cardId)!!.toDto()
    }

    // ── contacts & direct chats ────────────────────────────────────────────

    /** Known contacts (excluding self), mirroring desktop `get_contacts`. */
    suspend fun listContacts(): List<ContactDto> {
        val arr = rpc.callArray("get_contacts", JSONArray().put(accountId()).put(0).put(JSONObject.NULL))
        val out = ArrayList<ContactDto>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optLong("id")
            if (id <= 0 || id == SELF_CONTACT_ID) continue
            out.add(
                ContactDto(
                    id = id,
                    address = obj.optString("address"),
                    displayName = obj.optString("displayName"),
                    name = obj.optString("name"),
                ),
            )
        }
        return out
    }

    /** Creates (or reuses) a DM chat with a contact by email address. */
    suspend fun createChatByEmail(address: String): Long {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) throw RpcException("empty email address")
        val contactId = lookupContactIdByAddr(trimmed) ?: run {
            val cid = rpc.callRaw(
                "create_contact",
                JSONArray().put(accountId()).put(trimmed).put(JSONObject.NULL),
            ) as? Number ?: throw RpcException("create_contact returned no id")
            cid.toLong()
        }
        val chatId = rpc.callRaw(
            "create_chat_by_contact_id",
            JSONArray().put(accountId()).put(contactId),
        ) as? Number ?: throw RpcException("create_chat_by_contact_id returned no id")
        return chatId.toLong()
    }

    /** Creates a new (empty) group chat. */
    suspend fun createGroup(name: String): Long = createGroupChat(name)

    /**
     * Adds a contact by email, a `peyt://invite/<b64>` legacy link, or a
     * core securejoin link (`https://i.delta.chat/#<token>` /
     * `OPENPGP4FPR:<token>`). Returns the opened chat id.
     */
    suspend fun addFriend(input: String): Long {
        val raw = input.trim()
        if (raw.isEmpty()) throw RpcException("empty input")
        val email = parseInviteEmail(raw) ?: raw
        if (isEmail(email)) return createChatByEmail(email)
        return secureJoin(raw)
    }

    /** Invite link for the current workspace, a core securejoin QR/URL. */
    suspend fun getInviteLink(): String {
        val wsId = currentWorkspaceId()
        val ws = db.workspaceDao().listWorkspaces().firstOrNull { it.id == wsId }
            ?: throw RpcException("no workspace")
        return getSecureJoinQr(ws.masterChatId)
    }

    private fun parseInviteEmail(raw: String): String? {
        val prefix = "peyt://invite/"
        if (!raw.startsWith(prefix)) return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(raw.substring(prefix.length)))
        }.getOrNull()?.takeIf { isEmail(it) }
    }

    private fun isEmail(s: String): Boolean =
        "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$".toRegex().matches(s.trim())

    // ── PEYT Studio ────────────────────────────────────────────────────────

    suspend fun ensurePeytStudio(): PeytStudioDto {
        val existing = db.workspaceDao().listWorkspaces().firstOrNull { it.name == PEYT_STUDIO_NAME }
        if (existing != null) {
            return PeytStudioDto(existing.toDto(), "existing", null)
        }
        val masterChatId = createGroupChat(PEYT_STUDIO_NAME)
        val wsId = db.workspaceDao().insert(
            WorkspaceEntity(name = PEYT_STUDIO_NAME, masterChatId = masterChatId, icon = "P", createdAt = now()),
        )
        val generalChat = createGroupChat("闲聊")
        db.channelDao().insert(
            ChannelEntity(workspaceId = wsId, chatId = generalChat, name = "闲聊", category = "General", position = 0),
        )
        val workChat = createGroupChat("工作")
        db.channelDao().insert(
            ChannelEntity(workspaceId = wsId, chatId = workChat, name = "工作", category = "General", position = 1),
        )
        db.channelDao().updateSpaceType(workChat, "card")
        db.roleDao().insert(RoleEntity(workspaceId = wsId, name = "core", color = null))
        val welcome = "👋 欢迎来到 PEYT Studio\n\n这是团队的默认协作空间。\n• 公告频道: 团队通知发布\n• 闲聊频道: 日常交流\n• 工作频道: 任务看板协作\n\n点击右上角头像可切换主题,左下角 + 可创建更多 workspace。"
        sendText(masterChatId, welcome)
        val generalQr = getSecureJoinQr(generalChat).replace("\"", "\\\"")
        val workQr = getSecureJoinQr(workChat).replace("\"", "\\\"")
        sendText(masterChatId, "[PEYT_INVITE]{\"general_qr\":\"$generalQr\",\"work_qr\":\"$workQr\"}")
        val inviteQr = getSecureJoinQr(masterChatId)
        return PeytStudioDto(
            workspace = db.workspaceDao().findByMasterChat(masterChatId)!!.toDto(),
            role = "founder",
            inviteQr = inviteQr,
        )
    }

    suspend fun joinPeytStudio(qr: String): PeytStudioDto {
        val masterChatId = secureJoin(qr)
        db.workspaceDao().findByMasterChat(masterChatId)?.let {
            return PeytStudioDto(it.toDto(), "existing", null)
        }
        val wsId = db.workspaceDao().insert(
            WorkspaceEntity(name = PEYT_STUDIO_NAME, masterChatId = masterChatId, icon = "P", createdAt = now()),
        )
        db.roleDao().insert(RoleEntity(workspaceId = wsId, name = "core", color = null))
        val ws = db.workspaceDao().findByMasterChat(masterChatId)!!
        return PeytStudioDto(ws.toDto(), "member", null)
    }

    suspend fun joinPeytChannel(workspaceId: Long, qr: String, name: String, category: String, spaceType: String?): Long {
        val chatId = secureJoin(qr)
        val exists = db.channelDao().listChannels(workspaceId).any { it.chatId == chatId }
        if (exists) return chatId
        db.channelDao().insert(
            ChannelEntity(workspaceId = workspaceId, chatId = chatId, name = name, category = category, position = 0),
        )
        if (spaceType != null) db.channelDao().updateSpaceType(chatId, spaceType)
        return chatId
    }

    suspend fun currentWorkspaceId(): Long {
        val workspaces = db.workspaceDao().listWorkspaces()
        return workspaces.firstOrNull { it.name == PEYT_STUDIO_NAME }
            ?.id
            ?: workspaces.firstOrNull()?.id
            ?: throw RpcException("no workspace")
    }

    // ── inbox + activity ───────────────────────────────────────────────────

    suspend fun listInboxEvents(limit: Long = 100): List<InboxEventDto> {
        val wsId = currentWorkspaceId()
        return db.inboxEventDao().listEvents(wsId, limit).map { it.toDto() }
    }

    suspend fun markInboxRead(eventId: Long) {
        db.inboxEventDao().markRead(eventId, now())
    }

    suspend fun markAllInboxRead() {
        db.inboxEventDao().markAllRead(currentWorkspaceId(), now())
    }

    suspend fun getInboxUnreadCount(): Long =
        db.inboxEventDao().unreadCount(currentWorkspaceId())

    suspend fun recordInboxEvent(
        eventType: String,
        sourceChatId: Long,
        msgId: Long?,
        actorId: Long,
        actorName: String,
        summary: String,
    ) {
        db.inboxEventDao().insert(InboxEventEntity(
            workspaceId = currentWorkspaceId(),
            type = eventType,
            sourceChatId = sourceChatId,
            msgId = msgId,
            actorId = actorId,
            actorName = actorName,
            summary = summary,
            createdAt = now(),
            readAt = null,
        ))
    }

    suspend fun listActivities(channelChatId: Long?, limit: Long = 100): List<cn.yzjtiantian.android.data.dto.ActivityDto> {
        val wsId = currentWorkspaceId()
        val rows = if (channelChatId != null) {
            db.activityDao().listForChannel(wsId, channelChatId, limit)
        } else {
            db.activityDao().listAll(wsId, limit)
        }
        return rows.map { it.toDto() }
    }

    private suspend fun logActivity(
        workspaceId: Long,
        channelChatId: Long?,
        action: String,
        targetType: String,
        targetId: Long,
        payload: String?,
    ) {
        val actorId = SELF_CONTACT_ID
        val actorName = Session.displayName.ifBlank { "self" }
        db.activityDao().insert(ActivityEntity(
            workspaceId = workspaceId,
            channelChatId = channelChatId,
            actorId = actorId,
            actorName = actorName,
            action = action,
            targetType = targetType,
            targetId = targetId,
            payload = payload,
            createdAt = now(),
        ))
    }

    private fun contactAddr(contactId: Long): String {
        return try {
            getContact(contactId).optString("address")
        } catch (_: Exception) {
            ""
        }
    }

    private fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        return text.take(max) + "..."
    }

    // ── entity → dto ───────────────────────────────────────────────────────

    private fun WorkspaceEntity.toDto() = WorkspaceDto(id, name, masterChatId, icon, createdAt)

    private fun ChannelEntity.toDto(unread: Int) = ChannelDto(
        id, workspaceId, chatId, name, category, position, topic, unread, spaceType,
    )

    private fun RoleEntity.toDto() = RoleDto(id, workspaceId, name, color)

    private fun ContactRoleRow.toDto() = ContactRoleDto(contact_id, role_id, name, color)

    private fun PinEntity.toDto() = PinDto(id, workspaceId, channelChatId, msgId, pinnedBy, pinnedAt)

    private fun CardEntity.toDto() = CardDto(
        id = id,
        workspaceId = workspaceId,
        channelChatId = channelChatId,
        msgId = msgId,
        type = type,
        title = title,
        description = description,
        status = status,
        assigneeContactId = assigneeContactId,
        assigneeName = assigneeContactId?.let { contactDisplayName(it) },
        dueDate = dueDate,
        createdBy = createdBy,
        createdByName = contactDisplayName(createdBy),
        createdAt = createdAt,
        updatedAt = updatedAt,
        position = position,
        sourceMsgId = sourceMsgId,
    )

    private fun InboxEventEntity.toDto() = InboxEventDto(
        id, workspaceId, type, sourceChatId, msgId, actorId, actorName, summary, createdAt, readAt,
    )

    private fun ActivityEntity.toDto() = cn.yzjtiantian.android.data.dto.ActivityDto(
        id, workspaceId, channelChatId, actorId, actorName, action, targetType, targetId, payload, createdAt,
    )

    private fun JSONObject.optStringOrNull(key: String): String? {
        val v = opt(key)
        return if (v == null || v === JSONObject.NULL) null else v.toString()
    }
}
