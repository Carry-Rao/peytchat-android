package cn.yzjtiantian.android.patch

import cn.yzjtiantian.android.core.TextSendHook

/**
 * 数据层发送钩子实现：给每条发送的文本追加后缀。
 *
 * 注意：
 * - 类名（含包名 `cn.yzjtiantian.android.patch`）必须与基座中任何类都不重名；
 * - 可自由引用基座类（`TextSendHook`/`ModuleManager` 等），
 *   补丁类加载器的 parent 能看到基座全部类；
 * - 只影响 `sendMessage`（用户文本消息），不影响信封协议消息。
 */
class DataTextHook : TextSendHook {

    override fun transform(text: String): String? = "$text （数据层补丁 v0.0.1）"
}
