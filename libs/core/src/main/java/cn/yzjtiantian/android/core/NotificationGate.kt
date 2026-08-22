package cn.yzjtiantian.android.core

/**
 * 通知门控：UI 与消息接收服务之间的进程内共享状态。
 *
 * 服务弹通知前判断「用户是否正在前台查看该会话」：
 * - [activeChatId]：当前正在前台打开的会话 chatId（-1 表示没有打开的会话），
 *   由 UI 在打开/关闭会话时维护；
 * - [appInForeground]：App 是否处于前台（MainActivity 生命周期维护）。
 *
 * 只有「App 在前台 **且** 正在看该会话」时才不弹通知；App 退后台后即使会话还
 * “开着”，也要照常弹通知（对齐 QQ/微信行为）。
 */
object NotificationGate {

    /** 当前正在前台查看的会话 chatId；-1 表示没有打开的会话。 */
    @Volatile
    var activeChatId: Long = -1L

    /** App 是否处于前台（MainActivity ON_START/ON_STOP 维护）。 */
    @Volatile
    var appInForeground: Boolean = false
}
