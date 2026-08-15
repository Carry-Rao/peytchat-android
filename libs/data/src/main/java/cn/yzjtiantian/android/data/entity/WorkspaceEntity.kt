package cn.yzjtiantian.android.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "master_chat_id") val masterChatId: Long,
    val icon: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
