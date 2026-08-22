package cn.yzjtiantian.android.feature.shell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.core.MessageNotifications
import cn.yzjtiantian.android.core.NotifyLog
import cn.yzjtiantian.android.core.NotifyStatus
import cn.yzjtiantian.android.core.PeytBridge
import cn.yzjtiantian.android.core.PeytEventLoop
import cn.yzjtiantian.android.core.Rpc
import cn.yzjtiantian.android.core.Session
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「消息通知」诊断对话框：一眼看出通知链路卡在哪一步。
 *
 * 三层开关状态（由外到内）：
 * 1. 通知权限（Android 13+ POST_NOTIFICATIONS）——可在此重新请求；
 * 2. 应用级通知开关（华为对侧载应用默认关闭，且 App 代码无法绕过）——「去开启」；
 * 3. 「新消息」渠道开关——「发送测试通知」自动删除重建。
 *
 * 若第 2/3 层被系统关闭且修复无效，说明是系统级设置，需按华为手动路径开启：
 * 设置 → 应用和服务 → 应用管理 → PEYT Chat → 通知 → 允许通知（新消息/全部开启）。
 */
@Composable
fun NotificationStatusDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // refreshKey 递增触发状态/日志刷新
    var refreshKey by remember { mutableIntStateOf(0) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    fun fmt(t: Long): String = if (t > 0) timeFmt.format(Date(t)) else "—"

    // 权限被拒时可在此重新请求；授权后重建渠道
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            MessageNotifications.ensureChannels(context)
            testResult = "✔ 通知权限已授予，渠道已重建，请再点「发送测试通知」验证"
        } else {
            testResult = "✘ 通知权限仍被拒绝"
        }
        refreshKey++
    }

    // core 连接状态（get_connectivity：1000 未连接/IO未运行、2000 连接中、3000 收发中、4000 已连接）
    var connectivity by remember { mutableStateOf("（未读取）") }
    fun readConnectivity() {
        connectivity = runCatching {
            val accountId = Session.currentAccountId
            if (accountId <= 0) {
                "无账号（未登录）"
            } else {
                val level = (Rpc(PeytBridge).callRaw(
                    "get_connectivity",
                    JSONArray().put(accountId),
                ) as? Number)?.toInt() ?: -1
                when {
                    level >= 4000 -> "已连接（$level）—— 服务端可达，应能收消息"
                    level >= 3000 -> "收发消息中（$level）"
                    level >= 2000 -> "连接中（$level）"
                    level >= 1000 -> "未连接 / IO 未运行（$level）—— 后台收不到消息的根因很可能是这个"
                    else -> "未知（$level）"
                }
            }
        }.getOrDefault("读取失败")
        refreshKey++
    }

    // 日志随 refreshKey 重读；状态字段为 @Volatile，重组时即读到最新值
    val logTail = remember(refreshKey) { NotifyLog.readTail(context) }

    val permissionGranted = MessageNotifications.areNotificationsPermissionGranted(context)
    val enabled = MessageNotifications.areNotificationsEnabled(context)
    val channelBlocked = MessageNotifications.isMessagesChannelBlocked(context)
    val needSettings = !enabled || channelBlocked

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("消息通知诊断") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatusRow("前台服务运行", NotifyStatus.serviceRunning)
                StatusRow("事件循环线程存活", PeytEventLoop.isRunning())
                if (!PeytEventLoop.isRunning()) {
                    Text(
                        text = "⚠ 事件循环线程未存活：core 事件没人消费，任何消息都不会触发通知。请重新打开 App 或重启设备后再试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "收到事件总数: ${NotifyStatus.eventCount}\n" +
                        "最近收到任意事件: ${fmt(NotifyStatus.lastAnyEventAt)}\n" +
                        "最近收到消息: ${fmt(NotifyStatus.lastIncomingAt)}\n" +
                        "最近弹通知: ${fmt(NotifyStatus.lastNotifiedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "最后一条消息处理结果：",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                val resultText = NotifyStatus.lastIncomingResult
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        resultText.startsWith("已弹通知") -> MaterialTheme.colorScheme.primary
                        resultText == "—" || resultText.startsWith("跳过") ||
                            resultText.startsWith("被热更新补丁静默") ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
                if (resultText == "—") {
                    Text(
                        text = "（还没有收到过新消息：让另一个账号往群里发一条，然后回来点「刷新」）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "core 连接状态（后台收消息的关键）：",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = connectivity,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connectivity.contains("已连接") || connectivity.contains("收发")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
                Row {
                    TextButton(onClick = { readConnectivity() }) { Text("读取连接状态") }
                }
                Text(
                    text = "自测方法：在 App 前台随便操作（发消息/切换页面）后点「刷新」——" +
                        "「收到事件总数」应持续增加。若前台都不增加 → 事件循环没在跑；" +
                        "若前台增加、后台不增加 → 后台收消息被系统限制。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Build.VERSION.SDK_INT >= 33) {
                    StatusRow("通知权限(Android 13+)", permissionGranted)
                }
                StatusRow("应用级通知开关", enabled)
                if (!enabled) {
                    Text(
                        text = "⚠ 应用级通知被系统关闭：收不到任何消息通知（前台服务常驻通知不受影响，所以仍能看到「正在接收消息」）。App 无法自行绕过，必须到系统设置开启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                StatusRow("「新消息」渠道被关", !channelBlocked)
                if (channelBlocked) {
                    Text(
                        text = "⚠ 「新消息」渠道被系统关闭。若「发送测试通知」重建后仍异常，说明是系统级开关（应用通知/该类别被关），需手动开启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "最近收到消息: ${fmt(NotifyStatus.lastIncomingAt)}\n" +
                        "最近弹通知: ${fmt(NotifyStatus.lastNotifiedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                testResult?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (needSettings) {
                    Text(
                        text = "华为/荣耀手动路径：设置 → 应用和服务 → 应用管理 → PEYT Chat → 通知 → 打开「允许通知」，并确认「新消息」类别为开启（建议全部打开）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = "最近日志：",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = logTail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )

                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = {
                        val ok = MessageNotifications.postTestNotification(context)
                        testResult = if (ok) {
                            "✔ 测试通知已发送（看下拉通知栏；若渠道被关已自动重建，请刷新确认状态）"
                        } else {
                            "✘ 发送失败：应用级通知被系统关闭，请「去开启」或按上方手动路径开启"
                        }
                        refreshKey++
                    }) { Text("发送测试通知") }
                    if (Build.VERSION.SDK_INT >= 33 && !permissionGranted) {
                        TextButton(onClick = {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }) { Text("重新请求权限") }
                    }
                    if (!enabled) {
                        TextButton(onClick = {
                            val ok = MessageNotifications.openNotificationSettings(context)
                            if (!ok) {
                                Toast.makeText(
                                    context,
                                    "未能跳转设置页，请按上方手动路径开启通知",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }) { Text("去开启", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = {
                        NotifyLog.clear(context)
                        refreshKey++
                    }) { Text("清除日志") }
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
private fun StatusRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = if (ok) "正常" else "异常",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
