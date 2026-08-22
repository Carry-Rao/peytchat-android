# 热更新操作手册：改完代码怎么发布

> 适用场景：你改了 App 的代码（尤其是 chatUI 等界面），想知道怎么把改动推到用户端。
> 配套代码见 `docs/hot-update.md`（机制说明）、`patch-sample/`（补丁模板）、`updates/`（待上传文件）。

## 0. 先判断你的改动属于哪一类

| 改动类型 | 举例 | 发布方式 | 用户如何生效 |
| --- | --- | --- | --- |
| 配置 / 行为 / 文案 / 颜色 | 气泡间距、是否显示时间、提示语、按钮行为 | 补丁 dex（现有能力） | 检查更新 + 杀进程重启 |
| 数据层行为 | 发送文本自动加后缀等 | 补丁 dex（`patch/data`，已实现，见 §3.1） | 检查更新 + 杀进程重启 |
| chatUI 结构改动 | `ChatScreen` 布局、组件、交互流程重做 | 补丁 dex（`ChatUiProvider` 扩展点，见 §3.2） | 检查更新 + 杀进程重启 |
| 其他 UI 结构改动 | `ShellScreen`/登录页布局重做 | 发新 APK | 重新安装 |

**关键边界**：补丁 dex 只能提供「基座里不存在的新类 + 干点新事」，**不能**覆盖
基座已编译进 APK 的同名 UI 类（parent-first 类加载）。所以 UI 结构改动目前只能发版。

---

## 1. 发「行为/配置」补丁（现有能力）

### 1.1 写补丁类

模板在 `patch-sample/src/cn/yzjtiantian/android/patch/ShellPatch.java`，照抄改内容即可。

命名规则（客户端与清单脚本都依赖）：

| 项 | 规则 |
| --- | --- |
| 类名 | `cn.yzjtiantian.android.patch.<Module>Patch`（shell/login/chat/account） |
| 文件名 | `<module>_<version>.dex`，如 `chat_1.0.2.dex` |
| 版本号 | **每次递增**，不要覆盖旧文件（便于回滚、避免 CDN 缓存） |
| 可选方法 | `module()` / `version()` / `description()` / `boolean apply(Context)` |

### 1.2 编译打包成 dex

```bash
cd patch-sample
javac -source 8 -target 8 \
    -classpath "$ANDROID_HOME/platforms/android-35/android.jar" \
    -d out src/cn/yzjtiantian/android/patch/*.java
mkdir -p out/dex
"$ANDROID_HOME/build-tools/35.0.0/d8" --output out/dex out/cn/yzjtiantian/android/patch/*.class
mv out/dex/classes.dex ../updates/chat_1.0.2.dex     # 命名 <module>_<version>.dex
```

### 1.3 生成清单

```bash
cd ..
OUT=updates/update.json ./scripts/gen-update-manifest.sh \
    https://peyt.org/peytchat-android-update/ \
    updates/chat_1.0.2.dex
```

脚本自动算 MD5、拼下载 URL。多个补丁可一次传入：`... update.json updates/shell_1.0.2.dex updates/login_1.0.2.dex`

### 1.4 上传服务器

把 `updates/update.json` 和新的 dex 上传到 `https://peyt.org/peytchat-android-update/`：

```
https://peyt.org/peytchat-android-update/update.json
https://peyt.org/peytchat-android-update/chat_1.0.2.dex
```

上传后验证：

```bash
curl https://peyt.org/peytchat-android-update/update.json   # 应返回 JSON，且 md5 与文件一致
```

### 1.5 客户端生效（两步）

1. 用户打开 App → 设置 → 检查更新 → 立即检查（提示「已下载并校验」）；
2. **完全杀掉 App 进程后重启** → 补丁在下次启动时自动加载；
3. 验证：设置 → 检查更新，出现「已加载补丁：chat v1.0.2 — …」。

---

## 2. 发「UI 结构」改动（现状：发新 APK）

