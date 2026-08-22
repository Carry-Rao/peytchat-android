package cn.yzjtiantian.android.core

import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * 一条「新消息」系统通知的内容描述。
 *
 * 由基座的消息接收服务在收到 `IncomingMsg` 后组装默认值，再交给
 * 已注册的 [MessageNotificationHook] 定制；补丁可通过 [MessageNotificationHook]
 * 修改标题/正文/优先级/渠道等，或返回 null 静默该条消息。
 */
data class MessageNotification(
    /** 通知标题（单聊=发送者名，群聊=会话名）。 */
    val title: String,
    /** 通知正文。 */
    val text: String,
    /** 所属会话 chatId（点击通知跳转用）。 */
    val chatId: Long,
    /** 触发通知的消息 id。 */
    val msgId: Long,
    /** 通知渠道 id，缺省用消息渠道 [MessageNotifications.CHANNEL_MESSAGES]。 */
    val channelId: String = MessageNotifications.CHANNEL_MESSAGES,
    /** 通知优先级。 */
    val priority: Int = NotificationCompat.PRIORITY_HIGH,
    /** 点击后是否自动消除。 */
    val autoCancel: Boolean = true,
    /** 分组键；null 不分组（默认按会话折叠）。 */
    val groupKey: String? = null,
)

/**
 * 消息通知热更新扩展点（稳定契约，签名发布后不要随意改）。
 *
 * 与 [TextSendHook] 同理：补丁入口类在 `apply(Context)` 里调用
 * `ModuleManager.registerPatchService(MessageNotificationHook.SERVICE_KEY, ...)`
 * 注册实现，基座的消息接收服务发通知前会查询并调用。
 *
 * 约定：
 * - 实现类必须是基座中不存在的新类（parent-first 类加载下才会命中补丁 dex）；
 * - 返回 null 表示**静默**该条消息（不弹通知）；
 * - 返回修改后的 [MessageNotification] 表示按定制内容弹通知。
 */
fun interface MessageNotificationHook {

    fun customize(context: Context, default: MessageNotification): MessageNotification?

    companion object {
        /** 注册键，[ModuleManager.registerPatchService] / [ModuleManager.getPatchService] 使用。 */
        const val SERVICE_KEY = "message_notification_hook"
    }
}
