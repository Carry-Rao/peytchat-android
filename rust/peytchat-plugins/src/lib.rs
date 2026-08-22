use std::fs;
use std::io::Read;
use std::path::PathBuf;

use base64::Engine;
use serde::{Deserialize, Serialize};
use thiserror::Error;

/// Errors produced by the plugin manager.
#[derive(Debug, Error)]
pub enum PluginError {
    #[error("{0}")]
    Plugin(String),
    #[error("io: {0}")]
    Io(String),
    #[error("http: {0}")]
    Http(String),
    #[error("json: {0}")]
    Json(String),
    #[error("zip: {0}")]
    Zip(String),
}

pub type Result<T> = std::result::Result<T, PluginError>;

/// A plugin entry from the GitHub registry.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RegistryPlugin {
    pub name: String,
    pub version: String,
    pub title: String,
    pub description: String,
    pub author: String,
    #[serde(rename = "type")]
    pub plugin_type: String, // "theme" | "chatbot" | "llm" | "general"
    pub entry: String,       // e.g. "plugin.js"
}

/// Top-level registry JSON fetched from the unified GitHub repo.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Registry {
    pub repo: String,
    pub plugins: Vec<RegistryPlugin>,
}

/// Status of an installed plugin, mirrored to the client.
#[derive(Debug, Clone, Serialize)]
pub struct PluginStatus {
    pub name: String,
    pub title: String,
    pub description: String,
    pub plugin_type: String,
    pub version: String,
    pub author: String,
    pub enabled: bool,
}

/// Manages plugin install / list / toggle / uninstall on disk.
///
/// Ported from the desktop backend (plugins.rs) to be fully synchronous so it
/// can be exposed directly through JNI without a tokio runtime.
pub struct PluginManager {
    base_dir: PathBuf,
    registry_url: String,
    raw_base: String,
}

impl PluginManager {
    pub fn new(app_data_dir: PathBuf) -> Self {
        Self {
            base_dir: app_data_dir.join("plugins"),
            registry_url:
                "https://ieshishinjin.github.io/PleaseEnterYourTextCommunityPluginsMarket/registry.json"
                    .into(),
            raw_base:
                "https://ieshishinjin.github.io/PleaseEnterYourTextCommunityPluginsMarket/plugins"
                    .into(),
        }
    }

    /// Fetch the plugin registry from GitHub and cache it locally.
    pub fn fetch_registry(&self) -> Result<Vec<RegistryPlugin>> {
        let resp = reqwest::blocking::get(&self.registry_url)
            .map_err(|e| PluginError::Http(format!("获取插件列表失败: {e}")))?;
        let registry: Registry = resp
            .json()
            .map_err(|e| PluginError::Json(format!("解析插件列表失败: {e}")))?;
        if let Ok(json) = serde_json::to_string(&registry) {
            let _ = fs::create_dir_all(&self.base_dir);
            let _ = fs::write(self.base_dir.join("registry_cache.json"), &json);
        }
        Ok(registry.plugins)
    }

    /// Install a plugin by name from the registry.
    /// Plugin files live at plugins/<author>/<name>/<entry>.
    pub fn install_plugin(&self, name: &str) -> Result<RegistryPlugin> {
        let author = self.find_author(name)?;
        let manifest_url = format!("{}/{}/{}/plugin.json", self.raw_base, author, name);
        let resp = reqwest::blocking::get(&manifest_url)
            .map_err(|e| PluginError::Http(format!("无法获取插件 {name}: {e}")))?;
        if !resp.status().is_success() {
            return Err(PluginError::Plugin(format!("插件 {name} 不存在于仓库中")));
        }
        let plugin: RegistryPlugin = resp
            .json()
            .map_err(|e| PluginError::Json(format!("解析插件 {name} 清单失败: {e}")))?;

        let dir = self.base_dir.join(name);
        fs::create_dir_all(&dir).map_err(|e| PluginError::Io(e.to_string()))?;

        let entry_url = format!("{}/{}/{}/{}", self.raw_base, author, name, plugin.entry);
        let js_resp = reqwest::blocking::get(&entry_url)
            .map_err(|e| PluginError::Http(format!("无法下载插件脚本 {name}: {e}")))?;
        let js_bytes = js_resp
            .bytes()
            .map_err(|e| PluginError::Http(format!("读取插件脚本 {name} 失败: {e}")))?;

        let manifest_json = serde_json::to_string_pretty(&plugin)
            .map_err(|e| PluginError::Json(format!("序列化清单失败: {e}")))?;
        fs::write(dir.join("plugin.json"), manifest_json)
            .map_err(|e| PluginError::Io(e.to_string()))?;
        fs::write(dir.join(&plugin.entry), &js_bytes).map_err(|e| PluginError::Io(e.to_string()))?;

        // Default: enabled
        fs::write(dir.join("enabled"), b"1").map_err(|e| PluginError::Io(e.to_string()))?;
        Ok(plugin)
    }

    /// Resolve a plugin's author (GitHub username / namespace dir) from the
    /// cached registry, falling back to a fresh fetch.
    fn find_author(&self, name: &str) -> Result<String> {
        let cached = self.base_dir.join("registry_cache.json");
        if let Ok(content) = fs::read_to_string(&cached) {
            if let Ok(reg) = serde_json::from_str::<Registry>(&content) {
                if let Some(p) = reg.plugins.iter().find(|p| p.name == name) {
                    return Ok(p.author.clone());
                }
            }
        }
        let plugins = self.fetch_registry()?;
        plugins
            .iter()
            .find(|p| p.name == name)
            .map(|p| p.author.clone())
            .ok_or_else(|| PluginError::Plugin(format!("插件 {name} 不存在于仓库中")))
    }

