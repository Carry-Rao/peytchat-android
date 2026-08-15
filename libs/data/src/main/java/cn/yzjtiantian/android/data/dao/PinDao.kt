package cn.yzjtiantian.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.yzjtiantian.android.data.entity.PinEntity

@Dao
interface PinDao {
    @Query("SELECT * FROM pins WHERE channel_chat_id = :channelChatId ORDER BY pinned_at DESC")
    suspend fun listPins(channelChatId: Long): List<PinEntity>

    @Query("SELECT COUNT(*) FROM pins WHERE channel_chat_id = :channelChatId AND msg_id = :msgId")
    suspend fun exists(channelChatId: Long, msgId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pin: PinEntity)

    @Query("DELETE FROM pins WHERE channel_chat_id = :channelChatId AND msg_id = :msgId")
    suspend fun deleteByMsg(channelChatId: Long, msgId: Long)
}
