package cn.yzjtiantian.android.patch

import android.content.Context
import cn.yzjtiantian.android.core.MessageNotification
import cn.yzjtiantian.android.core.MessageNotificationHook

/**
 * 消息通知定制钩子实现（示例）。
 *
 * 基座在收到新消息、组装默认通知内容后调用 `customize`：
 * - 返回修改后的 [MessageNotification] → 按定制内容弹通知；
 * - 返回 null → **静默**该条消息（不弹通知）。
 *
 * 当前示例：给通知正文加前缀「📣」，用于验证热更新全链路
 * （下载→校验→加载→apply→注册→服务发通知前调用）。
 * 需要真实改行为时，在这里定制标题/正文/优先级/渠道即可。
 *
 * 注意：
 * - 类名（含包名 `cn.yzjtiantian.android.patch`）必须与基座中任何类都不重名；
 * - 补丁类可引用基座类（parent-first 加载，基座全部类可见）。
 */
class NotificationTextHook : MessageNotificationHook {

    override fun customize(context: Context, default: MessageNotification): MessageNotification? {
        return default.copy(text = "📣 " + default.text)
    }
}
