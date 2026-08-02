package cn.yzjtiantian.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.core.AccountManager
import cn.yzjtiantian.android.core.EventBridge
import cn.yzjtiantian.android.core.PeytBridge
import cn.yzjtiantian.android.core.Rpc
import cn.yzjtiantian.android.ui.theme.PeytchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bridge = PeytBridge
        setContent {
            PeytchatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BridgeStatus(bridge = bridge)
                }
            }
        }
    }
}

@Composable
fun BridgeStatus(bridge: PeytBridge) {
    var status by remember { mutableStateOf("initializing...") }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) {
            try {
                val dataDir = context.filesDir.absolutePath
                val init = bridge.nativeInit(dataDir)
                val pluginsInit = bridge.nativePluginsInit(dataDir)
                val rpc = Rpc(bridge)
                val info = rpc.call("get_system_info")
                val coreVersion = info.optString("deltachat_core_version", "?")

                val accounts = AccountManager(rpc)
                val before = accounts.getAllAccounts().size

                val events = EventBridge(rpc)
                var eventCount = 0
                events.addListener { eventCount++ }
                events.start()

                val newId = accounts.addAccount()
                val after = accounts.getAllAccounts().size
                accounts.removeAccount(newId)
                events.stop()

                "init=$init plugins=$pluginsInit\n" +
                    "core: $coreVersion\n" +
                    "accounts: $before -> $after (test id=$newId)\n" +
                    "events fired: $eventCount"
            } catch (e: Throwable) {
                android.util.Log.e("PeytTest", "smoke test failed", e)
                "bridge error: ${e}"
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "PEYT Chat")
        Text(text = status)
    }
}
