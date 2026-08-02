package cn.yzjtiantian.android.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "activities",
    indices = [
        Index(value = ["workspace_id", "created_at"]),
        Index(value = ["channel_chat_id", "created_at"]),
    ],
)
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "workspace_id") val workspaceId: Long,
    @ColumnInfo(name = "channel_chat_id") val channelChatId: Long?,
    @ColumnInfo(name = "actor_id") val actorId: Long,
    @ColumnInfo(name = "actor_name") val actorName: String,
    val action: String,
    @ColumnInfo(name = "target_type") val targetType: String,
    @ColumnInfo(name = "target_id") val targetId: Long,
    val payload: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
