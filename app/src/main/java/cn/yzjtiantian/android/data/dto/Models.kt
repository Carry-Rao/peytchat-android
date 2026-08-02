package cn.yzjtiantian.android.data.dto

/** Mirrors dto.rs serialization shapes for the app-level domain objects. */
data class WorkspaceDto(
    val id: Long,
    val name: String,
    val masterChatId: Long,
    val icon: String?,
    val createdAt: Long,
)

data class ChannelDto(
    val id: Long,
    val workspaceId: Long,
    val chatId: Long,
    val name: String,
    val category: String,
    val position: Long,
    val topic: String?,
    val unread: Int,
    val spaceType: String = "chat",
)

data class RoleDto(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val color: String?,
)

data class ContactRoleDto(
    val contactId: Long,
    val roleId: Long,
    val roleName: String,
    val roleColor: String?,
)

data class PinDto(
    val id: Long,
    val workspaceId: Long,
    val channelChatId: Long,
    val msgId: Long,
    val pinnedBy: Long,
    val pinnedAt: Long,
)

data class CardDto(
    val id: Long,
    val workspaceId: Long,
    val channelChatId: Long,
    val msgId: Long?,
    val type: String,
    val title: String,
    val description: String?,
    val status: String,
    val assigneeContactId: Long?,
    val assigneeName: String?,
    val dueDate: Long?,
    val createdBy: Long,
    val createdByName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val position: Long,
    val sourceMsgId: Long?,
)

data class InboxEventDto(
    val id: Long,
    val workspaceId: Long,
    val type: String,
    val sourceChatId: Long,
    val msgId: Long?,
    val actorId: Long,
    val actorName: String,
    val summary: String,
    val createdAt: Long,
    val readAt: Long?,
)

data class ActivityDto(
    val id: Long,
    val workspaceId: Long,
    val channelChatId: Long?,
    val actorId: Long,
    val actorName: String,
    val action: String,
    val targetType: String,
    val targetId: Long,
    val payload: String?,
    val createdAt: Long,
)

data class PeytStudioDto(
    val workspace: WorkspaceDto,
    val role: String, // "founder" | "member" | "existing"
    val inviteQr: String?,
)

/** A message as returned by the core `get_message` RPC (subset we use). */
data class CoreMessageDto(
    val id: Long,
    val chatId: Long,
    val fromId: Long,
    val text: String,
    val timestamp: Long,
    val viewType: String,
)
