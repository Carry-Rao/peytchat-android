package cn.yzjtiantian.android.patch

import cn.yzjtiantian.android.core.TextSendHook

/**
 * 数据层发送钩子实现（示例）。
 *
 * 当前为**空操作**：`transform` 返回 null = 不拦截、按原文本发送。
 * 补丁仍然会被加载并注册（可在设置页「检查更新」看到「已加载补丁：data …」），
 * 但不再修改消息内容。需要真实改行为时，在这里实现 `transform` 即可。
 *
 * 注意：
 * - 类名（含包名 `cn.yzjtiantian.android.patch`）必须与基座中任何类都不重名；
 * - 只影响 `sendMessage`（用户文本消息），不影响信封协议消息。
 */
class DataTextHook : TextSendHook {

    override fun transform(text: String): String? = null
}