改了 `ChatScreen.kt` / `ShellScreen.kt` 等 UI 代码，热更新管不了，按正常流程发版：

```bash
# 构建
./gradlew :app:assembleDebug
# 产物
app/build/outputs/apk/debug/app-debug.apk
# 真机安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

发布渠道按你现有的分发方式（测试包/应用市场/直链）走。

> 提示：UI 发版不影响已下发的行为补丁——补丁文件在服务器上、记录在
> SharedPreferences 里，新版 APK 启动时仍会加载。除非你主动改清单或清数据。

---

## 3. 行为热更新（TextSendHook 数据层钩子 + ChatUiProvider 界面，均已实现）

### 3.1 数据层行为热更新（示例：patch/data）

改「发送出去的内容」这类数据层行为不用发版：

- 稳定契约：`libs/core/.../core/TextSendHook.kt`（注册键 `text_send_hook`）
- 基座消费：`PeytRepository.sendMessage` 发送文本前查
  `ModuleManager.getPatchService("text_send_hook") as? TextSendHook`
- 补丁模块：`patch/data`（入口类 `DataPatch` + `DataTextHook` + `packagePatchDex` 任务）

每次改数据层行为的操作流程：

```bash
# 1. 在 patch/data/src/main/java/cn/yzjtiantian/android/patch/ 里改/新增
#    TextSendHook 实现（新类名），或直接改 DataTextHook 的 transform()

# 2. 递增版本号：patch/data/build.gradle.kts 里的 patchVersion（如 0.0.1 → 0.0.2）

# 3. 构建补丁 dex（输出到 updates/data_<version>.dex）
./gradlew :patch:data:packagePatchDex

# 4. 生成清单
OUT=updates/update.json ./scripts/gen-update-manifest.sh \
    https://peyt.org/peytchat-android-update/ \
    updates/data_0.0.2.dex

# 5. 把 updates/data_0.0.2.dex 和 update.json 上传到服务器
```

用户侧生效：检查更新 → 杀进程重启 → 之后发出的每条文本都带补丁效果
（示例 `DataTextHook` 给文本追加「（数据层补丁 v0.0.1）」后缀）。

注意：钩子只作用于用户文本消息，不影响 card.*/project.invite 信封协议。

### 3.2 聊天界面热更新（ChatUiProvider）

机制保留在基座（`libs/patch-api` + `ShellScreen` 分发，回退内置 `ChatScreen`），
需要时新建补丁模块（复制 `patch/data` 的构建骨架，加 compose 依赖）实现
`ChatUiProvider` 即可；操作流程与 3.1 相同（`./gradlew :patch:<name>:packagePatchDex`）。

稳定契约（`ChatUiProvider` / `TextSendHook`）签名发布后不要随意改（旧基座加载新补丁会失败）。

---

## 4. 常用命令速查

```bash
# 计算 dex 的 md5（应与清单一致）
md5sum updates/chat_1.0.2.dex

# 本地联调（手机与电脑同一网络）
cd updates && python3 -m http.server 8000
# 清单地址填: http://<电脑IP>:8000/update.json
# （注意：http 明文在正式 App 里默认被禁，仅联调用；正式用 https://peyt.org）

# 验证服务器文件可访问
curl -I https://peyt.org/peytchat-android-update/shell_1.0.1.dex

# 回滚：把清单里的版本改回旧版本号（如 1.0.1），用户再次检查更新即会下载旧补丁
```

## 5. 常见问题

- **检查更新提示「失败 1 个」**：多半是 md5 不匹配或 dex 损坏，`md5sum` 核对后重新生成清单。
- **下载了但重启后没有「已加载补丁」**：确认 dex 里入口类名是
  `cn.yzjtiantian.android.patch.<Module>Patch`（大小写敏感），且文件名/清单 module 一致。
- **补丁不生效（老功能还在）**：补丁类与基座类重名了。补丁只能新增类，不能覆盖基座类。
- **用户版本混乱**：补丁版本号必须单调递增；回滚请改清单版本而非覆盖旧 dex。
