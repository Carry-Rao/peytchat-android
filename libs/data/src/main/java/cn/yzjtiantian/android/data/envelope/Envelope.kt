package cn.yzjtiantian.android.data.envelope

import org.json.JSONObject

/**
 * PEYT 纯 JSON 信封协议解析(镜像桌面端 `rv/src/utils/envelope.ts`)。
 *
 * 信封是 view_type=Text 的普通消息, 正文就是纯 JSON、无前缀:
 *   { "type": <string>, "id": <uuid>, "payload": { "text": <string>, ... } }
 * 约定: 所有 type 的 payload 都带 text 字段填充消息体正文;
 * 未知 type / 结构不合法 → 调用方显示原文兜底。
 *
 * 协议定义见 `rv/src-tauri/src/envelope.rs` 与
 * `rv/docs/superpowers/specs/2026-08-04-pure-json-envelope-design.md`。
 */
data class Envelope(
    val type: String,
    val id: String,
    val payload: JSONObject,
)

/** 尝试解析信封。非信封 / 结构不合法 → null(调用方显示原文)。 */
fun tryParseEnvelope(text: String): Envelope? {
    if (text.isEmpty() || text[0] != '{') return null
    val obj = try {
        JSONObject(text)
    } catch (_: Exception) {
        return null
    }
    val type = obj.optString("type").takeIf { it.isNotEmpty() } ?: return null
    val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: return null
    val payload = obj.optJSONObject("payload") ?: return null
    return Envelope(type = type, id = id, payload = payload)
}

/** 取信封正文文本(payload.text)。缺失或非字符串 → null。 */
fun envelopeText(env: Envelope): String? {
    val t = env.payload.opt("text")
    return t as? String
}

/** 取信封 md 标记:payload.markdown === true 才 true(布尔严格校验)。 */
fun envelopeMarkdown(env: Envelope): Boolean =
    (env.payload.opt("markdown") as? Boolean) == true

/** 取信封携带的「消息主题」(payload.theme),无 theme 或结构不合法 → null。 */
fun envelopeTheme(env: Envelope): JSONObject? {
    return runCatching { env.payload.getJSONObject("theme") }.getOrNull()
}

/** 还原消息正文: 是信封 → payload.text; 否则 → 原文。 */
fun resolveMessageText(text: String): String {
    val env = tryParseEnvelope(text) ?: return text
    return envelopeText(env) ?: text
}

/**
 * 业务信封(card.* / project.invite)的可读摘要。
 * 这些信封的 payload 无 text 字段, 直接显示 JSON 会污染聊天流;
 * 接收端据此把消息体渲染成一行可读摘要。
 * 非业务信封 / 结构不合法 → null(走普通文本渲染)。
 */
fun resolveEnvelopeSummary(text: String): String? {
    val env = tryParseEnvelope(text) ?: return null
    return when (env.type) {
        "card.create" -> "新建卡片: ${env.payload.optString("title", "")}"
        "card.update" -> "更新卡片: ${env.payload.optString("title", "")}"
        "card.delete" -> "删除卡片: ${env.payload.optString("title", "")}"
        "project.invite" -> "邀请加入 PEYT Studio 频道"
        else -> null
    }
}

/** 判断是否为卡片业务信封(create/update/delete), 接收端据此做本地同步。 */
fun isCardEnvelope(env: Envelope): Boolean =
    env.type == "card.create" || env.type == "card.update" || env.type == "card.delete"

/** 卡片信封 → 本地 upsert 动作(对齐 rv 旧 [CARD] payload 的 action 语义)。 */
fun cardEnvelopeAction(env: Envelope): String =
    when (env.type) {
        "card.delete" -> "delete"
        "card.update" -> "update"
        else -> "create"
    }