package cn.yzjtiantian.android.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "inbox_events",
    indices = [
        Index(value = ["workspace_id", "read_at"]),
        Index(value = ["created_at"]),
    ],
)
data class InboxEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "workspace_id") val workspaceId: Long,
    val type: String,
    @ColumnInfo(name = "source_chat_id") val sourceChatId: Long,
    @ColumnInfo(name = "msg_id") val msgId: Long?,
    @ColumnInfo(name = "actor_id") val actorId: Long,
    @ColumnInfo(name = "actor_name") val actorName: String,
    val summary: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "read_at") val readAt: Long?,
)
