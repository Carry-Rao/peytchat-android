package cn.yzjtiantian.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import cn.yzjtiantian.android.data.dao.ActivityDao
import cn.yzjtiantian.android.data.dao.CardDao
import cn.yzjtiantian.android.data.dao.ChannelDao
import cn.yzjtiantian.android.data.dao.InboxEventDao
import cn.yzjtiantian.android.data.dao.PinDao
import cn.yzjtiantian.android.data.dao.RoleDao
import cn.yzjtiantian.android.data.dao.WorkspaceDao
import cn.yzjtiantian.android.data.entity.ActivityEntity
import cn.yzjtiantian.android.data.entity.CardEntity
import cn.yzjtiantian.android.data.entity.ChannelEntity
import cn.yzjtiantian.android.data.entity.ContactRoleEntity
import cn.yzjtiantian.android.data.entity.InboxEventEntity
import cn.yzjtiantian.android.data.entity.PinEntity
import cn.yzjtiantian.android.data.entity.RoleEntity
import cn.yzjtiantian.android.data.entity.WorkspaceEntity

@Database(
    entities = [
        WorkspaceEntity::class,
        ChannelEntity::class,
        RoleEntity::class,
        ContactRoleEntity::class,
        PinEntity::class,
        CardEntity::class,
        InboxEventEntity::class,
        ActivityEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun channelDao(): ChannelDao
    abstract fun roleDao(): RoleDao
    abstract fun pinDao(): PinDao
    abstract fun cardDao(): CardDao
    abstract fun inboxEventDao(): InboxEventDao
    abstract fun activityDao(): ActivityDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peytchat.db",
                ).build().also { instance = it }
            }
    }
}
