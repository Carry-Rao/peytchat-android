package cn.yzjtiantian.android.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import cn.yzjtiantian.android.data.dto.ContactDto
import cn.yzjtiantian.android.data.repository.PeytRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Single-input dialog, mirroring the desktop `ui.inputDialog`. */
@Composable
fun InputDialog(
    title: String,
    placeholder: String,
    confirmLabel: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(placeholder) },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Contact list picker, mirroring the desktop `contactsPicker`. */
@Composable
fun ContactPickerDialog(
    contacts: List<ContactDto>,
    onPick: (ContactDto) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择联系人") },
        text = {
            if (contacts.isEmpty()) {
                Text(
                    text = "暂无联系人，可通过邮箱添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(contacts, key = { it.id }) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(c) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                text = c.displayName.ifBlank { c.name }.ifBlank { c.address },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (c.displayName.isNotBlank() || c.name.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = c.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** "选择联系人"：后台加载联系人后弹出选择列表。 */
@Composable
fun SelectContactDialog(
    repository: PeytRepository,
    onPick: (ContactDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var contacts by remember { mutableStateOf<List<ContactDto>?>(null) }
    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) {
            runCatching { repository.listContacts() }.getOrDefault(emptyList())
        }
    }
    val list = contacts
    if (list == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("选择联系人") },
            text = { Text("加载中…") },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            },
        )
        return
    }
    ContactPickerDialog(contacts = list, onPick = onPick, onDismiss = onDismiss)
}

/** "分享我的邀请链接"：展示 `peyt://invite/<b64>` 链接，支持复制。 */
@Composable
fun ShareInviteDialog(
    repository: PeytRepository,
    onDismiss: () -> Unit,
) {
    var link by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        link = withContext(Dispatchers.IO) {
            runCatching { repository.getInviteLink() }.getOrDefault("")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我的邀请链接") },
        text = {
            val text = link
            Text(
                text = text?.takeIf { it.isNotBlank() } ?: "正在获取…",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        val text = link?.takeIf { it.isNotBlank() }
                        if (text != null) {
                            clipboard.setText(AnnotatedString(text))
                            copied = true
                        }
                    },
                    enabled = !link.isNullOrBlank(),
                ) {
                    Text(if (copied) "已复制" else "复制")
                }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
    )
}
