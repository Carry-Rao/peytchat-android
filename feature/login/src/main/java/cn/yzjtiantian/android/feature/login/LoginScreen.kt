package cn.yzjtiantian.android.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.core.AccountManager
import cn.yzjtiantian.android.ui.theme.BrandOrange
import cn.yzjtiantian.android.ui.theme.BrandViolet
import cn.yzjtiantian.android.ui.theme.OnBrandDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Login page: chatmail quick-start (editable server) or classic email+password. */
@Composable
fun LoginScreen(
    accountManager: AccountManager,
    onLoggedIn: () -> Unit,
    onError: (String) -> Unit,
) {
    var mode by remember { mutableStateOf("chatmail") }
    var server by remember { mutableStateOf(AccountManager.DEFAULT_CHATMAIL_SERVER) }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun login() {
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (mode == "chatmail") {
                        accountManager.createChatmailAccount(
                            displayName = displayName.ifBlank { "PEYT 用户" },
                            server = server,
                        )
                    } else {
                        accountManager.login(email.trim(), password)
                    }
                    onLoggedIn()
                } catch (e: Exception) {
                    onError(e.message ?: "登录失败")
                }
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandHeader()
        Spacer(Modifier.height(36.dp))

        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                ModeToggle(mode = mode, onModeChange = { mode = it })
                Spacer(Modifier.height(20.dp))

                if (mode == "chatmail") {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("Chatmail 服务器") },
                        supportingText = { Text("输入域名或完整地址，自动注册新邮箱") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("显示名称（可选）") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { login() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("邮箱地址") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { login() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { login() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    enabled = !busy,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp)) else Text("登录")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = if (mode == "chatmail") {
                "无需邮箱密码，Chatmail 自动为你注册一个 PEYT 地址"
            } else {
                "使用已有的邮箱服务账号连接 PEYT 网络"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BrandHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(BrandViolet, BrandOrange))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "P",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = OnBrandDark,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "PEYT Chat",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "把想法敲进同一个频道",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeToggle(mode: String, onModeChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("chatmail" to "快速开始", "email" to "邮箱登录").forEach { (key, label) ->
            val selected = mode == key
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onModeChange(key) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

