package cn.yzjtiantian.android

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.yzjtiantian.android.core.MessageNotification
import cn.yzjtiantian.android.core.MessageNotifications
import cn.yzjtiantian.android.core.NotifyLog

/**
 * 系统通知构造与投递（:app 侧）。
 *
 * - 消息通知：按会话 chatId 折叠（同一会话新消息更新同一条通知）；
 * - 前台服务常驻通知：低优先级「正在接收消息」提示，随服务存在；
 * - 点击消息通知 → 携带 `peytchat://chat/<chatId>` 深链打开对应会话。
 */
object NotificationHelper {

    /** 前台服务常驻通知 id。 */
    const val FGS_NOTIFICATION_ID = 1001

    /** 创建通知渠道（幂等，委托 :core）。 */
    fun ensureChannels(context: Context) {
        MessageNotifications.ensureChannels(context)
    }

    /** 前台服务常驻通知（进程存活提示）。 */
    fun buildForeground(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, MessageNotifications.CHANNEL_SERVICE)
            .setSmallIcon(cn.yzjtiantian.android.core.R.drawable.ic_stat_message)
            .setContentTitle(context.getString(R.string.notif_service_title))
            .setContentText(context.getString(R.string.notif_service_text))
            .setContentIntent(launchAppPendingIntent(context))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 投递一条消息通知（同一会话的通知按 id 折叠更新）。
     * 返回是否真正投递给系统（false = 系统通知被关闭/渠道被关，通知不会显示）。
     */
    fun postMessage(context: Context, n: MessageNotification): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotifyLog.log(context, "⚠ 应用级通知被系统关闭，跳过消息通知 chatId=${n.chatId}（请到系统设置开启通知）")
            return false
        }
        ensureChannels(context)
        if (MessageNotifications.isMessagesChannelBlocked(context)) {
            NotifyLog.log(
                context,
                "⚠ 「新消息」渠道被系统关闭，通知已被系统丢弃 chatId=${n.chatId}（需在系统设置中开启该渠道/应用通知）",
            )
            return false
        }
        val builder = NotificationCompat.Builder(context, n.channelId)
            .setSmallIcon(cn.yzjtiantian.android.core.R.drawable.ic_stat_message)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setPriority(n.priority)
            .setAutoCancel(n.autoCancel)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(chatOpenPendingIntent(context, n.chatId))
        n.groupKey?.let { builder.setGroup(it) }
        NotificationManagerCompat.from(context)
            .notify(MessageNotifications.chatNotifId(n.chatId), builder.build())
        return true
    }

    /** 打开 App（前台服务通知点击）。 */
    private fun launchAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 点击消息通知 → 打开对应会话（深链 `peytchat://chat/<id>`）。 */
    private fun chatOpenPendingIntent(context: Context, chatId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("peytchat://chat/$chatId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            MessageNotifications.chatNotifId(chatId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
