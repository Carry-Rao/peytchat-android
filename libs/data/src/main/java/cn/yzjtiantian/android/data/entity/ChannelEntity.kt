package cn.yzjtiantian.android.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channels",
    indices = [Index(value = ["workspace_id", "chat_id"], unique = true)],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "workspace_id") val workspaceId: Long,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    val name: String,
    val category: String,
    val position: Long,
    val topic: String? = null,
    @ColumnInfo(name = "space_type") val spaceType: String = "chat",
)
