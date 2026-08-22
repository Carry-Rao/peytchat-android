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

## 部署到 GitHub（免费静态托管）

热更新服务端只需要托管静态文件：`update.json` 清单 + 补丁 `.dex`。三种方式任选：

### 方式一：GitHub Pages（推荐，最稳定）

1. 新建一个 **Public** 仓库，例如 `peytchat-updates`（也可用当前项目仓库）。
2. 仓库内建如下结构：

   ```
   peytchat-updates/
   ├── update.json          # 清单（用 scripts/gen-update-manifest.sh 生成）
   └── patches/
       └── shell_1.0.1.dex  # 补丁 dex
   ```

3. 仓库 Settings → Pages → **Source: Deploy from a branch** → 选 `main` / `(root)` → Save。
4. 清单地址即：`https://<用户名>.github.io/peytchat-updates/update.json`
   （项目仓库则是 `https://<用户名>.github.io/<仓库名>/update.json`）。
5. 客户端「检查更新」对话框里填这个地址即可（会自动记住）。

### 方式二：GitHub Releases 分发补丁文件

补丁 dex 走 Releases，清单仍放 Pages 或 raw：

- 把 `shell_1.0.1.dex` 作为 Release asset 上传；
- 清单里的 `url` 填
  `https://github.com/<用户名>/<仓库名>/releases/download/<tag>/shell_1.0.1.dex`；
- 客户端 `HttpURLConnection` 默认跟随 302 跳转到 CDN，无需改代码。
- 优点：单文件上限 2GB、带版本标签；缺点：每次发版要手动建 Release。

### 方式三：raw.githubusercontent.com（零配置，快速测试）

- 清单：`https://raw.githubusercontent.com/<用户名>/<仓库名>/main/update.json`
- 适合联调，不推荐生产（有文件大小限制，且国内访问不稳）。

### 国内访问优化：jsDelivr CDN

GitHub 直连/Pages 在国内可能不稳，可用 jsDelivr 加速（免费、无需配置）：

```
https://cdn.jsdelivr.net/gh/<用户名>/<仓库名>@main/update.json
https://cdn.jsdelivr.net/gh/<用户名>/<仓库名>@main/patches/shell_1.0.1.dex
```

注意：CDN 有缓存（单文件上限 20MB）。发布新补丁后可能延迟几分钟生效，
建议补丁文件名带上版本号（如 `shell_1.0.1.dex`），保证新版本必然命中新 URL。

### 一键生成清单

```bash
# 生成 update.json（补丁文件命名必须为 <module>_<version>.dex）
./scripts/gen-update-manifest.sh \
    https://<用户名>.github.io/peytchat-updates \
    updates/patches/shell_1.0.1.dex \
    updates/patches/login_1.0.2.dex
```

脚本自动计算每个文件的 MD5 并生成 `update.json`，然后提交推送即可。

## 客户端流程

1. 设置页 →「检查更新」→ 弹出对话框（默认清单地址
   `https://peyt.org/peytchat-android-update/update.json`，可修改并持久化）。
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

## 补丁类怎么写

客户端按**约定**反射加载补丁，补丁类不依赖 App 的任何接口，纯 Java/Kotlin 均可。

### 约定

| 项 | 约定 |
| --- | --- |
| 包名 | `cn.yzjtiantian.android.patch`（基座中不存在，parent-first 加载会命中补丁） |
| 类名 | `cn.yzjtiantian.android.patch.<Module>Patch`：shell→`ShellPatch`、login→`LoginPatch`、chat→`ChatPatch`、account→`AccountPatch` |
| 构造器 | public 无参（默认即可） |
| 可选方法 | `String module()`、`String version()`、`String description()`、`boolean apply(Context)` |

- `description()` 会显示在设置页「检查更新」对话框的「已加载补丁」列表里；
- `apply(Context)` 在进程启动、补丁加载时于**后台线程**调用，可写配置/注册行为，
  返回 boolean 表示是否应用成功（缺省视为成功）。

### 最小示例

仓库里已提供可直接编译的模板：`patch-sample/ShellPatch.java`
（完整编译发布步骤见 `patch-sample/README.md`）：

```java
package cn.yzjtiantian.android.patch;

import android.content.Context;

public class ShellPatch {
    public String module()      { return "shell"; }
    public String version()     { return "1.0.1"; }
    public String description() { return "示例补丁：写入一条补丁消息"; }

    public boolean apply(Context context) {
        context.getSharedPreferences("peyt_hot_update", Context.MODE_PRIVATE)
                .edit().putString("patch_message", "来自 shell 补丁 v1.0.1 的问候").apply();
        return true;
    }
}
```

编译打包（在 `patch-sample/` 目录下执行，完整步骤见 `patch-sample/README.md`）：

