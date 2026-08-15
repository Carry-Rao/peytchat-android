package cn.yzjtiantian.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cn.yzjtiantian.android.data.entity.InboxEventEntity

@Dao
interface InboxEventDao {
    @Query("SELECT * FROM inbox_events WHERE workspace_id = :workspaceId ORDER BY created_at DESC LIMIT :limit")
    suspend fun listEvents(workspaceId: Long, limit: Long): List<InboxEventEntity>

    @Insert
    suspend fun insert(event: InboxEventEntity): Long

    @Query("UPDATE inbox_events SET read_at = :now WHERE id = :eventId AND read_at IS NULL")
    suspend fun markRead(eventId: Long, now: Long)

    @Query("UPDATE inbox_events SET read_at = :now WHERE workspace_id = :workspaceId AND read_at IS NULL")
    suspend fun markAllRead(workspaceId: Long, now: Long)

    @Query("SELECT COUNT(*) FROM inbox_events WHERE workspace_id = :workspaceId AND read_at IS NULL")
    suspend fun unreadCount(workspaceId: Long): Long
}
