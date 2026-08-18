package cn.yzjtiantian.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import cn.yzjtiantian.android.core.AccountManager
import cn.yzjtiantian.android.core.CoreRuntime
import cn.yzjtiantian.android.core.DcEvent
import cn.yzjtiantian.android.core.MessageNotification
import cn.yzjtiantian.android.core.MessageNotificationHook
import cn.yzjtiantian.android.core.MessageNotifications
import cn.yzjtiantian.android.core.ModuleManager
import cn.yzjtiantian.android.core.NotificationGate
import cn.yzjtiantian.android.core.NotifyLog
import cn.yzjtiantian.android.core.NotifyStatus
import cn.yzjtiantian.android.core.PeytBridge
import cn.yzjtiantian.android.core.PeytEventLoop
import cn.yzjtiantian.android.core.Rpc
import cn.yzjtiantian.android.core.Session
import cn.yzjtiantian.android.data.envelope.resolveEnvelopeSummary
import cn.yzjtiantian.android.data.envelope.resolveMessageText
import org.json.JSONArray
import org.json.JSONObject

/**
 * 消息接收前台服务（仿 QQ/微信：App 退后台甚至被杀进程后仍能收消息）。
 *
 * 机制：
 * 1. 常驻前台服务（manifest 声明 `specialUse` 类型）保活进程；
 * 2. 通过 [PeytEventLoop] 持有 deltachat core 的事件循环（进程单例），
 *    收到 `IncomingMsg` 事件即弹系统通知；
 * 3. 开机广播（[BootReceiver]）与 `START_STICKY` 保证重启/被杀后自动恢复；
 * 4. 通知内容可被热更新补丁定制（[MessageNotificationHook]，见
 *    `patch/notification` 模块），实现「通知行为热更新」。
 *
 * 已知平台限制（Android 15 / targetSdk 35）：
 * - `dataSync` 型前台服务既不能从 BOOT_COMPLETED 启动、又有 6 小时/天超时，
 *   故这里用 `specialUse` 类型（无超时、可从开机广播启动）；
 * - 厂商深度省电/强制停止仍可能杀后台，真正「永不掉线」需接入
 *   FCM 或厂商推送通道（本自托管项目暂无推送服务器）。
 */
class MessageNotificationService : Service() {

