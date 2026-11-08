# PEYT Chat

PEYT Chat 是一个基于 [chatmail](https://github.com/chatmail/core) 核心的 Android 即时通讯客户端，使用 Kotlin 与 Jetpack Compose 构建。

项目直接通过 JNI 调用 Rust 核心，不依赖官方 Android 客户端代码。

> 当前状态：**开发中 / 预览版**（`v1.1.1-rc.1`）。预览版可能存在数据丢失或功能不稳定的风险，请勿用于生产环境。

## 功能

- **私聊与频道**：直接消息（DM）与会话频道，支持文字、图片、文件等消息
- **看板视图**：卡片式频道内容（kanban）
- **端到端加密**：由核心提供 OpenPGP / Autocrypt 加密与密钥管理
- **消息通知**：前台服务 + 通知渠道，可在设置中查看通知链路状态并发送测试通知
- **主题切换**：深色 / 浅色 / 跟随系统，状态栏与导航栏图标自动适配
- **多账号**：账号管理与快速切换
- **热更新**：DEX 补丁热更新与 APK 版本更新（见 `docs/`）

## 技术栈

| 层 | 技术 |
| --- | --- |
| UI | Kotlin 2.1.20、Jetpack Compose（BOM 2025.05.01）、Material 3 |
| 架构 | 多模块、Repository、Compose 状态驱动 |
| 本地存储 | Room 2.7.2 |
| 异步 | Kotlin Coroutines |
| 核心 | Rust `chatmail core`（`core/` 子模块）+ `rust/peytchat-bridge` JNI |
| 构建 | AGP 8.9.1、Gradle 8.13、JDK 17、compileSdk 35 |

## 项目结构

```text
app/                    应用入口、MainActivity、通知前台服务
feature/shell/          主界面：消息列表、聊天、频道、设置
feature/login/          登录 / 账号配置
feature/chat/           聊天 UI 扩展点
libs/core/              RPC 客户端、事件循环、会话、通知、热更新
libs/data/              Room 数据库与仓库层
libs/theme/             主题、配色、主题管理
libs/patch-api/         热补丁 API
patch/                  补丁模块（data / notification）
rust/peytchat-bridge/   Rust ↔ Kotlin JNI 桥
core/                   chatmail/core 子模块（Rust）
docs/                   热更新与发布文档
scripts/                构建脚本
```

## 构建

### 环境要求

- JDK 17+
- Android SDK（compileSdk 35）与 Android NDK
- Rust 工具链（stable）与 `cargo-ndk`
- 建议使用 Linux 或 macOS

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
```

### 初始化

```bash
git clone --recursive git@github.com:Carry-Rao/peytchat-android.git
cd peytchat-android

# 如果克隆时没有带上子模块
git submodule update --init --recursive
```

确保 `local.properties` 中的 SDK 路径正确：

```properties
sdk.dir=/path/to/android-sdk
```

### 一键构建（推荐）

脚本会依次编译核心与桥接库、复制 `.so`，最后调用 Gradle 打包：

```bash
# Debug APK（默认 arm64-v8a）
./scripts/build-android.sh debug

# Release APK（签名密钥由 CI/CD Secrets 注入；本地构建需自行配置）
./scripts/build-android.sh release

# 同时构建真机与模拟器架构
./scripts/build-android.sh debug arm64-v8a,x86_64
```

构建产物位于：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

未配置签名时，release 产物为 `app-release-unsigned.apk`，无法直接安装。签名密钥通过 CI/CD Secrets 注入，不提交到仓库。

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 仅用 Gradle 构建

需要先确保 `app/src/main/jniLibs/<abi>/libpeytchat_bridge.so` 已生成：

```bash
./gradlew :app:assembleDebug
```

## 版本与发布

- 版本号在 `app/build.gradle.kts` 的 `versionCode` / `versionName` 中维护。
- 发布使用 Git tag：稳定版 `vX.Y.Z`，预发布版 `vX.Y.Z-rc.N`。
- 发布前请先构建并实际安装验证对应 APK。

热更新与补丁发布流程见：

- [`docs/hot-update.md`](docs/hot-update.md)
- [`docs/update-workflow.md`](docs/update-workflow.md)

## 许可证

本项目（不含 `core/` 子模块）采用 **GNU Affero General Public License v3.0**，完整文本见 [`LICENSE`](LICENSE)。

`core/` 子模块为 [chatmail/core](https://github.com/chatmail/core)，采用 **Mozilla Public License 2.0（MPL-2.0）**，见 `core/LICENSE`。

MPL-2.0 与 AGPL-3.0 兼容：MPL-2.0 允许将 MPL 代码并入更大的作品并按 AGPL-3.0 分发；`core/` 中受 MPL 覆盖的文件本身仍按 MPL-2.0 提供。因此，本仓库整体以 AGPL-3.0 发布不违反 `core/` 子模块的许可证。

## 免责声明

本项目按“现状”提供，不附带任何明示或暗示的担保。使用本软件所产生的风险由使用者自行承担。
