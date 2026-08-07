package cn.yzjtiantian.android.core

/**
 * PEYT 深链处理。
 *
 * Web 端发出 core 标准 securejoin 链接 `OPENPGP4FPR:<token>`；前端 patch 会把
 * `OPENPGP4FPR:` 前缀替换成 `peytchat://`，使 Android 能被自定义 scheme 唤起。
 * 收到后在此把 `peytchat://` 前缀换回 `OPENPGP4FPR:`，还原成 core 可解析的格式。
 */
object DeepLink {

    /** Android 侧自定义 URL scheme。 */
    const val SCHEME = "peytchat"

    private const val PREFIX = "$SCHEME://"

    /**
     * 归一化外部链接为 core 可解析的形式：
     * - `peytchat://<token>` → `OPENPGP4FPR:<token>`（securejoin）
     * - 其余（纯邮箱 / `https://i.delta.chat/...` / `OPENPGP4FPR:...`）原样返回
     */
    fun toCore(raw: String): String {
        val input = raw.trim()
        if (!input.startsWith(PREFIX, ignoreCase = true)) return input
        return "OPENPGP4FPR:" + input.substring(PREFIX.length)
    }
}
