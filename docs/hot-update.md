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
3. **补丁注册**：补丁入口类（如 `ChatPatch`）的 `apply(Context)` 里调用
   `ModuleManager.registerUiProvider("chat", ChatUiV2())` 完成注册（通用 Object 存储，
   `:core` 不依赖具体契约）。

改 chatUI 时，新建一个补丁模块（可复制 `patch/data` 的构建骨架，Kotlin + Compose，
含打 dex 的 `packagePatchDex` 任务）：

```bash
# 1. 新建 patch/chat 模块，写 ChatUiProvider 实现（新类名，如 ChatUiV2）
# 2. 构建补丁 dex（版本号在 build.gradle.kts 的 patchVersion 里递增）
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

## 数据层行为热更新（TextSendHook 扩展点）

除 UI 外，数据层行为也可被补丁改变。现成的示例就是 `patch/data` 模块：

1. **稳定契约** `libs/core/.../core/TextSendHook.kt`：
   `fun interface TextSendHook { fun transform(text: String): String? }`，
   注册键 `TextSendHook.SERVICE_KEY = "text_send_hook"`；
2. **基座消费**：`PeytRepository.sendMessage` 发送文本前查询
   `ModuleManager.getPatchService("text_send_hook") as? TextSendHook`，
   有钩子就用返回值发送（返回 null 不拦截）；
3. **补丁注册**：`patch/data` 的入口类 `DataPatch.apply(Context)` 里
   `ModuleManager.registerPatchService(TextSendHook.SERVICE_KEY, DataTextHook())`。

`patch/data` 的 `DataTextHook` 给每条发送的文本追加「（数据层补丁 v0.0.1）」后缀，
用于验证热更新全链路。构建发布流程与上面相同：

```bash
./gradlew :patch:data:packagePatchDex
OUT=updates/update.json ./scripts/gen-update-manifest.sh \
    https://peyt.org/peytchat-android-update/ updates/data_0.0.2.dex
```

注意：钩子只作用于用户文本消息（`sendMessage`），不影响 card.*/project.invite 等
信封协议消息（避免破坏协议格式）。

## 消息通知热更新（MessageNotificationHook 扩展点）

App 常驻消息服务（`MessageNotificationService`，前台服务 + 开机自启）收到新消息
会弹系统通知；**通知的内容与是否弹通知可由补丁热更新**，无需发版。

1. **稳定契约** `libs/core/.../core/MessageNotificationHook.kt`：
   `fun interface MessageNotificationHook {
      fun customize(context: Context, default: MessageNotification): MessageNotification?
   }`，
   注册键 `MessageNotificationHook.SERVICE_KEY = "message_notification_hook"`；
   - `MessageNotification`（title/text/chatId/msgId/channelId/priority/autoCancel/groupKey）
     是基座组装好的默认通知内容；
   - 返回**修改后的** `MessageNotification` → 按定制内容弹通知；
   - 返回 **null** → 静默该条消息（不弹通知）。
2. **基座消费**：`MessageNotificationService.postIncomingNotification` 在弹通知前查询
   `ModuleManager.getPatchService("message_notification_hook") as? MessageNotificationHook`，
   有钩子就用定制结果（null 则跳过）。
3. **补丁注册**：`patch/notification` 的入口类 `NotificationPatch.apply(Context)` 里
   `ModuleManager.registerPatchService(MessageNotificationHook.SERVICE_KEY, NotificationTextHook())`。

`patch/notification` 的 `NotificationTextHook` 给通知正文加「📣」前缀，用于验证
「通知行为热更新」全链路。构建发布流程与上面相同：

```bash
./gradlew :patch:notification:packagePatchDex
OUT=updates/update.json ./scripts/gen-update-manifest.sh \
    https://peyt.org/peytchat-android-update/ updates/notification_0.0.1.dex