    /// Install a plugin from a base64-encoded ZIP file picked locally.
    pub fn install_plugin_from_zip(&self, data_base64: &str) -> Result<RegistryPlugin> {
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(data_base64)
            .map_err(|e| PluginError::Plugin(format!("Base64 解码失败: {e}")))?;

        let reader = std::io::Cursor::new(bytes);
        let mut archive =
            zip::ZipArchive::new(reader).map_err(|e| PluginError::Zip(e.to_string()))?;

        // Find plugin.json to determine the plugin name.
        let mut manifest_content = None;
        for i in 0..archive.len() {
            let mut file = archive
                .by_index(i)
                .map_err(|e| PluginError::Zip(e.to_string()))?;
            let name = file.name().to_string();
            if name.ends_with("plugin.json") {
                let mut content = String::new();
                file.read_to_string(&mut content)
                    .map_err(|e| PluginError::Io(e.to_string()))?;
                manifest_content = Some(content);
                break;
            }
        }
        let manifest_str = manifest_content
            .ok_or_else(|| PluginError::Plugin("ZIP 中缺少 plugin.json".into()))?;
        let plugin: RegistryPlugin = serde_json::from_str(&manifest_str)
            .map_err(|e| PluginError::Json(format!("解析 plugin.json 失败: {e}")))?;

        let dst = self.base_dir.join(&plugin.name);
        if dst.exists() {
            fs::remove_dir_all(&dst).map_err(|e| PluginError::Io(e.to_string()))?;
        }

        // Extract all files, stripping an optional top-level folder.
        for i in 0..archive.len() {
            let mut file = archive
                .by_index(i)
                .map_err(|e| PluginError::Zip(e.to_string()))?;
            if file.is_dir() {
                continue;
            }
            let name = file.name().to_string();
            let rel_path = name.split('/').skip(1).collect::<Vec<_>>().join("/");
            if rel_path.is_empty() {
                continue;
            }
            let target = dst.join(&rel_path);
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent).map_err(|e| PluginError::Io(e.to_string()))?;
            }
            let mut content = Vec::new();
            file.read_to_end(&mut content)
                .map_err(|e| PluginError::Io(e.to_string()))?;
            fs::write(&target, &content).map_err(|e| PluginError::Io(e.to_string()))?;
        }

        fs::write(dst.join("enabled"), b"1").map_err(|e| PluginError::Io(e.to_string()))?;
        Ok(plugin)
    }

    /// Remove a plugin directory.
    pub fn uninstall_plugin(&self, name: &str) -> Result<()> {
        let dir = self.base_dir.join(name);
        if !dir.exists() {
            return Err(PluginError::Plugin(format!("插件 {name} 未安装")));
        }
        fs::remove_dir_all(&dir).map_err(|e| PluginError::Io(e.to_string()))?;
        Ok(())
    }

    /// List all installed plugins with their enabled status.
    pub fn list_plugins(&self) -> Result<Vec<PluginStatus>> {
        if !self.base_dir.exists() {
            return Ok(vec![]);
        }
        let mut plugins = vec![];
        for entry in fs::read_dir(&self.base_dir).map_err(|e| PluginError::Io(e.to_string()))? {
            let entry = entry.map_err(|e| PluginError::Io(e.to_string()))?;
            if !entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                continue;
            }
            let dir = entry.path();
            let manifest_path = dir.join("plugin.json");
            if !manifest_path.exists() {
                continue;
            }
            let Ok(content) = fs::read_to_string(&manifest_path) else {
                continue;
            };
            if let Ok(plugin) = serde_json::from_str::<RegistryPlugin>(&content) {
                plugins.push(PluginStatus {
                    enabled: dir.join("enabled").exists(),
                    name: plugin.name,
                    title: plugin.title,
                    description: plugin.description,
                    plugin_type: plugin.plugin_type,
                    version: plugin.version,
                    author: plugin.author,
                });
            }
        }
        Ok(plugins)
    }

    /// Read the JS entry content for a plugin (used by the client loader).
    pub fn get_plugin_js(&self, name: &str) -> Result<String> {
        let dir = self.base_dir.join(name);
        let manifest_path = dir.join("plugin.json");
        if !manifest_path.exists() {
            return Err(PluginError::Plugin(format!("插件 {name} 未安装")));
        }
        let content = fs::read_to_string(&manifest_path)
            .map_err(|e| PluginError::Io(e.to_string()))?;
        let manifest: RegistryPlugin = serde_json::from_str(&content)
            .map_err(|e| PluginError::Json(format!("解析 {name} 清单失败: {e}")))?;
        fs::read_to_string(dir.join(&manifest.entry)).map_err(|e| PluginError::Io(e.to_string()))
    }

    /// Enable or disable a plugin by creating/removing the "enabled" marker.
    pub fn toggle_plugin(&self, name: &str, enabled: bool) -> Result<()> {
        let dir = self.base_dir.join(name);
        if !dir.exists() {
            return Err(PluginError::Plugin(format!("插件 {name} 未安装")));
        }
        let enabled_path = dir.join("enabled");
        if enabled {
            fs::write(&enabled_path, b"1").map_err(|e| PluginError::Io(e.to_string()))?;
        } else {
            let _ = fs::remove_file(&enabled_path);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_empty_list() {
        let dir = std::env::temp_dir().join(format!("peyt-plugins-test-{}", std::process::id()));
        let m = PluginManager::new(dir.clone());
        assert!(m.list_plugins().unwrap().is_empty());
        assert!(m.uninstall_plugin("nope").is_err());
        let _ = fs::remove_dir_all(&dir);
    }
}
