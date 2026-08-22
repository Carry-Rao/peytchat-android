package cn.yzjtiantian.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-RPC 2.0 client over the deltachat core session.
 *
 * Each call builds a `{"jsonrpc":"2.0","id":N,"method":...,"params":...}`
 * request and executes it synchronously via [PeytBridge.nativeJsonrpcCall].
 */
class Rpc(private val bridge: PeytBridge) {

    private val pending = java.util.concurrent.atomic.AtomicLong(0)

    private fun send(method: String, params: JSONArray): JSONObject {
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", pending.incrementAndGet())
            .put("method", method)
            .put("params", params)

        val responseJson = bridge.nativeJsonrpcCall(request.toString())
        val response = JSONObject(responseJson)

        if (response.has("error")) {
            val err = response.getJSONObject("error")
            val code = err.optInt("code", 0)
            val msg = err.optString("message", "unknown RPC error")
            throw RpcException(msg, code)
        }
        return response
    }

    /** Perform an RPC call and return the raw `result` value (may be null). */
    fun callRaw(method: String, params: JSONArray = JSONArray()): Any? {
        val response = send(method, params)
        if (!response.has("result")) return null
        val result = response.opt("result")
        return if (result === JSONObject.NULL) null else result
    }

    /** Perform an RPC call expecting an object result. */
    fun call(method: String, params: JSONArray = JSONArray()): JSONObject {
        val result = callRaw(method, params) ?: throw RpcException("missing result for $method")
        return result as? JSONObject ?: throw RpcException("expected object result for $method")
    }

    /** Perform an RPC call expecting an array result. */
    fun callArray(method: String, params: JSONArray = JSONArray()): JSONArray {
        val result = callRaw(method, params) ?: return JSONArray()
        return result as? JSONArray ?: throw RpcException("expected array result for $method")
    }
}

class RpcException(message: String, val rpcCode: Int = 0) : RuntimeException(message)
