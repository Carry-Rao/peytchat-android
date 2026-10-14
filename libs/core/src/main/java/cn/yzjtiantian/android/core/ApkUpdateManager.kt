package cn.yzjtiantian.android.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内大版本（APK）更新管理器，仿「大版本走 APK 安装、小版本走热更新补丁」的两段式更新。
 *
 * 链路：
 *  1. [checkForApkUpdate] 拉取更新清单，解析顶层 `apk` 字段（可选，缺省表示无 APK 更新）；
 *  2. 清单 `apk.versionCode` 严格大于当前 [getCurrentVersionCode] 时提示更新；
 *  3. [downloadApk] 流式下载到 `filesDir/apks/`，边下边回调进度，完成后校验 MD5；
 *  4. [installApk] 通过 FileProvider content URI 调系统安装器（用户确认后安装）。
 *
 * 清单 `apk` 字段格式：
 * ```json
 * {
 *   "appVersion": "0.2.0",
 *   "apk": {
 *     "versionCode": 2,
 *     "versionName": "0.2.0",
 *     "url": "https://peyt.org/peytchat-android-update/apk/peytchat-0.2.0.apk",
 *     "md5": "a1b2c3d4e5f6...",
 *     "size": 12582912,
 *     "releaseNotes": "1. 修复邀请链接 bug\n2. ...",
 *     "forceUpdate": false,
 *     "minSupportedVersion": 1
 *   },
 *   "patches": [ ... ]
 * }
 * ```
 *
 * 注意：覆盖安装要求新旧 APK 签名证书完全一致，否则系统提示「签名冲突」。
 */
class ApkUpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "PEYT"
        private const val APK_DIR = "apks"

        /**
         * 「APK 刚完成安装，下次启动应自动补装补丁」标志。
         * 下载成功、调起安装前写入；新 APK 首次启动时消费并触发一次补丁检查
         * （新基座类可能变了，旧补丁不一定兼容，装完再按新清单拉补丁更安全）。
         */
        const val PREF_APK_JUST_UPDATED = "apk_just_updated"
    }

    /** 更新清单中的 APK 描述。 */
    data class ApkInfo(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val md5: String,
        val size: Long,              // 字节数，-1 表示清单未提供
        val releaseNotes: String,
        val forceUpdate: Boolean,
        val minSupportedVersion: Int, // -1 表示未提供
    )

    /** 一次 APK 更新检查的结果，回调在工作线程执行。 */
    data class ApkUpdateResult(
        val success: Boolean,        // 清单拉取/解析是否成功
        val hasUpdate: Boolean,      // 是否有新版本 APK
        val info: ApkInfo? = null,
        val message: String = "",
    )

    fun interface CheckCallback {
        fun onResult(result: ApkUpdateResult)
    }

    fun interface DownloadCallback {
        fun onResult(success: Boolean, apkFile: File?, message: String)
    }

    fun interface ProgressCallback {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long) // totalBytes <= 0 表示未知
    }

    private val prefs =
        context.getSharedPreferences(HotUpdateManager.PREF_NAME, Context.MODE_PRIVATE)

    private val cancelled = AtomicBoolean(false)

    private val apkDir: File by lazy {
        File(context.filesDir, APK_DIR).apply { mkdirs() }
    }

    // ===== 版本比较 =====

    /** 当前安装包的 versionCode（从 PackageManager 读取，library 模块拿不到 app 的 BuildConfig）。 */
    fun getCurrentVersionCode(): Int {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取当前版本号失败", e)
            0
        }
    }

    // ===== 检查更新 =====

    /** 使用当前保存的清单地址检查 APK 更新。 */
    fun checkForApkUpdate(callback: CheckCallback) {
        checkForApkUpdate(HotUpdateManager(context).getManifestUrl(), callback)
    }

    /**
     * 拉取清单并解析 `apk` 字段，比较 versionCode 判断是否有新版本。
     * 回调在后台线程执行，UI 层需自行切回主线程。
     */
    fun checkForApkUpdate(manifestUrl: String, callback: CheckCallback) {
        Thread {
            try {
                val json = fetchManifest(manifestUrl)
                val info = parseApkInfo(json)
                if (info == null) {
                    callback.onResult(ApkUpdateResult(true, false, message = "清单中没有 APK 更新"))
                    return@Thread
                }
                val current = getCurrentVersionCode()
                val hasUpdate = info.versionCode > current ||
                    (info.minSupportedVersion > 0 && current < info.minSupportedVersion)
                Log.d(
                    TAG,
                    "APK 检查更新: current=$current, remote=${info.versionCode}, hasUpdate=$hasUpdate"
                )
                callback.onResult(
                    ApkUpdateResult(true, hasUpdate, info.takeIf { hasUpdate }, if (hasUpdate) "发现新版本" else "已是最新版本")
                )
            } catch (e: Exception) {
                Log.e(TAG, "APK 检查更新失败", e)
                callback.onResult(ApkUpdateResult(false, false, message = "检查更新失败：${e.message}"))
            }
        }.start()
    }

    private fun fetchManifest(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.requestMethod = "GET"
        conn.inputStream.bufferedReader().use { return it.readText() }
    }

    /** 解析清单顶层 `apk` 字段；缺失或字段不完整返回 null。 */
    private fun parseApkInfo(json: String): ApkInfo? {
        val apk = JSONObject(json).optJSONObject("apk") ?: return null
        val versionCode = apk.optInt("versionCode", 0)
        val versionName = apk.optString("versionName")
        val url = apk.optString("url")
        if (versionCode <= 0 || versionName.isEmpty() || url.isEmpty()) return null
        return ApkInfo(
            versionCode = versionCode,
            versionName = versionName,
            url = url,
            md5 = apk.optString("md5"),
            size = apk.optLong("size", -1L),
            releaseNotes = apk.optString("releaseNotes"),
            forceUpdate = apk.optBoolean("forceUpdate", false),
            minSupportedVersion = apk.optInt("minSupportedVersion", -1),
        )
    }

    // ===== 下载 =====

    /** 取消正在进行的下载（下次下载前自动复位）。 */
    fun cancelDownload() {
        cancelled.set(true)
    }

    /**
     * 下载 APK 到 `filesDir/apks/peytchat-<versionName>.apk`，边下边回调进度，
     * 完成后校验 MD5。成功时写入 [PREF_APK_JUST_UPDATED] 标志（供下次启动补装补丁）。
     * 回调（进度与结果）在工作线程执行，UI 层需自行切回主线程。
     */
    fun downloadApk(info: ApkInfo, onProgress: ProgressCallback?, callback: DownloadCallback) {
        cancelled.set(false)
        Thread {
            val dest = File(apkDir, "peytchat-${info.versionName}.apk")
            try {
                val conn = URL(info.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                // 禁用 gzip，保证 Content-Length 是原始大小、进度条百分比准确
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.instanceFollowRedirects = true

                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size.takeIf { it > 0 } ?: -1L

                var downloaded = 0L
                var lastNotified = 0L
                conn.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled.get()) {
                                conn.disconnect()
                                dest.delete()
                                callback.onResult(false, null, "下载已取消")
                                return@Thread
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            // 每读约 64KB 回调一次进度，避免刷爆主线程
                            if (downloaded - lastNotified >= 64 * 1024) {
                                lastNotified = downloaded
                                onProgress?.onProgress(downloaded, total)
                            }
                        }
                    }
                }
                conn.disconnect()
                onProgress?.onProgress(downloaded, if (total > 0) total else downloaded)

                if (info.md5.isNotEmpty() && !verifyMd5(dest, info.md5)) {
                    Log.e(TAG, "APK MD5 校验失败: ${dest.name}")
                    dest.delete()
                    callback.onResult(false, null, "MD5 校验失败，已丢弃下载文件")
                    return@Thread
                }

                // 标记「装完新 APK 后要自动补装补丁」；App 进程会被安装杀掉，
                // 重启时 EventBridge 读到该标志就静默跑一次补丁检查
                prefs.edit().putBoolean(PREF_APK_JUST_UPDATED, true).apply()

                Log.d(TAG, "APK 下载完成: ${dest.absolutePath} ($downloaded bytes)")
                callback.onResult(true, dest, "下载完成")
            } catch (e: Exception) {
                Log.e(TAG, "APK 下载失败", e)
                dest.delete()
                callback.onResult(false, null, "下载失败：${e.message}")
            }
        }.start()
    }

    private fun verifyMd5(file: File, expectedMd5: String): Boolean {
        return try {
            val digest = MessageDigest.getInstance("MD5").digest(file.readBytes())
            val actual = digest.joinToString("") { "%02x".format(it) }
            actual.equals(expectedMd5, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "MD5 校验异常", e)
            false
        }
    }

    // ===== 安装 =====

    /** 当前应用是否已被用户允许安装未知来源应用（Android 8.0+）。 */
    fun canRequestInstall(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * 通过 FileProvider 调起系统安装器安装 APK（用户确认后才会安装）。
     * 未授予「安装未知应用」权限时跳转设置页并返回 false。
     *
     * @return true 表示已发起安装流程
     */
    fun installApk(apkFile: File): Boolean {
        if (!apkFile.exists()) {
            Log.e(TAG, "APK 文件不存在: ${apkFile.absolutePath}")
            return false
        }
        if (!canRequestInstall()) {
            Log.d(TAG, "未授予安装未知应用权限，跳转设置页")
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return false
        }
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apkFile
            )
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "调起安装失败", e)
            false
        }
    }
}
