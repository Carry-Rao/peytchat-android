package cn.yzjtiantian.android.patch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.yzjtiantian.android.data.dto.ChannelDto
import cn.yzjtiantian.android.data.repository.PeytRepository
import cn.yzjtiantian.android.patchapi.ChatUiProvider
import cn.yzjtiantian.android.ui.theme.iMessageBlue

/**
 * 补丁提供的聊天界面实现（示例）。
 *
 * 通过补丁 dex 下发：基座 `ShellScreen` 检测到 `chat` 模块注册了
 * [ChatUiProvider] 后，聊天区就渲染这里的界面，无需重新安装 App。
 *
 * 注意：
 * - 类名（含包名 `cn.yzjtiantian.android.patch`）必须与基座中任何类都不重名；
 * - 可自由引用 `PeytRepository`/`ChannelDto`/compose 等基座类
 *   （补丁类加载器的 parent 能看到基座全部类）；
 * - 本示例只做展示，正式补丁应实现真实的聊天交互。
 */
class ChatUiV2 : ChatUiProvider {

    @Composable
    override fun ChatContent(repository: PeytRepository, channel: ChannelDto) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "补丁版聊天界面 v2",
                    style = MaterialTheme.typography.headlineSmall,
                    color = iMessageBlue,
                )
                Text(
                    text = "频道：#${channel.name}（由 chat 补丁提供）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "这段 UI 编译在补丁 dex 里，无需重新安装 App 即可更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
