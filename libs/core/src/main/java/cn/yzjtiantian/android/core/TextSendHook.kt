package cn.yzjtiantian.android.core

/**
 * 数据层可热更新的行为钩子：发送文本消息前调用。
 *
 * 补丁（如 `patch/data` 的入口类 `DataPatch`）在 `apply(Context)` 里通过
 * `ModuleManager.registerPatchService(TextSendHook.SERVICE_KEY, hook)` 注册；
 * 基座 `PeytRepository.sendMessage` 发送文本前会查询该钩子：
 *
 * - 未注册钩子 → 按原文本发送；
 * - 已注册钩子 → 用 `transform(text)` 的返回值发送（返回 null 视为不拦截）。
 *
 * 注意：
 * - 只作用于**用户文本消息**（`sendMessage`），不会影响信封（card.* / project.invite）
 *   等内部消息，避免破坏协议格式；
 * - 本接口是稳定契约，发布后不要改签名；
 * - 补丁实现类名必须是基座中不存在的新类（parent-first 类加载）。
 */
fun interface TextSendHook {

    companion object {
        /** 在 [ModuleManager] 中的注册键。 */
        const val SERVICE_KEY = "text_send_hook"
    }

    /** 返回 null 表示不拦截；返回字符串表示用该文本发送。 */
    fun transform(text: String): String?
}
