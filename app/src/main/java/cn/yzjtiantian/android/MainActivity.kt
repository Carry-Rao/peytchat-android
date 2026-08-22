package cn.yzjtiantian.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import cn.yzjtiantian.android.core.AccountManager
import cn.yzjtiantian.android.core.CoreRuntime
import cn.yzjtiantian.android.core.MessageNotifications
import cn.yzjtiantian.android.core.NotificationGate
import cn.yzjtiantian.android.core.PeytBridge
import cn.yzjtiantian.android.core.PeytEventLoop
import cn.yzjtiantian.android.core.Rpc
import cn.yzjtiantian.android.data.AppDatabase
import cn.yzjtiantian.android.data.repository.PeytRepository
import cn.yzjtiantian.android.ui.login.LoginScreen
import cn.yzjtiantian.android.ui.shell.ShellScreen
import kotlinx.coroutines.withContext
import cn.yzjtiantian.android.ui.theme.DynamicPeytchatTheme
import cn.yzjtiantian.android.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {

    /** 最近收到的深链(`peytchat://...`)，登录后由 ShellScreen 消费。 */
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink = intent.dataString
        setContent {
            val themeMode by ThemeManager.themeMode.collectAsState()
            val navController = rememberNavController()

            DynamicPeytchatTheme(
                themeMode = themeMode
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot(
                        bridge = PeytBridge,
                        navController = navController,
                        deepLink = deepLink,
                        onDeepLinkConsumed = { deepLink = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = intent.dataString
    }
}

@Composable
fun AppRoot(
    bridge: PeytBridge,
    navController: NavController,
    deepLink: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    var loggedIn by remember { mutableStateOf<Boolean?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 维护「App 是否前台」标记：通知服务只在「前台且正在看该会话」时免打扰，
    // App 退后台后即使会话还开着也要照常弹通知（对齐 QQ/微信）。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> NotificationGate.appInForeground = true
                Lifecycle.Event.ON_STOP -> NotificationGate.appInForeground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Android 13+ 通知运行时权限（首次进入请求一次）。
    var notifPermissionHandled by remember { mutableStateOf(false) }
    var showNotifGuide by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifPermissionHandled = true
        if (granted) {
            // Android 13+ 已知坑：权限授予前创建的渠道会被系统置为关闭且不自动恢复，
            // 授权后重建渠道以恢复「新消息」通知。
            MessageNotifications.ensureChannels(context)
        } else if (Build.VERSION.SDK_INT >= 33) {
            Toast.makeText(
                context,
                "未授予通知权限，将无法收到新消息提醒（可在系统设置中开启）",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionHandled = true
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // 权限处理完成后若系统通知仍关闭（权限被拒 / 华为等系统对侧载应用默认关通知）→ 引导开启
    LaunchedEffect(notifPermissionHandled) {
        if (notifPermissionHandled && !MessageNotifications.areNotificationsEnabled(context)) {
            showNotifGuide = true
        }
    }

    // 一次性初始化：核心 + 事件循环（进程单例，消息接收前台服务复用同一循环）。
    LaunchedEffect(Unit) {
        val ready = withContext(Dispatchers.IO) {
            PeytEventLoop.ensureStarted(context) != null
        }
        if (!ready) {
            Toast.makeText(context, "核心初始化失败", Toast.LENGTH_LONG).show()
        }
        val hasAccount = withContext(Dispatchers.IO) {
            runCatching {
                val rpc = Rpc(bridge)
                AccountManager(rpc).getAllAccounts().any { it.configured }
            }.getOrDefault(false)
        }
        loggedIn = hasAccount
    }

    when (loggedIn) {
        null -> {}
        false -> LoginScreen(
            accountManager = remember(bridge) { AccountManager(Rpc(bridge)) },
            onLoggedIn = { loggedIn = true },
            onError = { msg ->
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
        )
        true -> {
            val rpc = remember(bridge) { Rpc(bridge) }
            val accountManager = remember(bridge) { AccountManager(rpc) }
            val repository = remember(bridge) {
                PeytRepository(
                    rpc = rpc,
                    db = AppDatabase.get(context),
                    tempDir = java.io.File(context.cacheDir, "media"),
                )
            }
            val scope = rememberCoroutineScope()

            // 引导账号 + 启动常驻消息服务 + 订阅事件（信封副作用）。
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        CoreRuntime.startConfiguredAccount()

                        // ✅ 常驻前台服务：App 退后台/被杀进程后仍能收消息弹通知
                        MessageNotificationService.start(context)

                        // 收到消息 → 解析 PEYT 信封:card.* 同步本地卡片, project.invite 自动加入频道。
                        // 副作用以 (from_id, envelope.id) 幂等去重(见 repository.handleIncomingEnvelope)。
                        PeytEventLoop.addListener { event ->
                            if (event.kind == "IncomingMsg") {
                                // deltachat-jsonrpc 的 IncomingMsg 事件字段是 camelCase（msgId）
                                val msgId = event.payload.optLong(
                                    "msgId",
                                    event.payload.optLong("msg_id", 0),
                                )
                                if (msgId > 0) {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching {
                                            val msg = repository.getChatMessage(msgId)
                                            repository.handleIncomingEnvelope(msgId, msg.fromId, msg.text)
                                        }.onFailure { e ->
                                            android.util.Log.w("PEYT", "[envelope] IncomingMsg $msgId handle failed", e)
                                        }
                                    }
                                }
                            }
                        }
                        android.util.Log.d("PEYT", "[startup] account io + message service + listeners ready")
                    }.onFailure { e ->
                        android.util.Log.w("PEYT", "[startup] init failed", e)
                    }
                }
            }

            // ✅ 传入 accountManager
            ShellScreen(
                repository = repository,
                accountManager = accountManager,  // ✅ 新增参数
                onLoggedOut = {
                    loggedIn = false
                    // 退出登录：停止常驻消息服务，清掉全部通知
                    MessageNotificationService.stop(context)
                    MessageNotifications.cancelAll(context)
                },
                deepLink = deepLink,
                onDeepLinkConsumed = onDeepLinkConsumed,
            )
        }
    }

    // 通知未开启引导（系统通知开关关闭/权限被拒时弹出）
    if (showNotifGuide) {
        AlertDialog(
            onDismissRequest = { showNotifGuide = false },
            title = { Text("开启消息通知") },
            text = {
                Text(
                    "当前系统通知未开启，将收不到新消息提醒。\n" +
                        "（部分系统如华为对侧载安装的应用默认关闭通知，需手动开启。）",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNotifGuide = false
                    MessageNotifications.openNotificationSettings(context)
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { showNotifGuide = false }) { Text("暂不") }
            },
        )
    }
}
