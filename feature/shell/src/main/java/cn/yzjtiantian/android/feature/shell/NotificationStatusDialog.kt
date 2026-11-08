package cn.yzjtiantian.android.feature.shell

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.core.MessageNotifications
import cn.yzjtiantian.android.core.NotifyStatus
import cn.yzjtiantian.android.core.PeytBridge
import cn.yzjtiantian.android.core.PeytEventLoop
import cn.yzjtiantian.android.core.Rpc
import cn.yzjtiantian.android.core.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** 消息通知状态：一眼看清通知链路是否正常，异常时给出设置入口。 */
@Composable
fun NotificationStatusDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    fun fmt(t: Long): String = if (t > 0) timeFmt.format(Date(t)) else "—"

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) MessageNotifications.ensureChannels(context)
        testResult = if (granted) "✔ 通知权限已授予" else "✘ 通知权限被拒绝"
        refreshKey++
    }

    val permissionGranted = MessageNotifications.areNotificationsPermissionGranted(context)
    val enabled = MessageNotifications.areNotificationsEnabled(context)
    val channelBlocked = MessageNotifications.isMessagesChannelBlocked(context)
    val eventLoopRunning = PeytEventLoop.isRunning()

    // core 连接状态：1000 未连接、2000 连接中、3000 收发中、4000 已连接
    var connectivity by remember { mutableStateOf("读取中…") }
    LaunchedEffect(refreshKey) {
        connectivity = withContext(Dispatchers.IO) {
            runCatching {
                val accountId = Session.currentAccountId
                if (accountId <= 0) {
                    "未登录"
                } else {
                    val level = (Rpc(PeytBridge).callRaw(
                        "get_connectivity",
                        JSONArray().put(accountId),
                    ) as? Number)?.toInt() ?: -1
                    when {
                        level >= 4000 -> "已连接"
                        level >= 3000 -> "收发中"
                        level >= 2000 -> "连接中"
                        else -> "未连接"
                    }
                }
            }.getOrDefault("读取失败")
        }
    }
    val connected = connectivity == "已连接" || connectivity == "收发中"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("消息通知") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusRow("前台服务", NotifyStatus.serviceRunning)
                StatusRow("事件循环", eventLoopRunning)
                StatusRow("core 连接", connected, connectivity)
                if (Build.VERSION.SDK_INT >= 33) {
                    StatusRow("通知权限", permissionGranted)
                }
                StatusRow("应用通知", enabled)
                StatusRow("新消息渠道", !channelBlocked)

                Text(
                    text = "最近消息 ${fmt(NotifyStatus.lastIncomingAt)}    " +
                        "最近通知 ${fmt(NotifyStatus.lastNotifiedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "最近处理：${NotifyStatus.lastIncomingResult}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!eventLoopRunning || !enabled || channelBlocked) {
                    Text(
                        text = "有异常项，请到系统设置中允许通知：设置 → 应用管理 → PEYT Chat → 通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                testResult?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = {
                        val ok = MessageNotifications.postTestNotification(context)
                        testResult = if (ok) "✔ 测试通知已发送" else "✘ 发送失败，请先开启应用通知"
                        refreshKey++
                    }) { Text("发送测试通知") }
                    if (Build.VERSION.SDK_INT >= 33 && !permissionGranted) {
                        TextButton(onClick = {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }) { Text("请求权限") }
                    }
                    if (!enabled) {
                        TextButton(onClick = {
                            if (!MessageNotifications.openNotificationSettings(context)) {
                                Toast.makeText(context, "未能跳转设置页", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("去开启", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { refreshKey++ }) { Text("刷新") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun StatusRow(label: String, ok: Boolean, value: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value ?: if (ok) "正常" else "异常",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
