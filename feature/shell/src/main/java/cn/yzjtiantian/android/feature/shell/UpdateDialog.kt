package cn.yzjtiantian.android.ui.shell

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.core.HotUpdateManager

/**
 * 「检查更新」对话框：拉取更新清单 → 下载并校验补丁 → 记录（下次启动应用）。
 *
 * 更新清单地址默认取 [HotUpdateManager.DEFAULT_MANIFEST_URL]，可在此修改并持久化，
 * 方便接入自己的服务端。
 */
@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hotUpdate = remember(context) { HotUpdateManager(context.applicationContext) }

    var url by remember { mutableStateOf(hotUpdate.getManifestUrl()) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<HotUpdateManager.UpdateResult?>(null) }
    val installed = remember { hotUpdate.getInstalledVersions() }

    AlertDialog(
        onDismissRequest = {
            if (!checking) onDismiss()
        },
        title = { Text("检查更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 更新清单地址（可修改，方便对接自己的服务端）
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
                }
            ) { Text("立即检查") }
        },
        dismissButton = {
            TextButton(
                enabled = !checking,
                onClick = onDismiss,
            ) { Text("关闭") }
        }
    )
}
