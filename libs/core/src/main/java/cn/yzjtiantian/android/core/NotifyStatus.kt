package cn.yzjtiantian.android.core

/**
 * 消息通知服务运行状态（供设置页「消息通知」诊断展示）。
 * 由 :app 的 MessageNotificationService 在运行中更新。
 */
object NotifyStatus {

    /** 事件循环是否就绪（服务成功挂载通知监听器）。 */
    @Volatile
    var eventLoopReady: Boolean = false

    /** 前台服务是否正在运行（服务 onCreate/onDestroy 维护）。 */
    @Volatile
    var serviceRunning: Boolean = false

    /** 事件消费线程是否存活（诊断：事件循环是否在跑）。 */
    @Volatile
    var eventThreadAlive: Boolean = false

    /** 收到任意事件的总数（诊断：前台操作应让该数字持续增加）。 */
    @Volatile
    var eventCount: Long = 0L

    /** 最近一次收到任意事件的时间戳（ms）。 */
    @Volatile
    var lastAnyEventAt: Long = 0L

    /** 最近一次收到 IncomingMsg 事件的时间戳（ms）。 */
    @Volatile
    var lastIncomingAt: Long = 0L

    /** 最近一次成功投递消息通知的时间戳（ms）。 */
    @Volatile
    var lastNotifiedAt: Long = 0L

    /** 最后一条 IncomingMsg 的处理结果（诊断醒目行）。 */
    @Volatile
    var lastIncomingResult: String = "—"
}
