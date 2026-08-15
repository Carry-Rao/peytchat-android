package cn.yzjtiantian.android.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "contact_roles",
    primaryKeys = ["contact_id", "role_id"],
)
data class ContactRoleEntity(
    @ColumnInfo(name = "contact_id") val contactId: Long,
    @ColumnInfo(name = "role_id") val roleId: Long,
    @ColumnInfo(name = "workspace_id") val workspaceId: Long,
)
