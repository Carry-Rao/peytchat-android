package cn.yzjtiantian.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cn.yzjtiantian.android.data.entity.ChannelEntity

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE workspace_id = :workspaceId ORDER BY category, position, id")
    suspend fun listChannels(workspaceId: Long): List<ChannelEntity>

    @Query("SELECT chat_id FROM channels")
    suspend fun getAllChatIds(): List<Long>

    @Query("SELECT * FROM channels WHERE chat_id = :chatId")
    suspend fun findByChatId(chatId: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: Long): ChannelEntity?

    @Query("SELECT workspace_id FROM channels WHERE chat_id = :chatId")
    suspend fun getWorkspaceId(chatId: Long): Long?

    @Insert
    suspend fun insert(channel: ChannelEntity): Long

    @Query("UPDATE channels SET name = :name WHERE chat_id = :chatId")
    suspend fun updateName(chatId: Long, name: String)

    @Query("UPDATE channels SET topic = :topic WHERE chat_id = :chatId")
    suspend fun updateTopic(chatId: Long, topic: String)

    @Query("UPDATE channels SET category = :category WHERE chat_id = :chatId")
    suspend fun updateCategory(chatId: Long, category: String)

    @Query("UPDATE channels SET space_type = :spaceType WHERE chat_id = :chatId")
    suspend fun updateSpaceType(chatId: Long, spaceType: String)

    @Query("SELECT space_type FROM channels WHERE chat_id = :chatId")
    suspend fun getSpaceType(chatId: Long): String?

    @Query("DELETE FROM channels WHERE chat_id = :chatId")
    suspend fun deleteByChatId(chatId: Long)
}
