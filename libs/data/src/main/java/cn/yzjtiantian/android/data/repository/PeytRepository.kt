package cn.yzjtiantian.android.data.repository

import cn.yzjtiantian.android.core.DeepLink
import cn.yzjtiantian.android.core.ModuleManager
import cn.yzjtiantian.android.core.PeytBridge
import cn.yzjtiantian.android.core.Rpc
import cn.yzjtiantian.android.core.RpcException
import cn.yzjtiantian.android.core.Session
import cn.yzjtiantian.android.core.TextSendHook
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
import cn.yzjtiantian.android.data.envelope.Envelope
import cn.yzjtiantian.android.data.envelope.cardEnvelopeAction
import cn.yzjtiantian.android.data.envelope.isCardEnvelope
import cn.yzjtiantian.android.data.envelope.resolveEnvelopeSummary
import cn.yzjtiantian.android.data.envelope.resolveMessageText
import cn.yzjtiantian.android.data.envelope.tryParseEnvelope
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

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
    private val tempDir: java.io.File,
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

    /**
     * 组装并发送信封消息(复用 Rust `peyt-envelope`, 与桌面端 envelope.rs 同构)。
     */
    private fun sendEnvelope(chatId: Long, type: String, payload: JSONObject): Long =
        sendText(chatId, PeytBridge.nativeBuildEnvelope(type, payload.toString()))

    /**
     * Public text-send used by the chat UI.
     *
     * 按 PEYT 信封协议包装正文:发送端(写)复用 Rust `peyt-envelope` 的
     * `build_envelope`(与桌面端 envelope.rs 同构),产出
     * `{"type":"text","id":"<uuid>","payload":{"text":"..."}}`。
     * 内部 `[CARD]`/`[PEYT_INVITE]` 已迁移为 `card.*`/`project.invite` 信封
     * (见 [createCard]/[ensurePeytStudio])。
     * 接收端(读)解析见 [resolveEnvelopeText],为客户端自实现。
     */
    suspend fun sendMessage(chatId: Long, text: String): Long {
        // 数据层热更新钩子：补丁注册的 TextSendHook 可改写发送的文本
        // （只作用于用户文本消息，不影响 card.*/project.invite 等信封协议）。
        val finalText = (ModuleManager.getPatchService(TextSendHook.SERVICE_KEY) as? TextSendHook)
            ?.transform(text) ?: text
        val payload = JSONObject().put("text", finalText).toString()
        val envelope = PeytBridge.nativeBuildEnvelope("text", payload)
        return sendText(chatId, envelope)
    }

    /**
     * 发送附件/文档(对齐桌面端 `send_attachment` + media 信封):
     * - 二进制写入临时文件, 按 MIME 推断 viewtype(Image/Gif/Audio/Video/File);
     * - 正文写 `{"type":"media",...,"payload":{"media_type","mime","name","size","text"}}`;
     * - 经 `send_msg` MessageData 挂 core 附件(接收端见 [ChatMessageDto] 附件字段)。
     */
    suspend fun sendAttachment(chatId: Long, bytes: ByteArray, filename: String, mime: String): Long {
        val dir = tempDir.takeIf { it.exists() || it.mkdirs() } ?: throw RpcException("no temp dir")
        val file = java.io.File(dir, filename)
        file.writeBytes(bytes)
        val viewType = viewTypeForMime(mime)
        val mediaType = when (viewType) {
            "Image", "Gif" -> "image"
            "Audio" -> "audio"
            "Video" -> "video"
            "Voice" -> "voice"
            else -> "file"
        }
        val payload = JSONObject()
            .put("media_type", mediaType)
            .put("mime", mime)
            .put("name", filename)
            .put("size", bytes.size)
            .put("text", filename)
        val envelope = PeytBridge.nativeBuildEnvelope("media", payload.toString())
        val data = JSONObject()
            .put("text", envelope)
            .put("viewtype", viewType)
            .put("file", file.absolutePath)
            .put("filename", filename)
        val result = rpc.callRaw(
            "send_msg",
            JSONArray().put(accountId()).put(chatId).put(data),
        ) as? Number ?: throw RpcException("send_msg returned no id")
        return result.toLong()
    }

    /** 按 MIME 推断 Delta viewtype(对齐桌面端 `viewtype_for_mime`)。 */
    private fun viewTypeForMime(mime: String): String {
        val m = mime.lowercase()
        return when {
            m.startsWith("image/") -> if (m.endsWith("gif")) "Gif" else "Image"
            m.startsWith("audio/") -> "Audio"
            m.startsWith("video/") -> "Video"
            else -> "File"
        }
    }

    /** 消息(id, 信封 id) → 已处理标记, 用于跨 IncomingMsg/历史路径的副作用幂等(镜像 rv 规范 §5.2)。 */
    private val processedEnvelopeIds = ConcurrentHashMap<Pair<Long, String>, Boolean>()

    /**
     * 解析 PEYT 信封(镜像桌面端 `envelope.ts`):
     * - 普通信封(text/reply/media) → `payload.text`;
     * - 业务信封(card.* / project.invite) → 可读摘要(卡片标题/邀请提示);
     * - 结构不合法 / 未知 type → 原样返回。
     */
    private fun resolveEnvelopeText(raw: String): String {
        return resolveEnvelopeSummary(raw) ?: resolveMessageText(raw)
    }

    /**
     * 处理收到的一条消息:若为业务信封(card.* / project.invite),执行本地副作用
     * (卡片同步 / 频道加入),并以 (fromId, envelope.id) 幂等去重;返回是否已消费。
     * 普通信封/普通文本返回 false,不拦截聊天流展示。
     */
    suspend fun handleIncomingEnvelope(msgId: Long, fromId: Long, text: String): Boolean {
        val env = tryParseEnvelope(text) ?: return false
        if (!isCardEnvelope(env) && env.type != "project.invite") return false
        val key = fromId to env.id
        if (processedEnvelopeIds.putIfAbsent(key, true) != null) return true
        return try {
            when {
                isCardEnvelope(env) -> {
                    upsertCardFromMsg(msgId, cardEnvelopeAction(env), env.payload)
                    true
                }
                env.type == "project.invite" -> {
                    handleProjectInvite(msgId, env)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            processedEnvelopeIds.remove(key)
            android.util.Log.w("PEYT", "[envelope] handle ${env.type} failed", e)
            true
        }
    }

    private suspend fun handleProjectInvite(msgId: Long, env: Envelope) {
        val payload = env.payload
        val generalQr = payload.optString("general_qr", "").takeIf { it.isNotEmpty() }
        val workQr = payload.optString("work_qr", "").takeIf { it.isNotEmpty() }
        if (generalQr == null && workQr == null) return
        val msg = try { getMessage(msgId) } catch (_: Exception) { return }
        val workspaceId = db.channelDao().getWorkspaceId(msg.chatId) ?: return
        if (generalQr != null) {
            joinPeytChannel(workspaceId, generalQr, "闲聊", "General", null)
        }
        if (workQr != null) {
            joinPeytChannel(workspaceId, workQr, "工作", "General", "card")
        }
    }

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
                    text = resolveEnvelopeText(m.text),
                    timestamp = m.timestamp,
                    isOut = m.fromId == SELF_CONTACT_ID,
                    isInfo = isInfo,
                    viewType = m.viewType,
                    fileName = m.fileName,
                    fileBytes = m.fileBytes,
                    filePath = m.filePath,
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
            fileName = obj.optStringOrNull("fileName"),
            fileBytes = obj.optLong("fileBytes", 0),
            filePath = obj.optStringOrNull("file"),
            fileMime = obj.optStringOrNull("fileMime"),
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
            val c = getContact(contactId)
            c.optString("displayName").ifBlank { c.optString("address") }
        } catch (_: Exception) {
            "陌生人"
        }
    }

    /**
     * 直接消息的会话名:优先联系人显示名(core get_display_name:
     * 本地名→authname→邮箱),与桌面端对齐;拿不到再用 chatlist 的 name。
     */
    private fun resolveDmName(item: JSONObject): String {
        val dmContactId = item.optLong("dmChatContact", 0)
        if (dmContactId > 0) {
            runCatching {
                val c = getContact(dmContactId)
                val display = c.optString("displayName")
                if (display.isNotBlank()) return display
                c.optString("address").takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return item.optString("name")
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

    /**
     * 执行 securejoin 加入群/联系人。
     *
     * 与 PC 端一致：core 的 `secure_join` 返回 chatId 后握手在后台进行
     * （邮件来回需要数秒到数十秒），这里**不阻塞**，立即返回 chatId。
     */
    private suspend fun secureJoin(qr: String): Long {
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

    /**
     * 按 chatId 查找本地频道（通知点击跳转用）。
     * 单聊/陌生群等非 workspace 频道返回 null，调用方再走直接消息路径。
     */
    suspend fun findChannelByChatId(chatId: Long): ChannelDto? =
        db.channelDao().findByChatId(chatId)?.toDto(unread = freshMsgCount(chatId))

    /** 把会话标记为已读（打开聊天时调用，联动未读数/通知）。 */
    suspend fun markChatNoticed(chatId: Long) {
        runCatching {
            rpc.callRaw("marknoticed_chat", JSONArray().put(accountId()).put(chatId))
        }
    }

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
            .put("id", cardId)
            .put("type", type)
            .put("title", title)
            .put("status", "todo")
            .put("assignee_addr", assigneeAddr)
            .put("due_date", dueDate ?: JSONObject.NULL)
            .put("description", description ?: JSONObject.NULL)
            .put("created_by_addr", createdByAddr)
            .put("created_at", now)
            .put("updated_at", now)
            .put("position", 0)
        val sentMsgId = sendEnvelope(chatId, "card.create", cardJson)
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
            .put("id", cardId)
            .put("type", row.type)
            .put("title", row.title)
            .put("status", row.status)
            .put("assignee_addr", assigneeAddr)
            .put("due_date", row.dueDate ?: JSONObject.NULL)
            .put("description", row.description ?: JSONObject.NULL)
            .put("created_at", row.createdAt)
            .put("updated_at", now)
            .put("position", 0)
        sendEnvelope(row.channelChatId, "card.update", cardJson)
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
                .put("id", cardId)
                .put("title", row.title)
                .put("created_at", row.createdAt)
            sendEnvelope(row.channelChatId, "card.delete", cardJson)
            logActivity(row.workspaceId, row.channelChatId, "card_delete", "card", cardId, null)
        }
    }

    suspend fun listCards(workspaceId: Long, chatId: Long): List<CardDto> =
        db.cardDao().listCards(workspaceId, chatId).map { it.toDto() }

    suspend fun getCard(cardId: Long): CardDto? =
        db.cardDao().getById(cardId)?.toDto()

    /**
     * Driven by `card.create`/`card.update`/`card.delete` 信封消息。按信封类型
     * 动作 upserts/updates/deletes 卡片, 按 (channel_chat_id, title, created_at
     * 在 60s 内) 去重。
     */
    suspend fun upsertCardFromMsg(msgId: Long, action: String, payload: JSONObject): CardDto? {
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
            .put("id", cardId)
            .put("type", type)
            .put("title", resolvedTitle)
            .put("status", "todo")
            .put("assignee_addr", "")
            .put("due_date", JSONObject.NULL)
            .put("description", JSONObject.NULL)
            .put("created_by_addr", createdByAddr)
            .put("created_at", now)
            .put("updated_at", now)
            .put("position", 0)
            .put("source_msg_id", msgId)
        val sentMsgId = sendEnvelope(chatId, "card.create", cardJson)
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
     * 删除聊天/频道：core `delete_chat` + 清理本地 Room 频道记录。
     * 群聊与单聊通用。
     */
    suspend fun deleteChat(chatId: Long) {
        runCatching {
            rpc.callRaw("delete_chat", JSONArray().put(accountId()).put(chatId))
        }.onFailure {
            android.util.Log.w("PEYT", "[delete_chat] chatId=$chatId core 删除失败", it)
        }
        db.channelDao().deleteByChatId(chatId)
        android.util.Log.d("PEYT", "[delete_chat] chatId=$chatId 已删除（core + Room）")
    }

    /**
     * 删除好友：core `delete_contact`（连同其聊天一起删除）+ 兜底删聊天。
     * 通过单聊的 chatId 定位联系人。
     */
    suspend fun deleteFriend(chatId: Long) {
        val contactIds = runCatching {
            rpc.callArray("get_chat_contacts", JSONArray().put(accountId()).put(chatId))
        }.getOrDefault(JSONArray())
        if (contactIds.length() > 0) {
            val contactId = contactIds.optLong(0)
            runCatching {
                rpc.callRaw("delete_contact", JSONArray().put(accountId()).put(contactId))
            }.onFailure {
                android.util.Log.w("PEYT", "[delete_contact] contactId=$contactId 失败", it)
            }
            android.util.Log.d("PEYT", "[delete_friend] chatId=$chatId contactId=$contactId 已删除")
        } else {
            android.util.Log.w("PEYT", "[delete_friend] chatId=$chatId 未找到联系人，回退 delete_chat")
        }
        deleteChat(chatId)
    }

    /**
     * 直接消息列表,来自 core `get_chatlist`。
     *
     * 桌面端消息页直接渲染 core chatlist;Android 此前只展示本地 workspace
     * 频道,收到陌生人的单聊/请求后没有任何入口。这里取未归档、非 self-talk、
     * 且未绑定为 workspace 频道的会话,让收到的新消息能直接出现在「消息」页。
     * 排序:未读优先,再按 chat_id 倒序(近似最近活跃)。
     */
    suspend fun listDirectChats(): List<ChannelDto> {
        val wsChannelIds = db.channelDao().getAllChatIds().toHashSet()
        val entries = runCatching {
            rpc.callArray(
                "get_chatlist_entries",
                JSONArray().put(accountId()).put(0).put(JSONObject.NULL).put(JSONObject.NULL),
            )
        }.getOrDefault(JSONArray())
        val ids = ArrayList<Long>(entries.length())
        for (i in 0 until entries.length()) {
            val id = entries.optLong(i, 0)
            if (id > 9) ids.add(id)
        }
        if (ids.isEmpty()) return emptyList()
        val items = runCatching {
            rpc.call(
                "get_chatlist_items_by_entries",
                JSONArray().put(accountId()).put(JSONArray(ids)),
            )
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<ChannelDto>()
        val keys = items.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = items.optJSONObject(key) ?: continue
            if (obj.optString("kind") != "ChatListItem") continue
            val chatId = obj.optLong("id", 0)
            if (chatId in wsChannelIds) continue
            if (obj.optBoolean("is_self_talk", false)) continue
            if (obj.optBoolean("is_archived", false)) continue
            val preview = obj.optString("summary_text2")
            out.add(
                ChannelDto(
                    id = -1,
                    workspaceId = -1,
                    chatId = chatId,
                    name = resolveDmName(obj),
                    category = "",
                    position = 0,
                    topic = preview.ifBlank { null },
                    unread = obj.optInt("fresh_message_counter", 0),
                    spaceType = "chat",
                ),
            )
        }
        out.sortWith(compareByDescending<ChannelDto> { it.unread }.thenByDescending { it.chatId })
        return out
    }

    /**
     * Adds a contact by email, a `peyt://invite/<b64>` legacy link, a
     * `peytchat://` deep link, or a core securejoin link
     * (`https://i.delta.chat/#<token>` / `OPENPGP4FPR:<token>`).
     * Returns the opened chat id.
     */
    suspend fun addFriend(input: String): Long {
        val raw = DeepLink.toCore(input)
        if (raw.isEmpty()) throw RpcException("empty input")
        val email = parseInviteEmail(raw) ?: raw
        if (isEmail(email)) return createChatByEmail(email)
        return secureJoin(raw)
    }

    /** Basic display name for a chat id (used when jumping via deep link). */
    suspend fun getChatName(chatId: Long): String =
        runCatching {
            rpc.call(
                "get_basic_chat_info",
                JSONArray().put(accountId()).put(chatId),
            ).optString("name")
        }.getOrDefault("")

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
        val generalQr = getSecureJoinQr(generalChat)
        val workQr = getSecureJoinQr(workChat)
        val invitePayload = JSONObject()
            .put("general_qr", generalQr)
            .put("work_qr", workQr)
        sendEnvelope(masterChatId, "project.invite", invitePayload)
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
