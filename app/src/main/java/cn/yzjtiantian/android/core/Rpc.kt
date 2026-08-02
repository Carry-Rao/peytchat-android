package cn.yzjtiantian.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal JSON-RPC 2.0 client over the deltachat core session.
 *
 * Each call builds a `{"jsonrpc":"2.0","id":N,"method":...,"params":...}`
 * request and executes it synchronously via [PeytBridge.nativeJsonrpcCall].
 */
class Rpc(private val bridge: PeytBridge) {

    private val pending = java.util.concurrent.atomic.AtomicLong(0)

    fun call(method: String, params: JSONArray = JSONArray()): JSONObject {
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", pending.incrementAndGet())
            .put("method", method)
            .put("params", params)

        val responseJson = bridge.nativeJsonrpcCall(request.toString())
        val response = JSONObject(responseJson)

        if (response.has("error")) {
            val err = response.getJSONObject("error")
            throw RpcException(err.optString("message", "unknown RPC error"))
        }
        return response.optJSONObject("result")
            ?: throw RpcException("missing result in response for $method")
    }

    /** Convenience for calls whose result is a plain value (not an object). */
    fun callValue(method: String, params: JSONArray = JSONArray()): Any? {
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", pending.incrementAndGet())
            .put("method", method)
            .put("params", params)

        val responseJson = bridge.nativeJsonrpcCall(request.toString())
        val response = JSONObject(responseJson)

        if (response.has("error")) {
            val err = response.getJSONObject("error")
            throw RpcException(err.optString("message", "unknown RPC error"))
        }
        if (!response.has("result")) {
            return null
        }
        val result = response.opt("result")
        return if (result === JSONObject.NULL) null else result
    }
}

class RpcException(message: String) : RuntimeException(message)
