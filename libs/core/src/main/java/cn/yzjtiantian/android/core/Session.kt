package cn.yzjtiantian.android.core

/**
 * Tracks the currently selected account id.
 *
 * Mirrors the desktop backend's `state.current()` which resolves the active
 * deltachat account context. All business RPC calls operate on this account.
 */
object Session {
    @Volatile
    var currentAccountId: Long = 0
        private set

    @Volatile
    var displayName: String = ""

    fun select(accountId: Long) {
        currentAccountId = accountId
    }

    fun clear() {
        currentAccountId = 0
        displayName = ""
    }
}
