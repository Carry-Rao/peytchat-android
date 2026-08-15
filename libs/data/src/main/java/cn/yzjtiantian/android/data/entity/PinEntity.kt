package cn.yzjtiantian.android.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pins",
    indices = [Index(value = ["channel_chat_id", "msg_id"], unique = true)],
)
data class PinEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "workspace_id") val workspaceId: Long,
    @ColumnInfo(name = "channel_chat_id") val channelChatId: Long,
    @ColumnInfo(name = "msg_id") val msgId: Long,
    @ColumnInfo(name = "pinned_by") val pinnedBy: Long,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long,
)
