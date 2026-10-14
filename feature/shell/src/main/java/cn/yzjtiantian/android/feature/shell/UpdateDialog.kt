package cn.yzjtiantian.android.ui.shell

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.core.ApkUpdateManager
import cn.yzjtiantian.android.core.HotUpdateManager
import cn.yzjtiantian.android.core.ModuleManager
import java.io.File
import java.util.Locale

/**
 * 「检查更新」对话框：
 * - 顶部：APK 大版本更新（拉取清单 `apk` 字段 → 下载 → 系统安装器安装）；
 * - 底部：原有补丁热更新（拉取清单 `patches` → 下载并校验 → 记录，下次启动应用）。
 *
 * APK 更新状态机：idle → checking → downloading(progress) → readyToInstall → installing。
 * 更新清单地址默认取 [HotUpdateManager.DEFAULT_MANIFEST_URL]，可在此修改并持久化，
 * 方便接入自己的服务端。
 */

/** APK 更新流程状态。 */
private enum class ApkUpdateState { Idle, Checking, Downloading, ReadyToInstall, Installing }

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hotUpdate = remember(context) { HotUpdateManager(context.applicationContext) }
    val apkUpdate = remember(context) { ApkUpdateManager(context.applicationContext) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var url by remember { mutableStateOf(hotUpdate.getManifestUrl()) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<HotUpdateManager.UpdateResult?>(null) }
    val installed = remember { hotUpdate.getInstalledVersions() }
    // 本进程已加载生效的补丁（启动时由 loadPendingPatches 注册）
    val loadedPatches = remember { ModuleManager.getLoadedPatches() }
    // 示例：补丁通过 apply() 写入的提示消息（见 patch-sample/ShellPatch.java）
    val patchMessage = remember {
        context.getSharedPreferences("peyt_hot_update", Context.MODE_PRIVATE)
            .getString("patch_message", null)
    }

    // ===== APK 大版本更新状态 =====
    var apkState by remember { mutableStateOf(ApkUpdateState.Idle) }
    var apkInfo by remember { mutableStateOf<ApkUpdateManager.ApkInfo?>(null) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    var apkError by remember { mutableStateOf<String?>(null) }
    // 0f..1f；-1f 表示总大小未知
    var apkProgress by remember { mutableStateOf(-1f) }
    var apkProgressText by remember { mutableStateOf("") }

    /** 后台下载 APK，进度/结果回调切回主线程。 */
    fun startDownload(info: ApkUpdateManager.ApkInfo) {
        apkState = ApkUpdateState.Downloading
        apkError = null
        apkProgress = if (info.size > 0) 0f else -1f
        apkProgressText = "准备下载…"
        apkUpdate.downloadApk(
            info,
            onProgress = { done, total ->
                mainHandler.post {
                    apkProgress = if (total > 0) (done.toDouble() / total).toFloat() else -1f
                    apkProgressText = if (total > 0) {
                        "${formatBytes(done)} / ${formatBytes(total)}（${(apkProgress * 100).toInt()}%）"
                    } else {
                        "已下载 ${formatBytes(done)}"
                    }
                }
            },
        ) { success, file, message ->
            mainHandler.post {
                if (success && file != null) {
                    apkFile = file
                    apkProgress = 1f
                    apkProgressText = "下载完成"
                    // 下载完成后自动调起系统安装；未授予「安装未知应用」权限时
                    // installApk 会跳转设置页，回到对话框后可点「立即安装」重试
                    apkState = ApkUpdateState.ReadyToInstall
                    apkState = ApkUpdateState.Installing
                    if (!apkUpdate.installApk(file)) {
                        apkError = "请允许「安装未知应用」权限后重试"
                        apkState = ApkUpdateState.ReadyToInstall
                    }
                } else {
                    apkError = message
                    apkProgressText = ""
                    apkState = ApkUpdateState.Idle
                }
            }
        }
    }

    // 打开对话框时先检查一次 APK 更新
    LaunchedEffect(Unit) {
        apkState = ApkUpdateState.Checking
        apkError = null
        apkUpdate.checkForApkUpdate(url) { r ->
            mainHandler.post {
                apkState = ApkUpdateState.Idle
                if (r.success && r.hasUpdate && r.info != null) {
                    apkInfo = r.info
                } else {
                    apkInfo = null
                    if (!r.success) apkError = r.message
                }
            }
        }
    }

    // 下载完成后已在上面的回调里自动调起安装（见 startDownload），
    // 这里无需额外的 LaunchedEffect，避免「失败 → ReadyToInstall」重试死循环。

    val apkBusy = apkState == ApkUpdateState.Downloading || apkState == ApkUpdateState.Installing

    AlertDialog(
        onDismissRequest = {
            if (!checking && !apkBusy) onDismiss()
        },
        title = { Text("检查更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ===== APK 大版本更新 =====
                if (apkState == ApkUpdateState.Checking) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text("正在检查新版本…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                apkInfo?.let { info ->
                    Text(
                        text = "发现新版本 v${info.versionName}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (info.releaseNotes.isNotEmpty()) {
                        Text(
                            text = info.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    when (apkState) {
                        ApkUpdateState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { if (apkProgress < 0) 0f else apkProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(apkProgressText, style = MaterialTheme.typography.bodySmall)
                        }
                        ApkUpdateState.ReadyToInstall -> {
                            Text(
                                text = "APK 已下载完成，可以安装。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        ApkUpdateState.Installing -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Text("正在调起安装…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        else -> {}
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (apkState) {
                            ApkUpdateState.Idle -> {
                                TextButton(onClick = { startDownload(info) }) { Text("立即更新") }
                                if (!info.forceUpdate) {
                                    TextButton(onClick = { apkInfo = null }) { Text("稍后再说") }
                                }
                            }
                            ApkUpdateState.Downloading -> {
                                TextButton(onClick = {
                                    apkUpdate.cancelDownload()
                                }) { Text("取消下载") }
                            }
                            ApkUpdateState.ReadyToInstall -> {
                                TextButton(onClick = {
                                    val file = apkFile
                                    if (file != null) {
                                        apkState = ApkUpdateState.Installing
                                        if (!apkUpdate.installApk(file)) {
                                            apkError = "请允许「安装未知应用」权限后重试"
                                            apkState = ApkUpdateState.ReadyToInstall
                                        }
                                    }
                                }) { Text("立即安装") }
                            }
                            else -> {}
                        }
                    }
                }

                apkError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // ===== 补丁热更新（原有功能，与 APK 更新互不影响） =====
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("更新清单地址") },
                    singleLine = true,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 已记录版本
                if (installed.isNotEmpty()) {
                    Text(
                        text = "已安装补丁：" + installed.entries.joinToString("、") { "${it.key} v${it.value}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 本进程已加载生效的补丁（说明补丁确实被加载并调用了）
                if (loadedPatches.isNotEmpty()) {
                    Text(
                        text = "已加载补丁：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    loadedPatches.forEach { p ->
                        Text(
                            text = "${p.module} v${p.version} — ${p.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                patchMessage?.let {
                    Text(
                        text = "补丁消息：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 检查结果
                result?.let { r ->
                    Text(
                        text = r.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.success) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    r.updated.forEach { u ->
                        Text(
                            text = "${u.module}: ${u.oldVersion ?: "无"} → ${u.newVersion}" +
                                "（${if (u.downloaded) "已下载并校验" else "失败"}）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // 进行中
                if (checking) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            text = "正在检查更新…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Text(
                    text = "补丁下载并校验通过后，将在下次启动时应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !checking,
                onClick = {
                    hotUpdate.setManifestUrl(url)
                    checking = true
                    result = null
                    hotUpdate.checkForUpdates(url) { r ->
                        Handler(Looper.getMainLooper()).post {
                            checking = false
                            result = r
                        }
                    }
                    // 清单地址可能刚被修改，重新检查一次 APK 更新
                    apkState = ApkUpdateState.Checking
                    apkUpdate.checkForApkUpdate(url) { r ->
                        mainHandler.post {
                            apkState = ApkUpdateState.Idle
                            if (r.success && r.hasUpdate && r.info != null) {
                                apkInfo = r.info
                            } else {
                                apkInfo = null
                                if (!r.success) apkError = r.message
                            }
                        }
                    }
                }
            ) { Text("立即检查") }
        },
        dismissButton = {
            TextButton(
                enabled = !checking && !apkBusy,
                onClick = onDismiss,
            ) { Text("关闭") }
        }
    )
}
