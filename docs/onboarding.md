# 新人上手指南（Onboarding）

> 面向**刚接手本项目**的开发者。读完本文你应该能：把项目跑起来、看懂代码放在哪、知道改完东西怎么发出去、以及避开前人踩过的坑。
>
> 本文是总览。机制细节见 [hot-update.md](./hot-update.md)，发版操作见 [update-workflow.md](./update-workflow.md)。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| `docs/onboarding.md` | 本文：环境、构建、架构、约定、排错 |
| `docs/hot-update.md` | 热更新**机制**说明（清单格式、补丁类约定、ClassLoader、扩展点） |
| `docs/update-workflow.md` | 热更新**发布操作手册**（改完代码怎么推到用户端） |
| `.github/PULL_REQUEST_TEMPLATE.md` | PR 提交检查清单 |

---

## 一、项目是什么

**PEYT Chat** 是一款**自托管即时通讯 App**：

- 没有自建的推送服务器，消息收发走 **IMAP/SMTP**（协议层由 Rust 的 [chatmail/core](https://github.com/chatmail/core) 负责），保活靠 Android 前台服务。
- 采用**两段式更新**体系（类似手游）：
  - **小改动 → DEX 热更新补丁**：不用重新装包，用户「检查更新」后杀进程重启即生效；
  - **大改动 → APK 大版本更新**：App 内下载新 APK 并调系统安装器覆盖安装。
- 除聊天本身，还有「工作区 / 频道 / 角色 / 卡片」等协作概念（Room 本地库 + PEYT 信封协议）。

**当前版本**：`versionCode = 1`、`versionName = "0.1.1"`、`applicationId = cn.yzjtiantian.android`、`minSdk 26` / `targetSdk 35`。

---

## 二、技术栈

| 层 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.1.20、Rust（core 内核 + JNI 桥接） |
| UI | Jetpack Compose（BOM 2025.05.01）、Material3、Navigation Compose 2.9.8 |
| 构建 | Gradle **8.13**（wrapper）、AGP **8.9.1**、KSP `2.1.20-2.0.1` |
| 持久化 | Room 2.7.2（数据库 `peytchat.db`） |
| 协程 | kotlinx-coroutines 1.8.1 |
| 原生 | NDK + `cargo-ndk`，ABI **仅 arm64-v8a** |
| 依赖管理 | 版本集中在 `gradle/libs.versions.toml` |

版本号统一放 [gradle/libs.versions.toml](../gradle/libs.versions.toml)，不要在各模块里硬编码。

---

## 三、仓库结构与模块职责

### 3.1 先记住这个坑：两个 `core`

| 路径 | 是什么 | 参与 Gradle 构建？ |
| --- | --- | --- |
| `core/` | **Rust 子模块**（git submodule → `chatmail/core`），消息协议内核 | ❌ 不参与 |
| `libs/core/` | **Kotlin 模块** `:core`，包名 `cn.yzjtiantian.android.core.*` | ✅ 参与 |

[settings.gradle.kts](../settings.gradle.kts) 里显式把 `:core/:data/:theme/:patch-api` 的源码目录重定向到 `libs/` 下，就是为了避开这个重名。

### 3.2 目录树（只看源码）

```
peytchat-android/
├── app/                     # 入口：MainActivity、常驻服务、资源、jniLibs
├── libs/
│   ├── core/                # :core    RPC / 事件桥 / 热更新 / 账号 / 补丁注册表
│   ├── data/                # :data    Room 数据库 + Repository + 信封解析
│   ├── theme/               # :theme   颜色 / 图标 / 主题 / 主题对话框
│   └── patch-api/           # :patch-api  补丁稳定契约（ChatUiProvider）
├── feature/
│   ├── shell/               # :feature:shell  登录后主界面（消息/协作/通知/设置）
│   ├── login/               # :feature:login  登录页
│   └── chat/                # :feature:chat   预留，目前只有壳
├── patch/
│   ├── data/                # :patch:data          数据层补丁示例
│   └── notification/        # :patch:notification  通知行为补丁示例
├── rust/                    # Rust workspace（见 3.3）
├── core/                    # 【Rust 子模块】chatmail/core
├── patches/core/            # 对 core 子模块的本地 patch 文件
├── scripts/                 # 构建与发版脚本
└── docs/                    # 本文档所在目录
```

### 3.3 模块职责与依赖方向

```
                    ┌───────────────────────────────┐
                    │            :app               │  入口/资源/常驻服务
                    └───────┬───────────────┬───────┘
                            │               │
        ┌───────────────────┴───┐     ┌─────┴──────────────┐
        │      feature:*        │     │   libs (基础层)     │
        │ shell / login / chat  │────▶│ core / data / theme │
        └───────────────────┬───┘     │      patch-api      │
                            │         └────────────────────┘
                            └──────────────▶（同上）
        patch:data / patch:notification ──▶ 通过 :patch-api 的契约与基座交互
```

| 模块 | 职责 | 关键文件 |
| --- | --- | --- |
| `:app` | 进程入口、导航、常驻收消息服务、通知、资源、native 库 | `MainActivity.kt`、`MessageNotificationService.kt`、`BootReceiver.kt`、`NotificationHelper.kt` |
| `:core` | 底层能力：JSON-RPC、事件桥、会话、账号、热更新、补丁注册表 | `PeytBridge.kt`、`Rpc.kt`、`EventBridge.kt`、`Session.kt`、`CoreRuntime.kt`、`AccountManager.kt`、`HotUpdateManager.kt`、`ApkUpdateManager.kt`、`ModuleManager.kt` |
| `:data` | Room 持久化与业务仓库、信封编解码 | `AppDatabase.kt`、`repository/PeytRepository.kt`、`envelope/Envelope.kt`、`entity/*`、`dao/*` |
| `:theme` | 主题色板、图标、对话框样式 | `ui/theme/Theme.kt`、`Color.kt`、`TdesignIcons.kt`、`ThemeDialog.kt` |
| `:patch-api` | 补丁**稳定契约**，发布后不可随意改签名 | `patchapi/ChatUiProvider.kt` |
| `:feature:shell` | 登录后主容器（底部导航）与各业务页面 | `ShellScreen.kt`、`ChatScreen.kt`、`InboxScreen.kt`、`WorkScreen.kt`、`AboutUser.kt`、`AddDialogs.kt`、`UpdateDialog.kt` |
| `:feature:login` | 登录 / Chatmail 账号创建 | `LoginScreen.kt` |
| `:feature:chat` | 预留模块，暂无业务代码 | — |
| `:patch:data` | 数据层热更新示例（`TextSendHook`） | `DataPatch`、`DataTextHook`，`patchVersion = 0.0.2` |
| `:patch:notification` | 通知热更新示例（`MessageNotificationHook`） | `NotificationPatch`、`NotificationTextHook`，`patchVersion = 0.0.1` |

**依赖方向**：`feature:* → libs/*`，`app → 全部`，无环。**libs 不要反向依赖 feature。**

### 3.4 Rust 部分（`rust/`）

`rust/` 是一个 Cargo workspace，三个 crate：

| crate | 作用 |
| --- | --- |
| `peyt-envelope` | PEYT 信封协议编解码（`build_envelope` 等） |
| `peytchat-bridge` | **JNI 出口**：产出 `libpeytchat_bridge.so`，被 Kotlin 的 `PeytBridge` 加载 |
| `peytchat-plugins` | 插件（JS）注册表拉取 / 安装 / 列表 |

Kotlin 侧调用入口是 [PeytBridge.kt](../libs/core/src/main/java/cn/yzjtiantian/android/core/PeytBridge.kt)：

```kotlin
System.loadLibrary("peytchat_bridge")
external fun nativeInit(dir: String): Long
external fun nativeJsonrpcCall(request: String): String
external fun nativeBuildEnvelope(type: String, payload: String): String
// ... nativePlugins* 系列
```

`PeytBridge` 是 **synchronous / 阻塞** 的，上层用 `Rpc.kt`、`PeytEventLoop.kt` 包装并发与事件分发。**不要在 UI 线程直接调 native。**

---

## 四、环境准备

### 4.1 必备工具

| 工具 | 用途 | 备注 |
| --- | --- | --- |
| **JDK 17** | Gradle / AGP | 项目 `sourceCompatibility = 17` |
| **Android SDK** | 编译与打包 | 需 `platforms;android-35`、`build-tools`（含 `d8`、`apksigner`、`aapt2`） |
| **Android NDK** | 编译 Rust 交叉产物 | 脚本会探测 `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT` / `NDK_HOME` 或常见路径 |
| **Rust 工具链** | 编 core 与 bridge | 需要 `cargo`、`cargo-ndk`、**`lib-patcher`** |
| `adb` | 装包/看日志 | `scripts/build-android.sh` 会检查 |
| `python3` | 生成更新清单 | `gen-update-manifest.sh` 依赖 |
| `zip` / `unzip` | 校验 APK 内容 | 缺了只是少个校验，非致命 |

Rust 相关依赖安装：

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
cargo install lib-patcher          # 缺它会在「给 libdeltachat.a 打补丁」这一步失败
```

### 4.2 必须自己创建的两个文件（都被 gitignore）

| 文件 | 内容 | 何时需要 |
| --- | --- | --- |
| `local.properties` | `sdk.dir=/path/to/Android/Sdk` | **每次都要**（Gradle 找 SDK） |
| `keystore.properties` | `storeFile` / `storePassword` / `keyAlias` / `keyPassword` | 打 **release** 包才需要 |

参考 [app/build.gradle.kts](../app/build.gradle.kts)：`keystore.properties` 不存在时 `signingConfigs.release` 不会填充，**release 包会签名失败**——新人第一次打 release 十有八九栽在这。

### 4.3 初始化 Rust 子模块

`core/` 是 submodule，**clone 后默认是空的**，必须初始化，否则构建脚本第 1 步就报错：

```bash
git submodule update --init --recursive
```

### 4.4 Gradle 发行包

[gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties) 指向**阿里云镜像**的 `gradle-8.13-bin.zip`。若下载卡住/超时，可临时换回官方源：

```
distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip
```

---

## 五、构建与运行

### 5.1 关键前提：native 库必须先就位

`app/src/main/jniLibs/` 被 [.gitignore](../.gitignore) 忽略，**新克隆的仓库里没有 `libpeytchat_bridge.so`**。

它不会被 Gradle 自动构建——必须由 `scripts/build-android.sh` 先编 Rust 再复制进去。**没有它，App 一起来就 `UnsatisfiedLinkError`。**

当前仓库只产出 **arm64-v8a** 一个 ABI，即**只支持 arm64 设备/模拟器**。

### 5.2 方式 A：一把梭（推荐，含 Rust）

```bash
./scripts/build-android.sh debug        # 或 release
```

脚本依次做 5 步：

1. 检查 `core/` 子模块 → 应用 `patches/core/*.patch`（幂等）→ `cargo ndk -t arm64-v8a build -p deltachat_ffi`
2. 生成 `libdeltachat_patched.a`（用 `lib-patcher`，给 `dc_` 前缀符号打补丁）
3. 编译 `rust/` 下的 `peytchat-bridge`
4. 把 `libpeytchat_bridge.so` 复制到 `app/src/main/jniLibs/arm64-v8a/`
5. `./gradlew assemble<Debug|Release>`，并校验 APK 内确实含 `.so`；有设备则自动 `adb install -r` 并拉起

产物：`app/build/outputs/apk/{debug,release}/app-{debug,release}.apk`

> `patches/core/0001-dns-preload-yzjtiantian-cn.patch` 是对上游 core 的定制（修 DNS 预载导致的登录超时）。改动 core 相关行为时**要改这个 patch 文件**，而不是直接改子模块里的代码——否则下次构建会被重置。

### 5.3 方式 B：只改 Kotlin/Compose（快）

`jniLibs` 里已有 `.so` 时，纯 Kotlin 改动不需要重编 Rust：

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

单模块编译可加速：`./gradlew :feature:shell:assembleDebug`。

### 5.4 看日志

```bash
adb logcat | grep -E "cn.yzjtiantian.android|deltachat|rust"
```

日志 tag 以 `PEYT` 开头（如 `ApkUpdateManager` 用 `PEYT`、`NotifyLog` 用 `PEYT-notify`）。

---

## 六、运行期架构速览

搞清这几条链路，读代码就不会迷路：

### 6.1 启动链路

```
MainActivity
  └─ AppRoot
       ├─ 初始化核心事件循环（PeytEventLoop，进程单例）
       ├─ 判断登录态（Session.currentAccountId）
       │    ├─ 未登录 → LoginScreen → AccountManager 建号/登录 → start_io
       │    └─ 已登录 → ShellScreen
       └─ 启动 MessageNotificationService（前台服务，持有事件循环）
```

- `CoreRuntime`：native **一次性初始化** + 账号引导；
- `Session`：全局当前账号（所有 RPC 都基于 `Session.currentAccountId`）。

### 6.2 通信链路

- **请求**：业务代码 → `Rpc.kt` → `PeytBridge.nativeJsonrpcCall(...)` → Rust core → IMAP/SMTP；
- **事件**：core 推送事件 → `EventBridge` 分发（如 `IncomingMsg`）→ UI 刷新 / 弹通知。

### 6.3 数据链路

- **本地**：Room（`peytchat.db`）存工作区/频道/角色/卡片/收件箱等；
- **仓库**：`PeytRepository` 同时管 Room 和 RPC，是 UI 的主要数据入口；
- **信封**：消息体走 PEYT 信封协议（`data/envelope/Envelope.kt` + Rust `peyt-envelope`），`card.*` / `project.invite` 这类信封会触发本地副作用，并用 `(fromId, envelope.id)` 做**幂等去重**。

### 6.4 常驻收消息

| 组件 | 作用 |
| --- | --- |
| `MessageNotificationService` | 前台服务，持有事件循环，退后台/被杀后仍收消息并弹通知 |
| `BootReceiver` | 监听 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`，有账号就拉起上面这个服务 |
| `NotificationGate` | 标记「当前正在看的会话」，该会话不弹通知 |
| 通知点击 | 深链 `peytchat://chat/<chatId>` → ShellScreen 打开对应会话 |

> **平台限制（重要）**：Android 15 / targetSdk 35 下 `dataSync` 型前台服务**不能**从开机广播启动且**6 小时/天超时**，所以消息服务用 `specialUse` 类型。厂商省电策略仍可能杀后台——这是无推送服务器的自托管架构的固有限制，`specialUse` 已是 Android 允许范围内最接近的解法。

---

## 七、两套更新体系（最需要理解的部分）

### 7.1 为什么要分成两套

| 改动类型 | 发布方式 | 用户生效方式 |
| --- | --- | --- |
| 配置 / 文案 / 行为 / 颜色 | DEX 补丁 | 检查更新 + **杀进程重启** |
| 数据层行为（如发送内容加工） | DEX 补丁（`TextSendHook`） | 同上 |
| 通知行为 | DEX 补丁（`MessageNotificationHook`） | 同上 |
| 聊天界面结构 | DEX 补丁（`ChatUiProvider`） | 同上 |
| 其他 UI 结构（ShellScreen / 登录页） | **发新 APK** | 重新安装 |
| 任何涉及基座类签名变更的重构 | **发新 APK** | 重新安装 |

### 7.2 热更新：清单 + 补丁 dex

服务端只需托管静态文件：`update.json` + 若干 `.dex`。客户端「设置 → 检查更新」拉清单 → 下载 dex → SHA-256 校验 → 记录到 SharedPreferences → **下次进程启动**时用 `DexClassLoader` 加载并注册。清单地址**必须是 `https://`**（明文 http 会被直接拒绝）。

补丁约定（客户端靠反射按约定加载）：

| 项 | 约定 |
| --- | --- |
| 包名 | `cn.yzjtiantian.android.patch` |
| 类名 | `cn.yzjtiantian.android.patch.<Module>Patch`（shell/login/chat/account） |
| 文件名 | `<module>_<version>.dex`（版本号**单调递增**） |
| 可选方法 | `module()` / `version()` / `description()` / `boolean apply(Context)` |

三个稳定**扩展点**（补丁注册、基座消费）：

| 扩展点 | 契约位置 | 注册键 | 基座消费点 |
| --- | --- | --- | --- |
| 聊天界面 | `libs/patch-api/.../ChatUiProvider.kt` | `getUiProvider("chat")` | `ShellScreen` 打开频道时优先用补丁界面，否则回退 `ChatScreen` |
| 发送文本加工 | `libs/core/.../TextSendHook.kt` | `text_send_hook` | `PeytRepository.sendMessage` |
| 通知定制 | `libs/core/.../MessageNotificationHook.kt` | `message_notification_hook` | `MessageNotificationService.postIncomingNotification` |

**硬限制（parent-first 类加载）**：`DexClassLoader` 的 parent 是应用类加载器，**补丁里与基座同名的类不会生效**（解析到基座版本）。所以补丁**只能新增类 + 通过 `ModuleManager` 注册**，无法覆盖基座已有类。要「整体替换同名 UI 类」得用更重的方案（Tinker 式 classloader 替换 / Play Core 动态交付）。

`ModuleManager` 是补丁注册表：`registerModule` / `registerPatch` / `registerUiProvider` / `registerPatchService`，查询用对应的 `get*`。

### 7.3 APK 大版本更新（应用内安装）

清单顶层多一个 `apk` 字段：

```json
{
  "appVersion": "0.2.0",
  "apk": {
    "versionCode": 2,
    "versionName": "0.2.0",
    "url": "https://.../apk/peytchat-0.2.0.apk",
    "sha256": "…",
    "size": 12582912,
    "releaseNotes": "1. …\n2. …",
    "forceUpdate": false,
    "minSupportedVersion": 1
  },
  "patches": [ … ]
}
```

关键实现（[ApkUpdateManager.kt](../libs/core/src/main/java/cn/yzjtiantian/android/core/ApkUpdateManager.kt)）：

| 能力 | 说明 |
| --- | --- |
| `checkForApkUpdate()` | 拉清单 → 解析 `apk` → `versionCode` **严格大于**当前（或低于 `minSupportedVersion`）才算有更新 |
| `getCurrentVersionCode()` | 用 `PackageManager` 读取（library 模块拿不到 app 的 `BuildConfig`） |
| `downloadApk()` | 流式下载到 `filesDir/apks/`（文件名用本地 `versionCode`，不信任远端 `versionName`），带进度回调、支持 `cancelDownload()`、完成后 SHA-256 校验；下载失败/哈希不符即删文件 |
| `installApk()` | `FileProvider` content URI + `ACTION_INSTALL_PACKAGE`；`canRequestInstall()` 判断「安装未知应用」是否已授权，未授权则引导到系统设置页 |
| 安装后补补丁 | 下载成功后写 `PREF_APK_JUST_UPDATED`（`apk_just_updated`）；`EventBridge.start()` 启动时消费该标志，静默跑一次 `checkForUpdates` |

UI 在 [UpdateDialog.kt](../feature/shell/src/main/java/cn/yzjtiantian/android/feature/shell/UpdateDialog.kt)：打开即检查 → 有新版本显示版本号 + 更新说明 + 「立即更新 / 稍后再说」（`forceUpdate=true` 时隐藏后者）→ 下载进度 → 自动调起安装；下载/安装中禁止关闭对话框。

**发布流程**（详见 [update-workflow.md](./update-workflow.md)）：

```bash
# 1. 递增 versionCode（必须！否则客户端不会提示）
#    改 app/build.gradle.kts 的 defaultConfig.versionCode
# 2. 打 release 包（签名必须与线上一致，否则覆盖安装失败）
./gradlew :app:assembleRelease
# 3. 生成清单（自动算 sha256 / size / url）
./scripts/gen-update-manifest.sh \
    --apk app/build/outputs/apk/release/app-release.apk \
    --apk-version-code 2 \
    --apk-version-name 0.2.0 \
    --apk-release-notes release-notes.txt \
    https://peyt.org/peytchat-android-update/ \
    updates/patches/shell_1.0.1.dex
# 4. 上传 APK + update.json + dex 到静态服务器
```

### 7.4 本地联调（几个必须注意的点）

```bash
# 客户端只接受 https 清单地址，明文 http:// 会被直接拒绝。
# 本地联调需提供 https：用自签证书的静态服务器，或走内网穿透（如 ngrok）。
```

1. **清单/补丁/APK 地址必须是 `https://`**：`HotUpdateManager` / `ApkUpdateManager`
   会校验协议，非 https 一律抛错拒绝（防止中间人篡改更新内容）。
2. **手机上的旧包必须也能访问该地址**：更新检查是**旧包**发起的，所以旧包也得能连通该 https 地址。
3. **清单里的 path 要对**：服务器根目录、`apk/`、`patches/` 子目录、`sha256`、`size` 必须和实际文件一致；`url` 少一层目录就是 404。
4. **哈希无身份认证能力**：客户端仅校验 `sha256` 与文件一致，不校验服务端身份；
   真正的信任链（清单签名验签）尚未实现，不要把它当安全边界。

---

## 八、开发约定

1. **模块边界**：`libs/*` 是基础层，不得依赖 `feature/*`；新页面放对应 `feature:*`；跨模块共享的底层能力放 `:core`。
2. **包名**：`cn.yzjtiantian.android.<模块语义>`（`core` / `data` / `ui.theme` / `feature.<name>` / `patch`）。
3. **版本集中管理**：依赖版本写进 `gradle/libs.versions.toml`，用 `alias(libs.plugins.*)` / `libs.*` 引用。
4. **补丁版本递增**：改 `patch/*` 时同步递增其 `build.gradle.kts` 里的 `patchVersion`，**不要覆盖旧 dex 文件**（便于回滚、避免 CDN 缓存）。
5. **稳定契约不要乱改**：`ChatUiProvider` / `TextSendHook` / `MessageNotificationHook` 的签名一旦发布，旧基座/旧补丁会依赖它，改了要评估兼容性。
6. **发 APK 时 versionCode 必须 +1**，且补丁版本建议同步递增（避免老补丁在新基座上加载失败）。
7. **UI 线程纪律**：native 调用/RPC 是阻塞的，不要在 UI 线程直接调。
8. **PR 规范**：按 [.github/PULL_REQUEST_TEMPLATE.md](../.github/PULL_REQUEST_TEMPLATE.md)，要求本地构建通过、无调试代码、无遗留 `TODO/FIXME`、文档同步更新。
9. **仓库目前没有 CI**（`.github/` 下只有 PR 模板），构建与测试靠本地执行，**PR 前请自己跑通构建**。

---

## 九、常见问题排查（FAQ）

| 现象 | 原因 / 解决 |
| --- | --- |
| `UnsatisfiedLinkError: dlopen failed: library "libpeytchat_bridge.so" not found` | `jniLibs` 里没有 `.so`（被 gitignore 了）。先跑 `./scripts/build-android.sh debug` |
| `core 目录不存在` / `core/Cargo.toml 不存在` | 子模块没初始化：`git submodule update --init --recursive` |
| 构建卡在 `lib-patcher 未安装` | `cargo install lib-patcher` |
| `cargo ndk: command not found` | `cargo install cargo-ndk`，并 `rustup target add aarch64-linux-android` |
| Gradle 发行包下载超时 | 阿里云镜像不稳时换回 `services.gradle.org`（见 4.4） |
| release 包签名失败 | 缺 `keystore.properties`（见 4.2） |
| 检查更新报「仅支持 https 清单地址」 | 清单地址不是 `https://`，客户端主动拒绝（见 7.4） |
| 覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名冲突） | 新旧包签名证书不一致。用 `apksigner verify --print-certs` 对比 SHA-256；不一致只能卸载重装（丢数据） |
| 检查更新提示失败 / 提示「失败 1 个」 | `sha256` 与文件不符、dex 损坏、或 URL 404；`sha256sum` 核对后重新生成清单 |
| 补丁下载了但重启后不生效 | ① 入口类名必须是 `cn.yzjtiantian.android.patch.<Module>Patch`（大小写敏感）；② 文件名/清单 `module` 与类名要对应；③ **补丁类与基座类重名了**——parent-first 下会被基座类遮蔽，改成新类名 |
| 桌面开发机上找不到 `patch-sample/`、`updates/` | 这两个目录被 gitignore 且当前仓库里没有。写新补丁请以 `patch/data` 或 `patch/notification` 作为构建骨架 |
| 杀进程重启后补丁才生效？ | 设计如此：补丁在**下次进程启动**时加载，改完必须完整杀掉进程再启动 |

---

## 十、新人第一步建议

1. **通读本文** + 浏览 `docs/hot-update.md`（机制）、`docs/update-workflow.md`（操作）。
2. **把项目跑起来**：装好 4.1 的工具 → 建 `local.properties` → `git submodule update --init --recursive` → `./scripts/build-android.sh debug`。
3. **顺着启动链路读代码**：`MainActivity` → `AppRoot` → `ShellScreen` → `PeytRepository`。
4. **理解信封协议**：`data/envelope/Envelope.kt` 与 Rust 的 `peyt-envelope`。
5. **动手试一次热更新**：按 `docs/update-workflow.md` 造一个补丁 dex，本地 `python3 -m http.server` 联调，观察「下载 → 校验 → 重启加载」全链路。
6. **动手试一次 APK 更新**：按 7.3 生成带 `apk` 字段的清单，走一遍「下载 → 安装 → 重启自动补补丁」。

> 任何与文档不符的地方，以**代码**为准，并顺手更新文档——文档过期比没有文档更危险。
