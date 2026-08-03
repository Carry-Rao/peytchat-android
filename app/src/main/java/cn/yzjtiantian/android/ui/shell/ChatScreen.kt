package cn.yzjtiantian.android.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.data.dto.ChannelDto
import cn.yzjtiantian.android.data.dto.ChatMessageDto
import cn.yzjtiantian.android.data.repository.PeytRepository
import cn.yzjtiantian.android.ui.theme.iMessageBubbleSelf
import cn.yzjtiantian.android.ui.theme.iMessageBlue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Message list + composer for a channel. */
@Composable
fun ChatScreen(
    repository: PeytRepository,
    channel: ChannelDto,
) {
    var messages by remember { mutableStateOf<List<ChatMessageDto>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    suspend fun load() {
        withContext(Dispatchers.IO) {
            messages = repository.getChatMessages(channel.chatId)
        }
    }

    LaunchedEffect(channel.chatId) {
        load()
        listState.scrollToItem(0)
        // Poll for new messages (simple refresh loop).
        while (true) {
            delay(3000)
            load()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = true,
        ) {
            items(messages, key = { it.msgId }) { msg ->
                if (msg.isInfo) {
                    InfoLine(text = msg.text)
                } else {
                    MessageBubble(msg = msg)
                }
            }
        }
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发消息到 ${channel.name}") },
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            draft = ""
                            scope.launch(Dispatchers.IO) {
                                runCatching { repository.sendMessage(channel.chatId, text) }
                            }
                            scope.launch { load() }
                        }
                    },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = iMessageBlue,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessageDto) {
    val isOut = msg.isOut
    val bubbleColor = if (isOut) iMessageBubbleSelf else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOut) Color.White else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalAlignment = if (isOut) Alignment.End else Alignment.Start,
    ) {
        if (!isOut) {
            Text(
                text = msg.fromName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        Surface(
            color = bubbleColor,
            shape = if (isOut) {
                RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = 14.dp, bottomEnd = 4.dp,
                )
            } else {
                RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = 4.dp, bottomEnd = 14.dp,
                )
            },
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatTime(msg.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOut) Color.White.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

private fun formatTime(timestamp: Long): String {
    // Core may return seconds; detect and normalize to ms.
    val ms = if (timestamp < 1_000_000_000_000L) timestamp * 1000L else timestamp
    return timeFormat.format(Date(ms))
}
