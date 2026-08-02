package cn.yzjtiantian.android.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "roles")
data class RoleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "workspace_id") val workspaceId: Long,
    val name: String,
    val color: String?,
)
