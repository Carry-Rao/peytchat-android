package cn.yzjtiantian.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.data.dto.InboxEventDto
import cn.yzjtiantian.android.data.repository.PeytRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val EVENT_META = mapOf(
    "mention" to "提及",
    "reply" to "回复",
    "card_assign" to "卡片指派",
    "system" to "系统",
)

private val timeFormat = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())

/** Inbox: unified notification center. */
@Composable
fun InboxScreen(
    repository: PeytRepository,
    onOpenChannel: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var events by remember { mutableStateOf<List<InboxEventDto>>(emptyList()) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        withContext(Dispatchers.IO) {
            events = repository.listInboxEvents(100)
        }
    }

    LaunchedEffect(Unit) {
        load()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "通知",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (events.any { it.readAt == null }) "有未读通知" else "已全部读完",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching { repository.markAllInboxRead() }
                        load()
                    }
                },
            ) {
                Icon(Icons.Filled.Check, contentDescription = "全部已读")
            }
        }

        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无通知",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(events, key = { it.id }) { ev ->
                    val unread = ev.readAt == null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (unread) {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { repository.markInboxRead(ev.id) }
                                        load()
                                    }
                                }
                                onOpenChannel(ev.sourceChatId)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    color = if (unread) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.MailOutline,
                                contentDescription = null,
                                tint = if (unread) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = EVENT_META[ev.type] ?: ev.type,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (unread) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = formatTime(ev.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = ev.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Row(modifier = Modifier.padding(top = 2.dp)) {
                                Text(
                                    text = ev.actorName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = "来源 #$ev.sourceChatId",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (unread) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 8.dp, top = 8.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private fun formatTime(ts: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - ts
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60} 分钟前"
        diff < 86400 -> "${diff / 3600} 小时前"
        else -> timeFormat.format(Date(ts * 1000))
    }
}
