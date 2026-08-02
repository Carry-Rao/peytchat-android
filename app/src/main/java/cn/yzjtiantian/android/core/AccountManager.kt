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
     * Creates a chatmail account via the nine.testrun.org QR invite.
     * Mirrors the desktop `create_chatmail_account` command.
     */
    fun createChatmailAccount(displayName: String): Long {
        val id = addAccount()
        try {
            rpc.callRaw("add_transport_from_qr", JSONArray().put(id).put("dcaccount:nine.testrun.org"))
            rpc.call("set_config", JSONArray().put(id).put("displayname").put(displayName))
            selectAccount(id)
            startIo(id)
        } catch (e: Exception) {
            try {
                removeAccount(id)
            } catch (_: Exception) {
            }
            throw e
        }
        return id
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

    fun getConfig(accountId: Long, key: String): String? =
        rpc.callRaw("get_config", JSONArray().put(accountId).put(key)) as? String
}

private fun JSONObject.optStringOrNull(key: String): String? {
    val v = opt(key)
    return if (v == null || v === JSONObject.NULL) null else v.toString()
}
