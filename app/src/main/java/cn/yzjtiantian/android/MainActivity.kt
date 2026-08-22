package cn.yzjtiantian.android

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import cn.yzjtiantian.android.core.AccountManager
import cn.yzjtiantian.android.core.EventBridge
import cn.yzjtiantian.android.core.PeytBridge
import cn.yzjtiantian.android.core.Rpc
import cn.yzjtiantian.android.core.Session
import cn.yzjtiantian.android.data.AppDatabase
import cn.yzjtiantian.android.data.repository.PeytRepository
import cn.yzjtiantian.android.ui.login.LoginScreen
import cn.yzjtiantian.android.ui.shell.ShellScreen
import kotlinx.coroutines.withContext
import cn.yzjtiantian.android.ui.theme.DynamicPeytchatTheme
import cn.yzjtiantian.android.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers


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
    val context = LocalContext.current
    var loggedIn by remember { mutableStateOf<Boolean?>(null) }

    // One-time init on the IO dispatcher.
    LaunchedEffect(Unit) {
        val ready = withContext(Dispatchers.IO) {
            runCatching {
                val dataDir = context.filesDir.absolutePath
                bridge.nativeInit(dataDir)
                bridge.nativePluginsInit(dataDir)
                true
            }.getOrDefault(false)
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
            val repository = remember(bridge) { PeytRepository(rpc, AppDatabase.get(context)) }

            // Pick the configured account and drive the event loop.
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val id = accountManager.getAllAccounts().firstOrNull { it.configured }?.id
                        if (id != null) {
                            Session.select(id)
                            Session.displayName =
                                accountManager.getConfig(id, "displayname") ?: ""
                            accountManager.disableForceEncryption(id)
                            accountManager.startIo(id)
                            android.util.Log.d("PEYT", "[startup] account=$id started IO, force_encryption disabled")
                            val bridgeEvents = EventBridge(rpc)
                            bridgeEvents.start()
                        } else {
                            android.util.Log.w("PEYT", "[startup] no configured account found")
                        }
                    }
                }
            }

            ShellScreen(
                repository = repository,
                onLoggedOut = {
                    loggedIn = false
                },
                deepLink = deepLink,
                onDeepLinkConsumed = onDeepLinkConsumed,
            )
        }
    }
}