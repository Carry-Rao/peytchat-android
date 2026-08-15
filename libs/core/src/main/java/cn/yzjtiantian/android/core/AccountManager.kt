package cn.yzjtiantian.android.core

import org.json.JSONArray
import org.json.JSONObject

/** A deltachat account, either configured or unconfigured. */
data class Account(
    val id: Long,
    val displayName: String?,
    val addr: String?,
    val profileImage: String?,
    val color: String,
    val privateTag: String?,
    val configured: Boolean,
) {
    companion object {
        fun fromJson(obj: JSONObject): Account {
            val kind = obj.optString("kind", "Unconfigured")
            val configured = kind == "Configured"
            return Account(
                id = obj.optLong("id", 0),
                displayName = obj.optStringOrNull("displayName"),
                addr = obj.optStringOrNull("addr"),
                profileImage = obj.optStringOrNull("profileImage"),
                color = obj.optString("color", "#000000"),
                privateTag = obj.optStringOrNull("privateTag"),
                configured = configured,
            )
        }
    }
}

/** Mirrors the desktop `login`/`create_chatmail_account` commands via JSON-RPC. */
class AccountManager(private val rpc: Rpc) {

    /** Adds a new (unconfigured) account and returns its ID. */
    fun addAccount(): Long {
        val result = rpc.callRaw("add_account")
        return (result as? Number)?.toLong() ?: throw RpcException("add_account returned no id")
    }

    /** Removes an account. */
    fun removeAccount(accountId: Long) {
        rpc.callRaw("remove_account", JSONArray().put(accountId))
    }

    /** Lists all accounts. */
    fun getAllAccounts(): List<Account> {
        val arr = rpc.callArray("get_all_accounts")
        return buildList {
            for (i in 0 until arr.length()) {
                add(Account.fromJson(arr.getJSONObject(i)))
            }
        }
    }

    /** Gets info for a single account. */
    fun getAccountInfo(accountId: Long): Account =
        Account.fromJson(rpc.call("get_account_info", JSONArray().put(accountId)))

    fun selectAccount(accountId: Long) {
        rpc.callRaw("select_account", JSONArray().put(accountId))
    }

    fun startIo(accountId: Long) {
        rpc.callRaw("start_io", JSONArray().put(accountId))
    }

    fun startIoForAllAccounts() {
        rpc.callRaw("start_io_for_all_accounts")
    }

    /**
     * Configures a new account with an email address and password.
     *
     * Mirrors the desktop `login` command: add account -> add/update transport
     * -> start IO -> select account. Returns the new account ID.
     */
    fun login(email: String, password: String): Long {
        val id = addAccount()
        try {
            val param = JSONObject()
                .put("addr", email)
                .put("password", password)
            rpc.callRaw("add_or_update_transport", JSONArray().put(id).put(param))
            disableForceEncryption(id)
            startIo(id)
            selectAccount(id)
        } catch (e: Exception) {
            // best-effort cleanup so a failed login doesn't leak an account
            try {
                removeAccount(id)
            } catch (_: Exception) {
            }
            throw e
        }
        return id
    }

    /**
     * Creates a chatmail account via the given server.
     *
     * [server] is a chatmail onboarding URL/host, e.g. `yzjtiantian.cn/new`
     * or `nine.testrun.org/new`. The onboarding POST is issued directly from
     * Android (no JNI/core dependency): we call `POST <server>/new`, parse the
     * returned `{email, password}`, then configure the account with core via
     * `add_or_update_transport` (same path as [login]).
     *
     * This keeps the JNI bridge limited to core's IMAP/SMTP + protocol/plugins,
     * and lets us surface HTTP errors (e.g. a 502 from the chatmail server)
     * directly to the user with proper context.
     */
    fun createChatmailAccount(displayName: String, server: String = DEFAULT_CHATMAIL_SERVER): Long {
        val (email, password) = requestChatmailCredentials(server)
        // Configure the account with core exactly like a classic email login.
        val id = login(email = email, password = password)
        rpc.callRaw("set_config", JSONArray().put(id).put("displayname").put(displayName))
        return id
    }

    /**
     * POSTs to the chatmail onboarding URL and returns the issued credentials.
     * The server responds with `{email, password}` on success, or an error
     * response with a `reason` field on failure.
     */
    private fun requestChatmailCredentials(server: String): Pair<String, String> {
        val url = normalizeChatmailServer(server)
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write("{}".toByteArray()) }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            if (code !in 200..299) {
                val reason = runCatching {
                    JSONObject(body).optString("reason")
                }.getOrDefault(body)
                throw RpcException("chatmail 注册失败 (HTTP $code): $reason")
            }
            val json = JSONObject(body)
            val email = json.optString("email").ifBlank {
                throw RpcException("chatmail 服务器未返回 email")
            }
            val password = json.optString("password").ifBlank {
                throw RpcException("chatmail 服务器未返回 password")
            }
            email to password
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Default chatmail onboarding server (host or URL). */
        const val DEFAULT_CHATMAIL_SERVER: String = "yzjtiantian.cn/new"

        /**
         * Normalizes a user-supplied server into an onboarding HTTP(S) URL.
         * Accepts `example.org`, `example.org/new`, `example.org:443`,
         * `https://example.org/new`, or an already-url `https://...`.
         *
         * A bare host/port (no scheme) is always treated as a **chatmail
         * endpoint**, not a mail server: it is wrapped in `https://`, and the
         * chatmail path (`/new`) is appended if absent. This avoids core's
         * `login_param_from_host`, which would take a bare `host` (even one
         * carrying `:port`) as the email-addressing domain verbatim and fail
         * DNS.
         */
        fun normalizeChatmailServer(server: String): String {
            val trimmed = server.trim().removeSuffix("/")
            if (trimmed.isEmpty()) throw RpcException("empty chatmail server")
            if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                return if (trimmed.endsWith("/new")) trimmed else "$trimmed/new"
            }
            return "https://$trimmed/new"
        }
    }

    fun setConfig(accountId: Long, key: String, value: String?) {
        val arr = JSONArray().put(accountId).put(key)
        if (value == null) {
            arr.put(JSONObject.NULL)
        } else {
            arr.put(value)
        }
        rpc.callRaw("set_config", arr)
    }

    /**
     * Turns off `force_encryption` for the account, mirroring the desktop client.
     *
     * chatmail core defaults to force_encryption=1; with that set, a brand-new chat
     * without the peer's public key cannot send its first (plaintext, Autocrypt
     * key-carrying) message, so the key exchange deadlocks and messages stick at
     * "sending" with a "no e2e" hint. The desktop client disables it to restore the
     * standard Delta flow (first plaintext with key → auto-upgrade to encryption).
     */
    fun disableForceEncryption(accountId: Long) {
        runCatching {
            setConfig(accountId, "force_encryption", "0")
        }
    }

    fun getConfig(accountId: Long, key: String): String? =
        rpc.callRaw("get_config", JSONArray().put(accountId).put(key)) as? String
}

private fun JSONObject.optStringOrNull(key: String): String? {
    val v = opt(key)
    return if (v == null || v === JSONObject.NULL) null else v.toString()
}
