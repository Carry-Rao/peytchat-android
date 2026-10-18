package cn.yzjtiantian.android.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dalvik.system.DexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 自托管 DEX 补丁热更新管理器。
 *
 * 完整链路：
 *  1. [checkForUpdates] 拉取更新清单（JSON，**仅允许 https**），与本地已记录版本对比；
 *  2. 仅当清单版本「严格大于」本地版本时，下载 .dex 并做哈希校验（sha256 / md5）；
 *  3. 校验通过后记录到 SharedPreferences（loaded=false）；
 *  4. 下次进程启动时由 [loadPendingPatches] 通过 DexClassLoader 加载，
 *     并把 ClassLoader 注册到 [ModuleManager]。
 *
 * 更新清单 JSON 格式（服务端静态托管即可）：
 * ```json
 * {
 *   "appVersion": "0.1.0",
 *   "patches": [
 *     {
 *       "module": "shell",
 *       "version": "1.0.1",
 *       "url": "https://peyt.org/peytchat-android-update/shell_1.0.1.dex",
 *       "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
 *     }
 *   ]
 * }
 * ```
 * `sha256` 为推荐字段（64 位 hex）；为兼容旧清单仍接受 `md5`（32 位 hex）。
 *
 * ⚠️ 安全边界：哈希只能防「传输损坏」，**不具备身份认证能力**（哈希与文件同源，
 * 可被一同替换）。故本类强制 https；若要防「被控服务器下发恶意补丁」，需再对
 * 清单做非对称签名 + 客户端内置公钥验签（尚未实现）。
 *
 * 已知限制：Android 类加载是 parent-first 的，若补丁类名与基座 APK 内已有类
 * 完全同名，[DexClassLoader] 会解析到基座版本，补丁不生效。要让补丁真正生效，
 * 补丁应提供基座中不存在的新类（例如实现固定接口的 Patch 实现类），由业务代码
 * 通过 [ModuleManager] 反射查找并调用。
 */
class HotUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "PEYT"

        /** 补丁热更新 SharedPreferences 文件名（apk_just_updated 标志也存这里）。 */
        const val PREF_NAME = "peyt_hot_update"
        private const val PATCH_DIR = "patches"

        /** 默认更新清单地址（线上服务端，设置页里也可修改并持久化）。 */
        const val DEFAULT_MANIFEST_URL = "https://peyt.org/peytchat-android-update/update.json"

        // 模块名称常量
        const val MODULE_SHELL = "shell"
        const val MODULE_LOGIN = "login"
        const val MODULE_CHAT = "chat"
        const val MODULE_ACCOUNT = "account"
        const val MODULE_DATA = "data"

        /** 所有可热更新模块。 */
        val ALL_MODULES = listOf(MODULE_SHELL, MODULE_LOGIN, MODULE_CHAT, MODULE_ACCOUNT, MODULE_DATA)

        /**
         * 校验并构造 URL：**只允许 https**。返回 null 表示非法。
         * 更新清单/补丁走明文 HTTP 时可被中间人替换为恶意内容，故一律拒绝。
         */
        internal fun httpsOnly(raw: String): java.net.URL? {
            return try {
                val url = java.net.URL(raw.trim())
                if (url.protocol.equals("https", ignoreCase = true)) url else null
            } catch (e: Exception) {
                null
            }
        }

        /** 语义化版本比较：a > b 返回正数，a == b 返回 0，a < b 返回负数。 */
        internal fun compareVersion(a: String, b: String): Int {
            val pa = a.split('.', '-', '+')
            val pb = b.split('.', '-', '+')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val sa = pa.getOrNull(i) ?: "0"
                val sb = pb.getOrNull(i) ?: "0"
                val ia = sa.toIntOrNull()
                val ib = sb.toIntOrNull()
                val cmp = if (ia != null && ib != null) ia.compareTo(ib) else sa.compareTo(sb)
                if (cmp != 0) return cmp
            }
            return 0
        }

        /** 把来自远端的字符串限制为安全的文件名片段，去掉路径分隔符等。 */
        internal fun safeName(raw: String): String =
            raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "_" }
    }

    /** 一次检查更新的结果，回调在工作线程执行。 */
    data class UpdateResult(
        val success: Boolean,
        val message: String,
        val updated: List<ModuleUpdate> = emptyList(),
    )

    /** 单个模块的更新记录。 */
    data class ModuleUpdate(
        val module: String,
        val oldVersion: String?,
        val newVersion: String,
        val downloaded: Boolean,
    )

    /** 更新清单中的单个补丁描述。`hash` 支持 sha256（64 位 hex）或 md5（32 位 hex）。 */
    data class PatchInfo(
        val module: String,
        val version: String,
        val url: String,
        val hash: String,
    )

    fun interface UpdateCallback {
        fun onResult(result: UpdateResult)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val patchDir: File by lazy {
        File(context.filesDir, PATCH_DIR).apply { mkdirs() }
    }

    // ===== 清单地址与已装版本 =====

    /** 当前使用的更新清单地址。 */
    fun getManifestUrl(): String =
        prefs.getString("manifest_url", DEFAULT_MANIFEST_URL) ?: DEFAULT_MANIFEST_URL

    /** 保存更新清单地址（持久化，下次打开设置仍生效）。 */
    fun setManifestUrl(url: String) {
        prefs.edit().putString("manifest_url", url.trim()).apply()
    }

    /** 某模块已记录的补丁版本，无则返回 null。 */
    fun getInstalledVersion(module: String): String? =
        prefs.getString("${module}_version", null)

    /** 所有模块已记录的补丁版本。 */
    fun getInstalledVersions(): Map<String, String> =
        ALL_MODULES.mapNotNull { m -> getInstalledVersion(m)?.let { m to it } }.toMap()

    // ===== 检查更新 =====

    /** 使用当前保存的清单地址检查更新。 */
    fun checkForUpdates(callback: UpdateCallback) {
        checkForUpdates(getManifestUrl(), callback)
    }

    /**
     * 拉取清单并下载/校验所有比本地新的补丁。
     * 回调在后台线程执行，UI 层需自行切回主线程。
     */
    fun checkForUpdates(manifestUrl: String, callback: UpdateCallback) {
        Thread {
            try {
                val patches = parseManifest(fetchManifest(manifestUrl))
                if (patches.isEmpty()) {
                    callback.onResult(UpdateResult(true, "清单中没有可用的补丁"))
                    return@Thread
                }

                val updated = mutableListOf<ModuleUpdate>()
                for (patch in patches) {
                    val current = getInstalledVersion(patch.module)
                    // 单调递增：仅当清单版本「严格大于」本地版本时才更新；
                    // 相等或更低一律跳过，防止被下发旧版补丁做降级攻击。
                    if (current != null && compareVersion(patch.version, current) <= 0) continue
                    val downloaded = downloadAndVerify(patch)
                    updated += ModuleUpdate(patch.module, current, patch.version, downloaded)
                }

                if (updated.isEmpty()) {
                    callback.onResult(UpdateResult(true, "所有模块已是最新版本"))
                    return@Thread
                }

                val ok = updated.count { it.downloaded }
                val fail = updated.size - ok
                val detail = updated.joinToString("；") {
                    "${it.module}: ${it.oldVersion ?: "无"} → ${it.newVersion}" +
                        "（${if (it.downloaded) "已下载并校验" else "失败"}）"
                }
                callback.onResult(
                    UpdateResult(
                        success = fail == 0,
                        message = "检查完成：成功 $ok 个，失败 $fail 个。$detail",
                        updated = updated,
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "检查更新失败", e)
                callback.onResult(UpdateResult(false, "检查更新失败：${e.message}"))
            }
        }.start()
    }

    /** 拉取更新清单 JSON 文本（仅允许 https）。 */
    private fun fetchManifest(url: String): String {
        val conn = (httpsOnly(url) ?: throw IllegalArgumentException("清单地址必须为 https: $url"))
            .openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.requestMethod = "GET"
        conn.inputStream.bufferedReader().use { return it.readText() }
    }

    /** 解析更新清单，字段缺失的条目会被忽略。 */
    private fun parseManifest(json: String): List<PatchInfo> {
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("patches") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val module = p.optString("module")
                val version = p.optString("version")
                val url = p.optString("url")
                // 推荐 sha256(64 hex)；兼容旧清单的 md5(32 hex)
                val hash = p.optString("sha256").ifEmpty { p.optString("md5") }
                if (module.isNotEmpty() && version.isNotEmpty() && url.isNotEmpty() && hash.isNotEmpty()) {
                    add(PatchInfo(module, version, url, hash))
                }
            }
        }
    }

    // ===== 下载与校验 =====

    /**
     * 下载补丁并校验哈希（sha256/md5）；通过则记录（下次启动由 [loadPendingPatches] 应用）。
     * 返回是否成功。
     */
    private fun downloadAndVerify(patch: PatchInfo): Boolean {
        return try {
            val file = downloadPatch(patch.url, patch.module, patch.version)
                ?: return false
            if (!verifyHash(file, patch.hash)) {
                Log.e(TAG, "哈希校验失败: module=${patch.module}")
                file.delete()
                return false
            }
            prefs.edit()
                .putString("${patch.module}_patch_path", file.absolutePath)
                .putString("${patch.module}_version", patch.version)
                .putBoolean("${patch.module}_loaded", false)
                .apply()
            Log.d(TAG, "补丁已下载并校验: module=${patch.module}, version=${patch.version}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "下载/校验补丁失败: module=${patch.module}", e)
            false
        }
    }

    // ===== 应用（加载） =====

    /**
     * 加载所有已下载待应用的补丁（进程启动时调用）。
     *
     * 注意：DexClassLoader 不会跨进程保留，因此每次启动都需要重新加载注册；
     * 这里先把所有模块的 loaded 标志重置为 false，再逐个加载。
     */
    fun loadPendingPatches() {
        ALL_MODULES.forEach { module ->
            prefs.edit().putBoolean("${module}_loaded", false).apply()
        }
        ALL_MODULES.forEach { module ->
            val patchPath = prefs.getString("${module}_patch_path", null)
            if (patchPath != null) {
                val patchFile = File(patchPath)
                if (patchFile.exists()) {
                    Log.d(TAG, "加载待应用补丁: module=$module path=$patchPath")
                    loadPatch(module, patchFile)
                }
            }
        }
    }

    /** 下载并立即应用补丁（由事件通道触发时使用）。 */
    fun downloadAndApplyPatch(
        url: String,
        module: String,
        version: String,
        hash: String,
    ) {
        Thread {
            try {
                val patchFile = downloadPatch(url, module, version)
                if (patchFile == null) {
                    Log.e(TAG, "下载补丁失败: $module")
                    return@Thread
                }
                if (!verifyHash(patchFile, hash)) {
                    Log.e(TAG, "哈希校验失败: $module")
                    patchFile.delete()
                    return@Thread
                }
                val success = loadPatch(module, patchFile)
                if (success) {
                    prefs.edit()
                        .putString("${module}_patch_path", patchFile.absolutePath)
                        .putString("${module}_version", version)
                        .putBoolean("${module}_loaded", true)
                        .apply()
                    Log.d(TAG, "补丁应用成功: module=$module")
                }
            } catch (e: Exception) {
                Log.e(TAG, "应用补丁失败: $module", e)
            }
        }.start()
    }

    /**
     * 通过 DexClassLoader 加载补丁并注册到 [ModuleManager]。
     *
     * 优先尝试按约定入口类加载（见 [tryLoadPatchEntry]），这才是真正生效的
     * 补丁；入口类缺失时退回旧逻辑（仅验证基座类可解析）。
     */
    fun loadPatch(module: String, patchFile: File): Boolean {
        return try {
            val dexDir = context.getDir("dex", Context.MODE_PRIVATE)

            val classLoader = DexClassLoader(
                patchFile.absolutePath,
                dexDir.absolutePath,
                null,
                context.classLoader
            )

            val loaded = tryLoadPatchEntry(module, classLoader) ||
                loadBaseModuleClass(module, classLoader)

            if (loaded) {
                ModuleManager.registerModule(module, classLoader)
                prefs.edit().putBoolean("${module}_loaded", true).apply()
            }
            loaded
        } catch (e: Exception) {
            Log.e(TAG, "加载补丁失败: $module", e)
            false
        }
    }

    /** 从路径应用补丁。 */
    fun applyPatchFromPath(module: String, patchPath: String): Boolean {
        val patchFile = File(patchPath)
        if (!patchFile.exists()) {
            Log.e(TAG, "补丁文件不存在: $patchPath")
            return false
        }
        return loadPatch(module, patchFile)
    }

    /** 更新指定模块（事件通道触发；当前实现为触发一次完整检查）。 */
    fun updateModule(module: String, version: String) {
        Log.d(TAG, "更新模块: module=$module, version=$version")
        checkForUpdates { result ->
            Log.d(TAG, "updateModule 结果: ${result.message}")
        }
    }

    // ===== 私有方法 =====

    private fun downloadPatch(url: String, module: String, version: String): File? {
        return try {
            val connection = (httpsOnly(url) ?: throw IllegalArgumentException("补丁地址必须为 https: $url"))
                .openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000

            // module/version 来自远端清单，禁止其影响落地路径
            val patchFile = File(patchDir, "${safeName(module)}_${safeName(version)}.dex")
            connection.inputStream.use { input ->
                patchFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "补丁下载完成: ${patchFile.absolutePath}")
            patchFile
        } catch (e: Exception) {
            Log.e(TAG, "下载补丁失败", e)
            null
        }
    }

    /**
     * 校验文件哈希。算法按 `expected` 长度自动判定：64 位 hex → SHA-256，32 位 → MD5。
     * 注意：哈希只能防「内容损坏」，不能防「被同时替换」（无身份认证能力）。
     */
    private fun verifyHash(file: File, expected: String): Boolean {
        return try {
            val algo = when (expected.trim().length) {
                64 -> "SHA-256"
                32 -> "MD5"
                else -> return false
            }
            val digest = MessageDigest.getInstance(algo).digest(file.readBytes())
            val actual = digest.joinToString("") { "%02x".format(it) }
            actual.equals(expected.trim(), ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "哈希校验失败", e)
            false
        }
    }

    // ===== 补丁入口类加载 =====

    /**
     * 尝试按约定加载补丁入口类，这是补丁**真正生效**的机制。
     *
     * 约定（补丁作者只需遵守，无需依赖 App 任何接口）：
     * - 入口类名：`cn.yzjtiantian.android.patch.<Module>Patch`
     *   （shell → ShellPatch，login → LoginPatch，chat → ChatPatch，account → AccountPatch）
     * - 类需有 public 无参构造函数；
     * - 可选方法（按方法名反射调用）：
     *   - `String module()`      模块名（缺省用清单中的 module）
     *   - `String version()`     补丁版本
     *   - `String description()` 描述（设置页展示用）
     *   - `boolean apply(Context)` 应用补丁，返回是否成功（缺省视为成功）
     *
     * 入口类名在基座中不存在，parent-first 类加载下会命中补丁 dex 中的类，
     * 因此能真正被实例化调用。
     */
    private fun tryLoadPatchEntry(module: String, classLoader: ClassLoader): Boolean {
        val entryName =
            "cn.yzjtiantian.android.patch.${module.replaceFirstChar { it.uppercase() }}Patch"
        return try {
            val clazz = classLoader.loadClass(entryName)
            val instance = clazz.getDeclaredConstructor().newInstance()

            val moduleName = invokeString(instance, "module") ?: module
            val version = invokeString(instance, "version") ?: ""
            val description = invokeString(instance, "description") ?: ""

            val applied = try {
                val method = clazz.getMethod("apply", Context::class.java)
                (method.invoke(instance, context.applicationContext) as? Boolean) ?: true
            } catch (e: NoSuchMethodException) {
                true // 未提供 apply()，视为加载成功
            }

            if (applied) {
                ModuleManager.registerPatch(
                    LoadedPatch(moduleName, version, description, instance, classLoader)
                )
                Log.d(TAG, "补丁加载成功: $moduleName v$version — $description")
            }
            applied
        } catch (e: ClassNotFoundException) {
            // 该 dex 没有约定入口类（可能是普通 dex 或旧格式），返回 false 走旧逻辑
            Log.d(TAG, "补丁入口类未找到: $entryName")
            false
        } catch (e: Exception) {
            Log.e(TAG, "补丁入口加载失败: $entryName", e)
            false
        }
    }

    /** 反射调用返回 String 的约定方法，失败返回 null。 */
    private fun invokeString(instance: Any, methodName: String): String? {
        return try {
            instance.javaClass.getMethod(methodName).invoke(instance) as? String
        } catch (e: Exception) {
            null
        }
    }

    /** 旧式加载：仅验证基座类可解析（parent-first 下补丁无法覆盖同名类）。 */
    private fun loadBaseModuleClass(module: String, classLoader: ClassLoader): Boolean {
        val baseName = when (module) {
            MODULE_SHELL -> "cn.yzjtiantian.android.ui.shell.ShellScreen"
            MODULE_LOGIN -> "cn.yzjtiantian.android.ui.login.LoginScreen"
            MODULE_CHAT -> "cn.yzjtiantian.android.ui.shell.ChatScreen"
            MODULE_ACCOUNT -> "cn.yzjtiantian.android.ui.shell.AccountPage"
            else -> return false
        }
        return try {
            classLoader.loadClass(baseName)
            Log.d(TAG, "模块类可解析: $module ($baseName)")
            true
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "模块类未找到: $baseName", e)
            false
        }
    }
}
