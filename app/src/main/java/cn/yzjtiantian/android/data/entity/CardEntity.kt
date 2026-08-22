package cn.yzjtiantian.android.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cards",
    indices = [
        Index(value = ["workspace_id", "channel_chat_id"]),
        Index(value = ["status"]),
        Index(value = ["assignee_contact_id"]),
        Index(value = ["msg_id"]),
    ],
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "workspace_id") val workspaceId: Long,
    @ColumnInfo(name = "channel_chat_id") val channelChatId: Long,
    @ColumnInfo(name = "msg_id") val msgId: Long? = null,
    val type: String,
    val title: String,
    val description: String?,
    val status: String,
    @ColumnInfo(name = "assignee_contact_id") val assigneeContactId: Long?,
    @ColumnInfo(name = "due_date") val dueDate: Long?,
    @ColumnInfo(name = "created_by") val createdBy: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val position: Long,
    @ColumnInfo(name = "source_msg_id") val sourceMsgId: Long?,
)
