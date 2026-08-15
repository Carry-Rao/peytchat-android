# 热更新（Hot Update）说明

自托管 DEX 补丁热更新：客户端从服务端拉取**更新清单**，下载补丁 `.dex` 文件并做
MD5 校验，记录后**下次启动时**通过 `DexClassLoader` 加载注册。

## 模块结构

拆分后各模块职责（根目录 `core/` 是 chatmail/core 的 Rust 子模块，不参与 Gradle 构建）：

| 模块 | 目录 | 内容 |
| --- | --- | --- |
| `:core` | `libs/core` | `cn.yzjtiantian.android.core.*`：RPC、事件桥、热更新管理器等 |
| `:data` | `libs/data` | `cn.yzjtiantian.android.data.*`：Room 数据库、Repository、信封解析 |
| `:theme` | `libs/theme` | `cn.yzjtiantian.android.ui.theme.*`：主题/颜色/图标 |
| `:feature:shell` | `feature/shell` | 主界面（ShellScreen / ChatScreen / 设置页等） |
| `:feature:login` | `feature/login` | 登录界面 |
| `:feature:chat` | `feature/chat` | 预留聊天模块（当前为空） |
| `:app` | `app` | 入口 MainActivity、资源、jniLibs |

依赖方向：`feature:* → core/data/theme`，`app → 全部`，无环。

## 更新清单格式

服务端只要托管一个静态 JSON 即可，例如 `https://your-server/peytchat/update.json`：

```json
{
  "appVersion": "0.1.0",
  "patches": [
    {
      "module": "shell",
      "version": "1.0.1",
      "url": "https://your-server/peytchat/patches/shell_1.0.1.dex",
      "md5": "d41d8cd98f00b204e9800998ecf8427e"
    },
    {
      "module": "login",
      "version": "1.0.2",
      "url": "https://your-server/peytchat/patches/login_1.0.2.dex",
      "md5": "9e107d9d372bb6826bd81d3542a419d6"
    }
  ]
}
```

字段说明：

- `appVersion`：可选，App 版本（当前仅展示用，不做强制校验）。
- `patches[].module`：模块名，取值 `shell` / `login` / `chat` / `account`。
- `patches[].version`：补丁版本号（字符串比较；与本地已记录版本不同即视为需要更新）。
- `patches[].url`：补丁 dex 的下载地址。
- `patches[].md5`：补丁 dex 的 MD5（小写十六进制），客户端下载后校验，不通过则丢弃。

## 客户端流程

1. 设置页 →「检查更新」→ 弹出对话框（默认清单地址
   `https://update.example.com/peytchat/update.json`，可修改并持久化）。
2. 点击「立即检查」→ `HotUpdateManager.checkForUpdates(url)`：
   - 拉取清单，逐条对比本地已记录版本；
   - 新版本 → 下载 dex → MD5 校验 → 写入
     `SharedPreferences`（`<module>_patch_path` / `<module>_version`，`loaded=false`）。
3. 下次进程启动时 `EventBridge.start()` 调用 `HotUpdateManager.loadPendingPatches()`：
   - 重置各模块 `loaded=false`（DexClassLoader 不跨进程保留，需重新加载）；
   - 对存在补丁文件的模块执行 `DexClassLoader` 加载并注册到 `ModuleManager`。

## 如何测试

1. 准备一个清单 JSON 与一个补丁 dex（见下）。
2. 本地起静态文件服务，例如：

   ```bash
   cd /tmp/update && python3 -m http.server 8000
   ```

3. 手机与电脑同一网络，清单地址填
   `http://<电脑IP>:8000/update.json`。
4. 设置页 →「检查更新」→「立即检查」，观察下载/校验结果提示。

### 构造一个测试补丁 dex

补丁只需要是合法 dex 即可走通「下载 → 校验 → 记录 → 加载」链路。最简单的方式：

```bash
# 1. 写一个简单类（任意包名、任意内容）
cat > Patch.java <<'EOF'
public class Patch {
    public static String hello() { return "hot-update-patch"; }
}
EOF

# 2. 编译并打成 dex（d8 在 Android SDK build-tools 里）
javac Patch.java
d8 --output . Patch.class            # 生成 classes.dex，改名为 shell_1.0.1.dex

# 3. 计算 md5 并填进清单
md5sum shell_1.0.1.dex
```

### 验证补丁确实被加载

补丁类名与基座不冲突时，可通过 `ModuleManager` 反射拿到：

```kotlin
val loader = ModuleManager.getModuleClassLoader("shell")
val clazz = loader?.loadClass("Patch") // 补丁里的类
```

## 已知限制（重要）

Android 类加载是 **parent-first** 的：`DexClassLoader` 的 parent 是应用类加载器，
若补丁类名与基座 APK 内已有类**完全同名**（例如补丁里也写
`cn.yzjtiantian.android.ui.shell.ShellScreen`），解析到的会是基座版本，补丁不会生效。

因此要让补丁真正生效：

- 补丁应提供基座中**不存在**的新类（例如实现固定接口的 `XxxPatch` 实现类）；
- 业务代码通过 `ModuleManager` 反射查找该类并调用。

如果需要整体替换 Compose 界面（同名类覆盖），需要考虑更重的方案（如 Tinker 式
classloader 替换、或 Google Play 的 Dynamic Feature Delivery + Play Core 按需下载）。

## 代码入口

- `libs/core/.../core/HotUpdateManager.kt` — 清单/下载/校验/加载
- `libs/core/.../core/ModuleManager.kt` — 模块 ClassLoader 注册表
- `libs/core/.../core/EventBridge.kt` — 启动时加载待应用补丁 + 事件通道
- `feature/shell/.../ui/shell/UpdateDialog.kt` — 检查更新对话框
- `feature/shell/.../ui/shell/ShellScreen.kt` — 设置页「检查更新」入口
