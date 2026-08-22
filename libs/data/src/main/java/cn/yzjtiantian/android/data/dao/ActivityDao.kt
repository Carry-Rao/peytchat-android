package cn.yzjtiantian.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cn.yzjtiantian.android.data.entity.ActivityEntity

@Dao
interface ActivityDao {
    @Query(
        "SELECT * FROM activities WHERE workspace_id = :workspaceId AND channel_chat_id = :channelChatId " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun listForChannel(workspaceId: Long, channelChatId: Long, limit: Long): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE workspace_id = :workspaceId ORDER BY created_at DESC LIMIT :limit")
    suspend fun listAll(workspaceId: Long, limit: Long): List<ActivityEntity>

    @Insert
    suspend fun insert(activity: ActivityEntity): Long
}
