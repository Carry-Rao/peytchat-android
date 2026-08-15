package cn.yzjtiantian.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cn.yzjtiantian.android.data.entity.WorkspaceEntity

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY id")
    suspend fun listWorkspaces(): List<WorkspaceEntity>

    @Insert
    suspend fun insert(workspace: WorkspaceEntity): Long

    @Query("SELECT * FROM workspaces WHERE master_chat_id = :masterChatId")
    suspend fun findByMasterChat(masterChatId: Long): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: Long): WorkspaceEntity?

    @Query("UPDATE workspaces SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Query("UPDATE workspaces SET icon = :icon WHERE id = :id")
    suspend fun updateIcon(id: Long, icon: String)

    @Query("DELETE FROM pins WHERE workspace_id = :id")
    suspend fun deletePinsForWorkspace(id: Long)

    @Query("DELETE FROM contact_roles WHERE workspace_id = :id")
    suspend fun deleteContactRolesForWorkspace(id: Long)

    @Query("DELETE FROM roles WHERE workspace_id = :id")
    suspend fun deleteRolesForWorkspace(id: Long)

    @Query("DELETE FROM channels WHERE workspace_id = :id")
    suspend fun deleteChannelsForWorkspace(id: Long)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun delete(id: Long)
}
