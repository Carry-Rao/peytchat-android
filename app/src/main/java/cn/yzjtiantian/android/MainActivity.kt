package cn.yzjtiantian.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import cn.yzjtiantian.android.core.Session
import cn.yzjtiantian.android.data.AppDatabase
import cn.yzjtiantian.android.data.repository.PeytRepository
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
                val events = EventBridge(rpc)
                var eventCount = 0
                events.addListener { eventCount++ }
                events.start()

                val out = StringBuilder()
                out.append("init=$init plugins=$pluginsInit\ncore: $coreVersion\n")

                // Use an existing configured account or create a fresh one.
                var accountId = accounts.getAllAccounts()
                    .firstOrNull { it.configured }?.id ?: 0
                if (accountId == 0L) {
                    // Try chatmail quick-configuration; fall back to plain add_account.
                    val chatmail = runCatching {
                        accounts.createChatmailAccount("smoke-test")
                    }
                    accountId = chatmail.getOrElse {
                        accounts.addAccount()
                    }
                    out.append("chatmail: ${chatmail.exceptionOrNull()?.message ?: "ok"}\n")
                }
                Session.select(accountId)
                rpc.callRaw("start_io", org.json.JSONArray().put(accountId))
                // Poll until configured or 60s elapse.
                repeat(60) {
                    if (accounts.getAccountInfo(accountId).configured) return@repeat
                    Thread.sleep(1000)
                }
                val configuredNow = accounts.getAccountInfo(accountId).configured

                val db = AppDatabase.get(context)
                val repo = PeytRepository(rpc, db)

                out.append("account: $accountId configured=$configuredNow\n")

                // 1. Room CRUD round-trip
                val wsDao = db.workspaceDao()
                val wsRow = wsDao.insert(
                    cn.yzjtiantian.android.data.entity.WorkspaceEntity(
                        name = "smoke-ws", masterChatId = 999, icon = "S", createdAt = 0
                    )
                )
                db.channelDao().insert(
                    cn.yzjtiantian.android.data.entity.ChannelEntity(
                        workspaceId = wsRow, chatId = 888, name = "general",
                        category = "General", position = 0, spaceType = "card"
                    )
                )
                val st = db.channelDao().getSpaceType(888)
                db.workspaceDao().delete(wsRow)
                out.append("room: ws=$wsRow spaceType=$st ok\n")

                // 2. PEYT Studio business flow
                runCatching { repo.ensurePeytStudio() }.onSuccess { p ->
                    out.append("studio: role=${p.role} ws=${p.workspace.id} invite=${p.inviteQr != null}\n")
                }.onFailure { out.append("studio ERR: ${it}\n") }

                // 3. create workspace -> channel -> card
                runCatching {
                    val ws = repo.createWorkspace("测试空间")
                    val ch = repo.createChannel(ws.id, "看板", "General")
                    val card = repo.createCard(
                        ws.id, ch.chatId, "task", "写测试用例",
                        "覆盖 Room 与 RPC", null, null
                    )
                    val cards = repo.listCards(ws.id, ch.chatId)
                    val pins = repo.togglePin(ws.id, ch.chatId, card.msgId ?: 0)
                    out.append("ws: ${ws.name}(${ws.id}) ch: ${ch.name}(${ch.chatId})\n")
                    out.append("card: ${card.title} id=${card.id} status=${card.status} pins=$pins\n")
                    out.append("cards listed: ${cards.size}\n")
                    out.append("activities: ${repo.listActivities(ch.chatId).size}\n")
                }.onFailure { out.append("biz ERR: ${it}\n") }

                events.stop()
                "events fired: $eventCount\n" + out.toString()
            } catch (e: Throwable) {
                Log.e("PeytTest", "smoke test failed", e)
                "bridge error: ${e}"
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = "PEYT Chat")
        Text(text = status)
    }
}
