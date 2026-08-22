package cn.yzjtiantian.android.patchapi

import androidx.compose.runtime.Composable
import cn.yzjtiantian.android.data.dto.ChannelDto
import cn.yzjtiantian.android.data.repository.PeytRepository

/**
 * chat 模块 UI 扩展点。
 *
 * 基座 `ShellScreen` 渲染聊天区时，先查 `ModuleManager.getUiProvider("chat")`：
 * 若补丁注册了 [ChatUiProvider] 实现，就渲染补丁提供的界面；否则回退到内置
 * `ChatScreen`。
 *
 * 补丁侧约定（见 `patch/chat` 模块）：
 * - 补丁入口类 `cn.yzjtiantian.android.patch.ChatPatch` 的 `apply(Context)` 里调用
 *   `ModuleManager.registerUiProvider("chat", ChatUiV2())` 完成注册；
 * - 补丁实现类（如 `ChatUiV2`）实现本接口，类名必须是基座中不存在的新类；
 * - 补丁实现可自由引用 `PeytRepository`/`ChannelDto`/compose 等基座类
 *   （补丁类加载器的 parent 能看到基座全部类）。
 *
 * 注意：本接口属于「稳定契约」，一经发布不要随意改签名，
 * 否则旧版本基座加载新补丁会失败。
 */
interface ChatUiProvider {

    /** 渲染聊天界面。调用方处于 Composable 上下文。 */
    @Composable
    fun ChatContent(
        repository: PeytRepository,
        channel: ChannelDto,
    )
}
