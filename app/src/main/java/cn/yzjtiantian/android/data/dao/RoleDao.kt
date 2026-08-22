package cn.yzjtiantian.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.yzjtiantian.android.data.entity.ContactRoleEntity
import cn.yzjtiantian.android.data.entity.RoleEntity

@Dao
interface RoleDao {
    @Query("SELECT * FROM roles WHERE workspace_id = :workspaceId ORDER BY id")
    suspend fun listRoles(workspaceId: Long): List<RoleEntity>

    @Insert
    suspend fun insert(role: RoleEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun setContactRole(role: ContactRoleEntity)

    @Query("SELECT role_id FROM contact_roles WHERE workspace_id = :workspaceId AND contact_id = :contactId")
    suspend fun listContactRoles(workspaceId: Long, contactId: Long): List<Long>

    @Query(
        "SELECT cr.contact_id, cr.role_id, r.name, r.color " +
            "FROM contact_roles cr JOIN roles r ON cr.role_id = r.id " +
            "WHERE cr.workspace_id = :workspaceId ORDER BY r.id, cr.contact_id",
    )
    suspend fun listAllContactRoles(workspaceId: Long): List<ContactRoleRow>
}

data class ContactRoleRow(
    val contact_id: Long,
    val role_id: Long,
    val name: String,
    val color: String?,
)