```bash
javac -source 8 -target 8 \
    -classpath "$ANDROID_HOME/platforms/android-35/android.jar" \
    -d out src/cn/yzjtiantian/android/patch/*.java
mkdir -p out/dex
"$ANDROID_HOME/build-tools/35.0.0/d8" --output out/dex out/cn/yzjtiantian/android/patch/*.class
mv out/dex/classes.dex shell_1.0.1.dex     # 命名 <module>_<version>.dex
```

然后 `../scripts/gen-update-manifest.sh <base_url> shell_1.0.1.dex` 生成清单发布即可。

### 验证补丁确实生效

客户端加载补丁时若找到约定入口类，会实例化并调用 `apply()`，
把结果注册进 `ModuleManager`（`LoadedPatch`）。设置页「检查更新」对话框会显示：

```
已加载补丁：
shell v1.0.1 — 示例补丁：写入一条补丁消息
补丁消息：来自 shell 补丁 v1.0.1 的问候
```

业务代码也可自行查询：

```kotlin
val patch = ModuleManager.getPatch("shell")   // LoadedPatch?
val loader = ModuleManager.getModuleClassLoader("shell")
val clazz = loader?.loadClass("cn.yzjtiantian.android.patch.ShellPatch")
```

## 界面热更新（ChatUiProvider 扩展点）

除「行为补丁」外，聊天界面也支持通过补丁整体替换（无需发版）。机制：

1. **稳定契约** `libs/patch-api/.../patchapi/ChatUiProvider.kt`：
   `@Composable fun ChatContent(repository: PeytRepository, channel: ChannelDto)`；
2. **基座分发**：`ShellScreen` 打开频道时先查
   `ModuleManager.getUiProvider("chat")`，注册了补丁就用补丁界面，否则回退内置 `ChatScreen`；
3. **补丁注册**：补丁入口类 `ChatPatch.apply(Context)` 里调用
   `ModuleManager.registerUiProvider("chat", ChatUiV2())` 完成注册（通用 Object 存储，
   `:core` 不依赖具体契约）。

补丁模块 `patch/chat` 就是一个可复制的模板（Kotlin + Compose，含打 dex 的
`packagePatchDex` 任务）。改 chatUI 的操作流程：

```bash
# 1. 在 patch/chat 里改/新增 ChatUiProvider 实现（新类名，如 ChatUiV2）
# 2. 构建补丁 dex（版本号在 patch/chat/build.gradle.kts 的 patchVersion 里递增）
./gradlew :patch:chat:packagePatchDex
# 3. 生成清单并上传（updates/chat_<version>.dex + update.json）
OUT=updates/update.json ./scripts/gen-update-manifest.sh \
    https://peyt.org/peytchat-android-update/ updates/chat_1.0.2.dex
```

要点：

- 补丁实现类名必须是基座中不存在的新类（parent-first 下才能命中补丁 dex）；
- 补丁实现可自由引用 `PeytRepository`/`ChannelDto`/compose/主题等基座类
  （补丁类加载器的 parent 能看到基座全部类）；
- `ChatUiProvider` 属稳定契约，签名发布后不要随意改；
- 基座 UI 发版不影响已下发的 UI 补丁：新版基座启动时仍会检测并加载补丁界面。

## 已知限制（重要）

Android 类加载是 **parent-first** 的：`DexClassLoader` 的 parent 是应用类加载器，
若补丁类名与基座 APK 内已有类**完全同名**（例如补丁里也写
`cn.yzjtiantian.android.ui.shell.ChatScreen`），解析到的会是基座版本，补丁不会生效。

因此：

- 补丁**不要**试图覆盖基座已有类，改为提供新类（入口类 / `ChatUiProvider` 实现）
  并让业务代码通过 `ModuleManager` 查询调用；
- 若需要无扩展点的整体替换（同名类覆盖），需要更重的方案（如 Tinker 式
  classloader 替换、或 Google Play 的 Dynamic Feature Delivery + Play Core 按需下载）。

## 代码入口

- `libs/core/.../core/HotUpdateManager.kt` — 清单/下载/校验/加载（含补丁入口类反射）
- `libs/core/.../core/ModuleManager.kt` — 模块/补丁/UI 提供者注册表（`LoadedPatch`）
- `libs/core/.../core/EventBridge.kt` — 启动时加载待应用补丁 + 事件通道
- `libs/patch-api/.../patchapi/ChatUiProvider.kt` — 聊天界面稳定契约
- `feature/shell/.../ui/shell/ShellScreen.kt` — 聊天区补丁优先/基座回退分发
- `feature/shell/.../ui/shell/UpdateDialog.kt` — 检查更新对话框（展示已加载补丁）
- `patch/chat/` — chat 补丁模块（入口类 + UI 实现 + packagePatchDex 任务）
- `feature/shell/.../ui/shell/ShellScreen.kt` — 设置页「检查更新」入口
- `patch-sample/` — 可编译发布的补丁示例