    override fun onCreate() {
        super.onCreate()
        // 先上常驻通知（startForegroundService 5 秒内必须调用）
        startForeground(NotificationHelper.FGS_NOTIFICATION_ID, NotificationHelper.buildForeground(this))
        NotifyStatus.serviceRunning = true
        NotifyLog.log(this, "消息接收服务启动")
        // 事件循环初始化含 native init + 账号引导，放后台线程避免阻塞主线程
        Thread {
            try {
                val bridge = PeytEventLoop.ensureStarted(applicationContext)
                removeListener = bridge?.addListener(::handleEvent)
                NotifyStatus.eventLoopReady = bridge != null
                NotifyStatus.eventThreadAlive = PeytEventLoop.isRunning()
                NotifyLog.log(
                    this,
                    if (bridge != null) "事件循环就绪，通知监听器已挂载"
                    else "⚠ 事件循环未就绪，通知监听器未挂载",
                )
            } catch (e: Exception) {
                NotifyStatus.eventLoopReady = false
                NotifyLog.log(this, "⚠ 服务初始化异常: ${e.message}")
            }
        }.start()
        // 心跳 + IO 保活：每 30 秒记录状态，每 60 秒重踢 start_io（幂等），
        // 网络切换/IMAP 断连后能自动恢复后台收消息。
        Thread {
            var tick = 0
            while (true) {
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                tick++
                NotifyStatus.eventThreadAlive = PeytEventLoop.isRunning()
                NotifyLog.log(
                    this,
                    "心跳: 服务存活, 事件循环线程=${if (NotifyStatus.eventThreadAlive) "存活" else "已死"}, " +
                        "事件总数=${NotifyStatus.eventCount}, 最近事件=${fmtTime(NotifyStatus.lastAnyEventAt)}, " +
                        "通知开关=${MessageNotifications.areNotificationsEnabled(this)}",
                )
                // 看门狗：事件线程意外死亡时自动重启并重新挂载监听器（自愈）
                if (!NotifyStatus.eventThreadAlive) {
                    NotifyLog.log(this, "⚠ 事件循环线程已死，尝试自愈重启")
                    try {
                        removeListener?.invoke()
                        removeListener = null
                        val b = PeytEventLoop.restart(applicationContext)
                        NotifyStatus.eventLoopReady = b != null
                        NotifyStatus.eventThreadAlive = PeytEventLoop.isRunning()
                        removeListener = b?.addListener(::handleEvent)
                        NotifyLog.log(
                            this,
                            if (b != null) "✔ 事件循环已自愈重启，监听器已重新挂载"
                            else "✘ 事件循环自愈失败",
                        )
                    } catch (e: Exception) {
                        NotifyLog.log(this, "⚠ 事件循环自愈异常: ${e.message}")
                    }
                }
                if (tick % 2 == 0 && Session.currentAccountId > 0) {
                    // 每 60 秒重踢 IO（start_io 幂等，IO 已运行则无操作）
                    val ok = runCatching {
                        AccountManager(Rpc(PeytBridge)).startIo(Session.currentAccountId)
                        true
                    }.getOrDefault(false)
                    NotifyLog.log(this, "IO 保活: start_io ${if (ok) "已执行" else "失败"}")
                }
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 进程被杀后系统用最近一次 intent 重启服务（START_STICKY）
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        NotifyStatus.serviceRunning = false
        removeListener?.invoke()
        removeListener = null
        NotifyLog.log(this, "消息接收服务停止")
        super.onDestroy()
    }

    // ===== 事件 → 通知 =====

    private fun handleEvent(event: DcEvent) {
        // 诊断计数：任何事件都记录（心跳/诊断页据此判断事件循环是否在工作）
        NotifyStatus.lastAnyEventAt = System.currentTimeMillis()
        NotifyStatus.eventCount++

        if (event.kind != "IncomingMsg") return
        // 事件自带 contextId（账号），比 Session 更可靠（多账号/开机自启场景）
        val accountId = if (event.contextId > 0) event.contextId else Session.currentAccountId
        if (accountId <= 0) {
            NotifyLog.log(this, "IncomingMsg 但无账号(accountId=$accountId)，忽略")
            return
        }
        // deltachat-jsonrpc 的 IncomingMsg 事件字段是 camelCase（msgId），
        // 兼容旧格式 msg_id 兜底。取不到就记日志，避免静默丢失。
        val msgId = event.payload.optLong("msgId", event.payload.optLong("msg_id", 0))
        if (msgId <= 0) {
            NotifyLog.log(this, "⚠ IncomingMsg 事件缺少 msgId，payload=${event.payload}")
            return
        }
        NotifyStatus.lastIncomingAt = System.currentTimeMillis()
        NotifyLog.log(this, "✔ 收到新消息: accountId=$accountId msgId=$msgId")
        // 解析消息详情涉及多次 RPC，放后台线程
        Thread {
            try {
                postIncomingNotification(accountId, msgId)
            } catch (e: Exception) {
                NotifyLog.log(this, "⚠ IncomingMsg $msgId 处理失败: ${e.message}")
            }
        }.start()
    }

    private fun postIncomingNotification(accountId: Long, msgId: Long) {
        val rpc = Rpc(PeytBridge)
        val msg = rpc.call("get_message", JSONArray().put(accountId).put(msgId))
        val chatId = msg.optLong("chatId", 0)
        if (chatId <= 0) {
            NotifyLog.log(this, "⚠ 取不到 chatId，跳过 msgId=$msgId")
            NotifyStatus.lastIncomingResult = "处理失败: 取不到 chatId"
            return
        }

        val fromId = msg.optLong("fromId", 0)

        // 用户正在前台看这个会话 → 不弹通知（App 退后台后即使会话开着也照常弹）
        if (NotificationGate.activeChatId == chatId && NotificationGate.appInForeground) {
            NotifyLog.log(this, "跳过: 正在前台查看 chatId=$chatId（免打扰）")
            NotifyStatus.lastIncomingResult = "跳过: 正在前台查看该会话（免打扰）"
            return
        }
        if (fromId == SELF_CONTACT_ID) {
            NotifyLog.log(this, "跳过: 自己(其他设备)发的消息 msgId=$msgId")
            NotifyStatus.lastIncomingResult = "跳过: 自己发的消息"
            return
        }
        if (msg.optBoolean("isInfo", false)) {
            NotifyLog.log(this, "跳过: 系统信息消息 msgId=$msgId")
            NotifyStatus.lastIncomingResult = "跳过: 系统信息消息"
            return
        }

        val chatName = runCatching {
            rpc.call("get_basic_chat_info", JSONArray().put(accountId).put(chatId))
                .optString("name", "")
        }.getOrDefault("").ifBlank { "会话" }

        // 单聊（会话里只有对方一人）→ 标题用发送者名；群聊 → 标题用会话名
        val contactCount = runCatching {
            rpc.callArray("get_chat_contacts", JSONArray().put(accountId).put(chatId)).length()
        }.getOrDefault(0)
        val isDm = contactCount <= 1

        val sender = if (fromId > 0) contactDisplayName(rpc, accountId, fromId) else ""
        val body = resolveBody(msg)
        val title = if (isDm) sender.ifBlank { chatName } else chatName
        val text = if (isDm) body else "$sender: $body"

        val default = MessageNotification(
            title = title,
            text = text,
            chatId = chatId,
            msgId = msgId,
        )

        // 热更新扩展点：补丁注册的 MessageNotificationHook 可定制通知内容/静默。
        // 注意：没有补丁（hook 为 null）→ 用默认通知；补丁返回 null → 才是要求静默。
        val hook = ModuleManager.getPatchService(MessageNotificationHook.SERVICE_KEY)
            as? MessageNotificationHook
        val final = if (hook != null) {
            hook.customize(applicationContext, default)
        } else {
            default
        }
        if (final == null) {
            NotifyLog.log(this, "被热更新补丁静默(返回 null): chatId=$chatId msgId=$msgId")
            NotifyStatus.lastIncomingResult = "被热更新补丁静默"
            return
        }

        NotifyStatus.lastNotifiedAt = System.currentTimeMillis()
        val posted = NotificationHelper.postMessage(applicationContext, final)
        NotifyStatus.lastIncomingResult =
            if (posted) "已弹通知: $title — $text"
            else "系统通知被关闭/渠道被关，未投递"
        NotifyLog.log(
            this,
            "弹通知${if (posted) "" else "(被系统丢弃)"}: chatId=$chatId title=${final.title} text=${final.text}",
        )
    }

    /** 正文：PEYT 信封 → 可读摘要/正文；附件消息 → 文件名；否则兜底文案。 */
    private fun resolveBody(msg: JSONObject): String {
        val raw = msg.optString("text", "")
        val resolved = resolveEnvelopeSummary(raw) ?: resolveMessageText(raw)
        if (resolved.isNotBlank()) return resolved
        val viewType = msg.optString("viewType", "Text")
        if (viewType != "Text") {
            return "[文件] " + msg.optString("fileName", "附件")
        }
        return "新消息"
    }

    private fun contactDisplayName(rpc: Rpc, accountId: Long, contactId: Long): String {
        return runCatching {
            val c = rpc.call("get_contact", JSONArray().put(accountId).put(contactId))
            c.optString("displayName").ifBlank { c.optString("address") }
        }.getOrDefault("")
    }

    private var removeListener: (() -> Unit)? = null

    companion object {
        private const val TAG = "PEYT"
        private const val ACTION_STOP = "cn.yzjtiantian.android.action.STOP_NOTIFICATION_SERVICE"
        private const val SELF_CONTACT_ID = 1L
        private val timeFmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())

        private fun fmtTime(t: Long): String =
            if (t > 0) timeFmt.format(java.util.Date(t)) else "—"

        /** 启动消息接收前台服务（App 登录后/开机广播时调用）。 */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MessageNotificationService::class.java),
                )
            } catch (e: Exception) {
                Log.w(TAG, "[notify-service] start failed", e)
            }
        }

        /** 停止消息接收前台服务（退出登录时调用），并清掉全部通知。 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MessageNotificationService::class.java))
            } catch (_: Exception) {
            }
            MessageNotifications.cancelAll(context)
        }

        /** 是否需要在开机后常驻（存在已配置账号才需要）。 */
        fun shouldRun(context: Context): Boolean {
            return CoreRuntime.ensureInit(context) && CoreRuntime.hasConfiguredAccount()
        }
    }
}