```

### 消息通知基础能力（基座内置，补丁之上）

| 能力 | 说明 |
| --- | --- |
| 退后台/杀进程收消息 | `MessageNotificationService` 常驻前台服务（`specialUse` 类型）持有事件循环 |
| 开机自启 | `BootReceiver` 监听 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`，有账号即拉起服务 |
| 点击通知跳会话 | 通知携带 `peytchat://chat/<chatId>` 深链，ShellScreen 打开对应会话 |
| 当前会话免打扰 | UI 打开会话时设置 `NotificationGate.activeChatId`，服务不再为该会话弹通知 |
| 打开会话清通知/未读 | ChannelScreen 打开时 `marknoticed_chat` + 取消该会话通知 |
| 通知折叠 | 同一会话的新消息更新同一条通知（按 chatId 做通知 id） |

### 已知平台限制（重要）

- **Android 15 / targetSdk 35**：`dataSync` 型前台服务既**不能**从 `BOOT_COMPLETED`
  启动、又有 **6 小时/天超时**，因此消息服务改用 `specialUse` 类型（无超时、
  可从开机广播启动；Play 上架需在控制台说明用途，自托管分发无此问题）。
- **厂商深度省电 / 用户手动强制停止**仍可能杀后台：仿 QQ/微信的「永不掉线」
  在无 FCM/厂商推送服务器的自托管架构下无法 100% 保证，FGS 已是 Android
  允许范围内最接近的方案（QQ/微信亦依赖各自厂商推送通道）。

## 已知限制（重要）

Android 类加载是 **parent-first** 的：`DexClassLoader` 的 parent 是应用类加载器，
若补丁类名与基座 APK 内已有类**完全同名**（例如补丁里也写
`cn.yzjtiantian.android.ui.shell.ChatScreen`），解析到的会是基座版本，补丁不会生效。

因此：

- 补丁**不要**试图覆盖基座已有类，改为提供新类（入口类 / `ChatUiProvider` 实现 /
  `TextSendHook` 实现）并让业务代码通过 `ModuleManager` 查询调用；
- 若需要无扩展点的整体替换（同名类覆盖），需要更重的方案（如 Tinker 式
  classloader 替换、或 Google Play 的 Dynamic Feature Delivery + Play Core 按需下载）。

## 代码入口

- `libs/core/.../core/HotUpdateManager.kt` — 清单/下载/校验/加载（含补丁入口类反射）
- `libs/core/.../core/ModuleManager.kt` — 模块/补丁/UI 提供者/补丁服务注册表（`LoadedPatch`）
- `libs/core/.../core/EventBridge.kt` — 启动时加载待应用补丁 + 事件通道
- `libs/core/.../core/TextSendHook.kt` — 数据层发送钩子稳定契约
- `libs/core/.../core/MessageNotificationHook.kt` — 消息通知定制钩子稳定契约（热更新）
- `libs/core/.../core/PeytEventLoop.kt` — 事件循环进程单例（服务与 UI 共享，避免事件被拆分）
- `libs/core/.../core/CoreRuntime.kt` — native 一次性初始化 + 账号引导
- `libs/core/.../core/NotificationGate.kt` — 当前打开会话标记（免打扰）
- `libs/core/.../core/MessageNotifications.kt` — 通知 id 换算 / 取消通知（无资源依赖）
- `libs/patch-api/.../patchapi/ChatUiProvider.kt` — 聊天界面稳定契约
- `feature/shell/.../ui/shell/ShellScreen.kt` — 聊天区补丁优先/基座回退分发 + `peytchat://chat/` 深链
- `feature/shell/.../ui/shell/UpdateDialog.kt` — 检查更新对话框（展示已加载补丁）
- `patch/data/` — 数据层补丁模块（入口类 `DataPatch` + `DataTextHook` + packagePatchDex 任务）
- `patch/notification/` — 消息通知补丁模块（入口类 `NotificationPatch` + `NotificationTextHook`）
- `app/.../MessageNotificationService.kt` — 常驻前台服务（事件循环持有者 + 弹通知）
- `app/.../NotificationHelper.kt` — 通知渠道/构造/投递（含前台服务常驻通知）
- `app/.../BootReceiver.kt` — 开机/升级后自启消息服务
- `feature/shell/.../ui/shell/ShellScreen.kt` — 设置页「检查更新」入口
- `patch-sample/` — 可编译发布的补丁示例
