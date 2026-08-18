package cn.yzjtiantian.android.core

import android.content.Context
import android.util.Log

/**
 * 进程级事件循环单例。
 *
 * deltachat core 的 `get_next_event_batch` 是全局阻塞消费：同一进程内只能有
 * **一个**消费者（多个线程并发拉取会把事件拆散/重复）。因此把 EventBridge
 * 收敛为进程单例，由常驻前台服务与 Activity 共享：
 *
 * - 前台服务（进程存活保障）在 onCreate 调用 [ensureStarted]；
 * - Activity 启动时也调用 [ensureStarted]（幂等，谁先到谁初始化）；
 * - UI 层通过 [addListener] 订阅事件（信封副作用等）；
 * - 服务层通过 [addListener] 订阅事件（弹系统通知）。
 */
object PeytEventLoop {

    private val lock = Any()

    @Volatile
    private var bridge: EventBridge? = null

    /**
     * 确保事件循环已启动并返回共享的 [EventBridge]。
     * 幂等：已启动则直接返回现有实例。启动失败返回 null。
     */
    fun ensureStarted(context: Context): EventBridge? {
        synchronized(lock) {
            bridge?.let { return it }
            if (!CoreRuntime.ensureInit(context)) return null
            // 尽力选中账号并启动 IO（无账号时事件循环仍可跑，账号事件也会进来）
            CoreRuntime.startConfiguredAccount()
            val created = EventBridge(Rpc(PeytBridge), context.applicationContext)
            bridge = created
            created.start()
            Log.d("PEYT", "[eventloop] started")
            return created
        }
    }

    /** 停止事件循环（退出登录/进程收尾时调用）。 */
    fun stop() {
        synchronized(lock) {
            bridge?.stop()
            bridge = null
        }
    }

    /** 重启事件循环（看门狗自愈用：线程死后重新拉起）。 */
    fun restart(context: Context): EventBridge? {
        synchronized(lock) {
            bridge?.stop()
            bridge = null
        }
        return ensureStarted(context)
    }

    /** 事件循环是否正在运行（诊断用）。 */
    fun isRunning(): Boolean = bridge?.isThreadAlive() == true

    /** 向共享事件循环注册监听器，返回注销函数；循环未启动返回 null。 */
    fun addListener(listener: DcEventListener): (() -> Unit)? =
        bridge?.addListener(listener)
}
