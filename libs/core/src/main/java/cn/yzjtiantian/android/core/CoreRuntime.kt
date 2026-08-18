package cn.yzjtiantian.android.core

import android.content.Context
import android.util.Log

/**
 * 进程级核心运行时：保证 `PeytBridge.nativeInit` 每个进程只执行一次，
 * 并提供「选中已配置账号 + 启动 IO」的公共引导逻辑。
 *
 * 消息接收前台服务（常驻进程）与 Activity 共用同一套初始化，
 * 避免 `nativeInit` 被重复调用（Rust 侧全局 RPC 句柄会被覆盖导致事件丢失）。
 */
object CoreRuntime {

    private val lock = Any()

    @Volatile
    private var initialized = false

    /**
     * 初始化 deltachat core（幂等，进程内只执行一次）。
     * 返回是否成功。
     */
    fun ensureInit(context: Context): Boolean {
        if (initialized) return true
        synchronized(lock) {
            if (initialized) return true
            val ok = runCatching {
                val dataDir = context.filesDir.absolutePath
                PeytBridge.nativeInit(dataDir)
                PeytBridge.nativePluginsInit(dataDir)
            }.isSuccess
            initialized = ok
            if (ok) {
                Log.d("PEYT", "[core] native init ok")
            } else {
                Log.e("PEYT", "[core] native init failed")
            }
            return ok
        }
    }

    /** 是否存在已配置（已登录）的账号。 */
    fun hasConfiguredAccount(): Boolean {
        if (!initialized) return false
        return runCatching {
            AccountManager(Rpc(PeytBridge)).getAllAccounts().any { it.configured }
        }.getOrDefault(false)
    }

    /**
     * 选中已配置账号、设置显示名、关闭 force_encryption 并启动 IO。
     * 无已配置账号返回 false。
     */
    fun startConfiguredAccount(): Boolean {
        if (!initialized) return false
        return runCatching {
            val manager = AccountManager(Rpc(PeytBridge))
            val id = manager.getAllAccounts().firstOrNull { it.configured }?.id
                ?: return false
            Session.select(id)
            Session.displayName = manager.getConfig(id, "displayname") ?: ""
            manager.disableForceEncryption(id)
            manager.startIo(id)
            Log.d("PEYT", "[core] account=$id started IO")
            true
        }.getOrDefault(false)
    }
}
