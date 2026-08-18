package cn.yzjtiantian.android.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 与系统通知相关的公共常量与工具（:core 与各 feature 可用）。
 *
 * 实际的消息通知构造/投递在 :app 的 `NotificationHelper`，这里放
 * 渠道创建、通知 id 换算、状态检查、测试通知等不依赖 :app 的能力。
 */
object MessageNotifications {

    /** 新消息通知渠道。 */
    const val CHANNEL_MESSAGES = "messages"

    /** 前台服务常驻通知渠道（低优先级）。 */
    const val CHANNEL_SERVICE = "service"

    /** 测试通知 id。 */
    const val TEST_NOTIF_ID = 9001

    /** 创建通知渠道（幂等）。 */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "新消息",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "收到新消息时提醒"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "后台服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持消息接收服务运行"
            },
        )
    }

    /** chatId(Long) → 通知 id(Int)，全应用统一换算。 */
    fun chatNotifId(chatId: Long): Int = (chatId and 0x7fffffffL).toInt()

    /** 取消某个会话的通知（打开会话时调用）。 */
    fun cancelForChat(context: Context, chatId: Long) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(chatNotifId(chatId))
        }
    }

    /** 取消全部通知（退出登录时调用）。 */
    fun cancelAll(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancelAll()
        }
    }

    // ===== 诊断 =====

    /** Android 13+ 通知运行时权限是否已授予（低版本恒为 true）。 */
    fun areNotificationsPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 应用级通知是否开启（Android 13+ 权限 + 系统开关，含华为侧载应用默认关闭的情况）。 */
    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 「新消息」渠道是否被单独关闭（渠道级开关）。 */
    fun isMessagesChannelBlocked(context: Context): Boolean {
        return runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = manager.getNotificationChannel(CHANNEL_MESSAGES) ?: return false
            channel.importance == NotificationManager.IMPORTANCE_NONE
        }.getOrDefault(false)
    }

    /**
     * 重置「新消息」渠道（删除重建，恢复默认高重要性）。
     *
     * 用于修复两类被关闭的情况：
     * 1. Android 13+ 在授予 POST_NOTIFICATIONS 之前创建的渠道会被系统置为
     *    IMPORTANCE_NONE，授权后不自动恢复 —— 重建即恢复；
     * 2. 用户/系统手动关闭了该渠道。
     *
     * 注意：删除渠道会重置该渠道的用户设置（声音/振动等）。
     */
    fun resetMessagesChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.deleteNotificationChannel(CHANNEL_MESSAGES) }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "新消息",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "收到新消息时提醒"
            },
        )
    }

    /**
     * 打开系统通知设置页（多意图兜底：部分系统如华为对标准意图支持不佳）。
     * 返回是否成功打开；失败时提示手动路径。
     */
    fun openNotificationSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // 尝试下一个
            }
        }
        return false
    }

    /**
     * 发送一条测试通知（诊断用）。
     * - App 级通知被系统关闭 → false（需「去开启」）；
     * - 「新消息」渠道被关 → 自动删除重建后重发（返回 true）。
     */
    fun postTestNotification(context: Context): Boolean {
        if (!areNotificationsEnabled(context)) return false
        ensureChannels(context)
        if (isMessagesChannelBlocked(context)) {
            NotifyLog.log(context, "测试通知：检测到「新消息」渠道被关，自动重建渠道")
            resetMessagesChannel(context)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("测试通知")
            .setContentText("这是一条来自 PEYT Chat 的测试消息")
            .setStyle(NotificationCompat.BigTextStyle().bigText("这是一条来自 PEYT Chat 的测试消息"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(TEST_NOTIF_ID, notification)
        return true
    }
}
