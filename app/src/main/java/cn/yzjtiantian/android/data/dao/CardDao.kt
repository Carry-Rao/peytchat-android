package cn.yzjtiantian.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cn.yzjtiantian.android.data.entity.CardEntity

@Dao
interface CardDao {
    @Query(
        "SELECT * FROM cards WHERE workspace_id = :workspaceId AND channel_chat_id = :channelChatId " +
            "ORDER BY status, position, created_at",
    )
    suspend fun listCards(workspaceId: Long, channelChatId: Long): List<CardEntity>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun getById(id: Long): CardEntity?

    @Query(
        "SELECT id FROM cards WHERE channel_chat_id = :channelChatId AND title = :title " +
            "AND ABS(created_at - :createdAt) < 60",
    )
    suspend fun findCardByDedup(channelChatId: Long, title: String, createdAt: Long): Long?

    @Insert
    suspend fun insert(card: CardEntity): Long

    @Query("UPDATE cards SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE cards SET description = :description, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateDescription(id: Long, description: String?, updatedAt: Long)

    @Query("UPDATE cards SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long)

    @Query("UPDATE cards SET assignee_contact_id = :assigneeContactId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateAssignee(id: Long, assigneeContactId: Long?, updatedAt: Long)

    @Query("UPDATE cards SET due_date = :dueDate, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateDueDate(id: Long, dueDate: Long?, updatedAt: Long)

    @Query("UPDATE cards SET msg_id = :msgId WHERE id = :id")
    suspend fun setMsgId(id: Long, msgId: Long)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun delete(id: Long)
}
