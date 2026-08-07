package cn.yzjtiantian.android.core

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
class EventBridge(private val rpc: Rpc) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private val listeners = CopyOnWriteArrayList<DcEventListener>()

    fun addListener(listener: DcEventListener): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(::loop, "peyt-event-bridge").also { it.isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

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
}
