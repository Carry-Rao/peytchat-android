package cn.yzjtiantian.android.ui.shell

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import cn.yzjtiantian.android.data.dto.ChannelDto
import cn.yzjtiantian.android.data.dto.ChatMessageDto
import cn.yzjtiantian.android.data.repository.PeytRepository
import cn.yzjtiantian.android.ui.theme.iMessageBubbleSelf
import cn.yzjtiantian.android.ui.theme.iMessageBlue
import coil.compose.AsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private val PURE_NUMBER = Regex("""\d+""")
private val JM_TAG = Regex("""(?i)<jm\s*=\s*['"]?\s*(\d+)\s*['"]?\s*>""")

/** 从 ` <jm='114514'> `(允许单/双引号、无引号、空格、大小写)提取漫画编号; 不是漫画格式 → null。 */
private fun parseJm(text: String): String? =
    JM_TAG.find(text)?.groupValues?.get(1)

/** Message list + composer for a channel. */
@Composable
fun ChatScreen(
    repository: PeytRepository,
    channel: ChannelDto,
) {
    var messages by remember { mutableStateOf<List<ChatMessageDto>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var fullscreenImage by remember { mutableStateOf<ChatMessageDto?>(null) }
    // 输入纯数字时, 在输入框上方询问是否作为漫画发送。
    var mangaPrompt by remember { mutableStateOf(false) }
    val draftNumber = draft.trim().takeIf { PURE_NUMBER.matches(it) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun load() {
        withContext(Dispatchers.IO) {
            messages = repository.getChatMessages(channel.chatId)
        }
    }

    fun sendText(text: String) {
        scope.launch(Dispatchers.IO) {
            try {
                repository.sendMessage(channel.chatId, text)
                android.util.Log.d("PEYT", "[send] ok chatId=${channel.chatId} text=$text")
            } catch (e: Exception) {
                android.util.Log.e("PEYT", "[send] FAILED chatId=${channel.chatId} text=$text", e)
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "发送失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        scope.launch { load() }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val (name, mime) = resolveContentMeta(context.contentResolver, uri)
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("read failed")
                repository.sendAttachment(channel.chatId, bytes, name, mime)
                android.util.Log.d("PEYT", "[send] attachment ok chatId=${channel.chatId} name=$name size=${bytes.size}")
            } catch (e: Exception) {
                android.util.Log.e("PEYT", "[send] attachment FAILED", e)
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "发送文件失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        scope.launch { load() }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = true,
        ) {
            items(messages.asReversed(), key = { it.msgId }) { msg ->
                if (msg.isInfo) {
                    InfoLine(text = msg.text)
                } else {
                    MessageBubble(msg = msg, onOpenImage = { fullscreenImage = it })
                }
            }
        }
        // 纯数字 → 发送前在输入框上方询问是否作为漫画。
        if (mangaPrompt && draftNumber != null) {
            MangaPromptBar(
                number = draftNumber,
                onSendManga = {
                    sendText("<jm='$draftNumber'>")
                    draft = ""
                    mangaPrompt = false
                },
                onSendPlain = {
                    sendText(draftNumber)
                    draft = ""
                    mangaPrompt = false
                },
                onDismiss = { mangaPrompt = false },
            )
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
                    onValueChange = {
                        draft = it
                        // 一旦不再是纯数字就收起漫画询问。
                        if (it.trim().let { n -> n.isNotEmpty() && !PURE_NUMBER.matches(n) }) {
                            mangaPrompt = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发消息到 ${channel.name}") },
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { filePicker.launch("*/*") }) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "发送文件",
                        tint = iMessageBlue,
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isEmpty()) return@IconButton
                        if (PURE_NUMBER.matches(text)) {
                            // 纯数字:先在上方询问是否为漫画, 用户确认后再按格式发送。
                            mangaPrompt = true
                        } else {
                            sendText(text)
                            draft = ""
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

    // 图片放大:全屏展示(重采样到更高分辨率)。
    fullscreenImage?.let { m ->
        val full = remember(m.filePath) {
            if (m.filePath != null) decodeImage(m.filePath, maxSize = 2048) else null
        }
        Dialog(onDismissRequest = { fullscreenImage = null }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { fullscreenImage = null },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (full != null) {
                    Image(
                        bitmap = full.asImageBitmap(),
                        contentDescription = m.fileName ?: "图片",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("图片加载失败", color = Color.White)
                }
            }
        }
    }
}

private val jmLinkBase = "https://18comic.vip/album/"

/** 发送前询问:数字作为漫画(18comic)链接发送, 还是普通消息。 */
@Composable
private fun MangaPromptBar(
    number: String,
    onSendManga: () -> Unit,
    onSendPlain: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "「$number」要作为漫画链接发送吗？",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSendManga) { Text("作为漫画") }
            TextButton(onClick = onSendPlain) { Text("普通发送") }
            IconButton(onClick = onDismiss, modifier = Modifier.width(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "取消",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessageDto, onOpenImage: (ChatMessageDto) -> Unit) {
    val isOut = msg.isOut
    val bubbleColor = if (isOut) iMessageBubbleSelf else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOut) Color.White else MaterialTheme.colorScheme.onSurface
    val jm = parseJm(msg.text)
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
            modifier = Modifier,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                when {
                    // 附件:渲染附件卡片, 不重复显示正文(文件名已在卡片展示)。
                    msg.viewType != "Text" -> AttachmentView(msg, onOpenImage)
                    // 漫画信封 <jm='114514'>:下划线链接 + 封面预览。
                    jm != null -> MangaView(jm, textColor)
                    else -> Text(
                        text = msg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatTime(msg.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOut) Color.White.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(if (isOut) Alignment.End else Alignment.Start),
                )
            }
        }
    }
}

@Composable
private fun MangaView(jm: String, textColor: Color) {
    val context = LocalContext.current
    Column {
        Text(
            text = jm,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$jmLinkBase$jm")))
            },
        )
        Spacer(Modifier.height(6.dp))
        AsyncImage(
            model = "https://cdn-msp3.18comic.vip/media/albums/$jm.jpg",
            contentDescription = "专辑封面 $jm",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(160.dp)
                .height(200.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun AttachmentView(
    msg: ChatMessageDto,
    onOpenImage: (ChatMessageDto) -> Unit,
) {
    when (msg.viewType) {
        "Image", "Gif" -> {
            val filePath = msg.filePath
            val bitmap = remember(filePath) { if (filePath != null) decodeImage(filePath) else null }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = msg.fileName ?: "图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenImage(msg) },
                )
            } else if (msg.filePath != null) {
                FileCardRow(msg)
            }
        }
        else -> if (msg.filePath != null) {
            FileCardRow(msg)
        }
    }
}

@Composable
private fun FileCardRow(msg: ChatMessageDto) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { openAttachment(context, msg) },
    ) {
        Icon(
            Icons.Filled.AttachFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = msg.fileName ?: "附件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = formatAttachmentBytes(msg.fileBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

private fun formatAttachmentBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
    return "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun openAttachment(context: Context, msg: ChatMessageDto) {
    val path = msg.filePath ?: run {
        android.widget.Toast.makeText(context, "文件不可用", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    val file = File(path)
    if (!file.exists()) {
        android.widget.Toast.makeText(context, "文件不存在: $path", android.widget.Toast.LENGTH_LONG).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, contentTypeByFilename(msg.fileName))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "打开 ${msg.fileName ?: "文件"}"))
    } catch (e: Exception) {
        android.util.Log.e("PEYT", "[open] failed", e)
        android.widget.Toast.makeText(context, "无法打开文件: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

private fun contentTypeByFilename(name: String?): String {
    val n = name?.lowercase(Locale.ROOT) ?: return "application/octet-stream"
    return when {
        n.endsWith(".txt") || n.endsWith(".log") -> "text/plain"
        n.endsWith(".pdf") -> "application/pdf"
        n.endsWith(".doc") -> "application/msword"
        n.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        n.endsWith(".xls") -> "application/vnd.ms-excel"
        n.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        n.endsWith(".ppt") -> "application/vnd.ms-powerpoint"
        n.endsWith(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        n.endsWith(".zip") || n.endsWith(".7z") || n.endsWith(".rar") -> "application/x-zip-compressed"
        n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac") -> "audio/*"
        n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") -> "video/*"
        n.endsWith(".json") -> "application/json"
        n.endsWith(".html") || n.endsWith(".htm") -> "text/html"
        else -> "application/octet-stream"
    }
}

private fun decodeImage(path: String, maxSize: Int = 1024): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) {
        null
    }
}

private fun resolveContentMeta(resolver: ContentResolver, uri: Uri): Pair<String, String> {
    var name = "file"
    resolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx)?.let { if (it.isNotBlank()) name = it }
        }
    }
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    return name to mime
}

private fun formatTime(timestamp: Long): String {
    // Core may return seconds; detect and normalize to ms.
    val ms = if (timestamp < 1_000_000_000_000L) timestamp * 1000L else timestamp
    return timeFormat.format(Date(ms))
}