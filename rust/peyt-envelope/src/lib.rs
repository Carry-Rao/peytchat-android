//! PEYT 信封协议 — 发送端(写)构造。
//!
//! 与桌面端 `rv/src-tauri/src/envelope.rs` `build_envelope` 保持一致,
//! 使 Android 与桌面端产出的信封字节级同构。
//!
//! 结构(纯 JSON, 无前缀):
//!   {
//!     "type": "text",                 // 注册表判别符(聊天消息类型)
//!     "id": "<uuid>",                 // 发送端幂等键
//!     "payload": { "text": "你好" }   // 类型专属载荷; 所有 type 都有 text 字段
//!   }
//! 无 version / 无 from / 无 timestamp(时间取 core 的 msgs.timestamp)。

use serde_json::{json, Value};

/// 构建信封字符串: 纯 JSON。
///
/// 接收端(显示层)的解析在客户端各自实现(桌面端 envelope.ts, Android 端
/// PeytRepository.resolveEnvelopeText),本 crate 只负责发送端构造,保证两端
/// 发送出的信封完全一致。
pub fn build_envelope(type_: &str, payload: Value) -> Result<String, serde_json::Error> {
    let id = uuid::Uuid::new_v4().to_string();
    let env = json!({
        "type": type_,
        "id": id,
        "payload": payload,
    });
    serde_json::to_string(&env)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn envelope_structure() {
        let s = build_envelope("text", json!({"text": "你好"})).unwrap();
        // 纯 JSON, 无前缀
        assert!(s.starts_with('{'));
        let v: Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["type"], "text");
        assert_eq!(v["id"].as_str().unwrap().len(), 36); // uuid
        assert_eq!(v["payload"]["text"], "你好");
        // 无 version / from / timestamp 字段
        assert!(v.get("version").is_none());
        assert!(v.get("from").is_none());
        assert!(v.get("timestamp").is_none());
    }

    #[test]
    fn envelope_json_is_inline_no_prefix() {
        let s = build_envelope("text", json!({"text": "a"})).unwrap();
        assert!(s.starts_with('{'));
        let parsed: Value = serde_json::from_str(&s).unwrap();
        assert_eq!(parsed["type"], "text");
        assert_eq!(parsed["payload"]["text"], "a");
        assert_eq!(parsed["id"].as_str().unwrap().len(), 36);
    }
}
