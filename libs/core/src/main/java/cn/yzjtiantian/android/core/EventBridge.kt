package cn.yzjtiantian.android.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single deltachat core event dispatched to listeners.
 *
 * `kind` is the camelCase event kind from the JSON-RPC `EventType` enum
 * (e.g. "IncomingMsg", "MsgsChanged", "ConfigureProgress", ...).
 */
data class DcEvent(
    val kind: String,
    val contextId: Long,
    val payload: JSONObject,
)

fun interface DcEventListener {
    fun onEvent(event: DcEvent)
}

/**
 * Bridges deltachat core events to the UI layer.
 *
 * Deltachat's `get_next_event_batch` is a blocking RPC that returns as soon as
 * at least one event has fired. This class runs a dedicated daemon thread that
 * loops over the call and dispatches each event to registered listeners.
 */
class EventBridge(
    private val rpc: Rpc,
    private val context: Context? = null,  // ✅ 添加上下文用于热更新
) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private val listeners = CopyOnWriteArrayList<DcEventListener>()

    // ✅ 热更新管理器
    private val hotUpdateManager by lazy {
        context?.let { HotUpdateManager(it) }
    }

    fun addListener(listener: DcEventListener): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(::loop, "peyt-event-bridge").also { it.isDaemon = true }.also { it.start() }

        // ✅ 启动时检查待加载的补丁
        hotUpdateManager?.loadPendingPatches()
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    /** 事件消费线程是否存活（诊断用：true=事件循环在跑）。 */
    fun isThreadAlive(): Boolean = thread?.isAlive == true

    private fun loop() {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val batch: Any? = try {
                rpc.callRaw("get_next_event_batch")
            } catch (e: Exception) {
                if (running.get()) {
                    // brief backoff before retrying; core may not be initialized yet
                    try {
                        Thread.sleep(500)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                null
            }
            if (batch !is JSONArray) continue
            for (i in 0 until batch.length()) {
                val item = batch.optJSONObject(i) ?: continue
                val event = item.optJSONObject("event") ?: continue
                val kind = event.optString("kind", "")
                if (kind.isEmpty()) continue
                val contextId = item.optLong("contextId", -1)
                val dcEvent = DcEvent(kind = kind, contextId = contextId, payload = event)
                android.util.Log.d("PEYT", "[event] ctx=$contextId kind=$kind payload=${event}")

                // ✅ 处理热更新事件（必须兜底：异常逃逸会杀死事件线程，导致整个事件循环停摆）
                try {
                    handleHotUpdateEvent(dcEvent)
                } catch (e: Exception) {
                    android.util.Log.w("EventBridge", "hot-update handler failed for $kind", e)
                }

                for (l in listeners) {
                    try {
                        l.onEvent(dcEvent)
                    } catch (e: Exception) {
                        android.util.Log.w("EventBridge", "listener failed for $kind", e)
                    }
                }
            }
        }
    }

    // ✅ 处理热更新相关事件
    private fun handleHotUpdateEvent(event: DcEvent) {
        when (event.kind) {
            "UpdateAvailable" -> {
                // 服务器通知有更新
                val patchUrl = event.payload.optString("patchUrl")
                val version = event.payload.optString("version")
                val module = event.payload.optString("module")
                val md5 = event.payload.optString("md5")

                android.util.Log.d("PEYT", "热更新可用: module=$module, version=$version")

                // 后台下载补丁
                hotUpdateManager?.downloadAndApplyPatch(
                    url = patchUrl,
                    module = module,
                    version = version,
                    md5 = md5
                )
            }
            "ModuleUpdate" -> {
                // 特定模块更新指令
                val module = event.payload.optString("module")
                val version = event.payload.optString("version")

                android.util.Log.d("PEYT", "模块更新指令: module=$module, version=$version")

                hotUpdateManager?.updateModule(module, version)
            }
            "PatchReady" -> {
                // 补丁已准备好，可以应用
                val patchPath = event.payload.optString("patchPath")
                val module = event.payload.optString("module")

                android.util.Log.d("PEYT", "补丁已准备: module=$module, path=$patchPath")

                hotUpdateManager?.applyPatchFromPath(module, patchPath)
            }
        }
    }
}