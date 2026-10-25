#!/usr/bin/env bash
#
# 用法:
#   ./scripts/build-android.sh [release|debug] [abi1,abi2,...]
#     release|debug   构建类型，默认 debug
#     abi1,abi2,...   逗号分隔的目标 ABI，默认 arm64-v8a
#                     可选: arm64-v8a | armeabi-v7a | x86 | x86_64
#
#   示例:
#     ./scripts/build-android.sh release arm64-v8a,x86_64   # 真机 + x86_64 模拟器
#     PEYT_ABIS=x86_64 ./scripts/build-android.sh debug      # 仅 x86_64 模拟器
#
# 说明:
#   对每个 ABI 依次执行：编译 deltachat_ffi → 用 lib-patcher 生成 patched 静态库
#   → 编译 peytchat-bridge → 复制 libpeytchat_bridge.so 到 jniLibs/<abi>/。
#   app/build.gradle.kts 会按 jniLibs 中实际存在的 ABI 限定 abiFilters，二者需一致；
#   否则 APK 会声明未构建的架构，导致对应设备上「安装成功但启动即崩」。

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

check_command() {
    if ! command -v "$1" &> /dev/null; then
        error "找不到命令: $1"
        exit 1
    fi
}

PROFILE="${1:-debug}"
ABI_LIST="${2:-${PEYT_ABIS:-arm64-v8a}}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CARGO_ARGS=()

IFS=',' read -r -a ABIS <<< "$ABI_LIST"
if [[ "${#ABIS[@]}" -eq 0 || -z "${ABIS[0]}" ]]; then
    error "目标 ABI 列表为空"
    exit 1
fi

info "检查必要的工具..."
check_command "cargo"
if ! command -v adb &> /dev/null; then
    warn "adb 未安装，将跳过安装到设备（CI 环境正常）"
fi
if ! command -v zip &> /dev/null; then
    warn "zip 命令未安装，部分验证功能可能不可用"
fi

if [[ "$PROFILE" == "release" ]]; then
    CARGO_ARGS+=(--release)
    RUST_PROFILE="release"
    info "构建模式: RELEASE"
else
    RUST_PROFILE="debug"
    info "构建模式: DEBUG"
fi

# ---------- NDK host 预编译目录探测 ----------
# 返回 NDK 提供的 host 目录名：linux-x86_64 或 darwin-x86_64
# 依据实际目录存在性判断，而非 uname，避免 CI/容器环境误判
detect_ndk_host() {
    local ndk_root="$1"
    local prebuilt="$ndk_root/toolchains/llvm/prebuilt"
    local host
    for host in linux-x86_64 darwin-x86_64; do
        if [[ -d "$prebuilt/$host" ]]; then
            echo "$host"
            return 0
        fi
    done
    return 1
}

# 校验给定 NDK 路径是否可用：要求 host 目录存在且 llvm-objcopy 可执行
is_valid_ndk() {
    local ndk_root="$1"
    [[ -d "$ndk_root" ]] || return 1
    local host
    host=$(detect_ndk_host "$ndk_root") || return 1
    [[ -x "$ndk_root/toolchains/llvm/prebuilt/$host/bin/llvm-objcopy" ]]
}

# ---------- NDK 路径探测 ----------
detect_ndk() {
    # 1) 已有环境变量
    for var in ANDROID_NDK_HOME ANDROID_NDK_ROOT NDK_HOME; do
        if [[ -n "${!var:-}" && -d "${!var}" ]]; then
            echo "${!var}"
            return 0
        fi
    done

    # 2) 常见 SDK 路径
    local candidates=(
        "$HOME/Android/Sdk/ndk"
        "$HOME/Library/Android/sdk/ndk"      # macOS
        "/opt/android-sdk/ndk"
        "/usr/lib/android-ndk"
        "/opt/android-studio/plugins/android-ndk"
    )
    for base in "${candidates[@]}"; do
        if [[ -d "$base" ]]; then
            # 取版本号最大的一个
            local latest
            latest=$(ls -1d "$base"/*/ 2>/dev/null | sort -V | tail -n1)
            if [[ -n "$latest" && -n "$(detect_ndk_host "${latest%/}" 2>/dev/null)" ]]; then
                echo "${latest%/}"
                return 0
            fi
            # 有些路径本身就是 NDK 根
            if [[ -n "$(detect_ndk_host "$base" 2>/dev/null)" ]]; then
                echo "$base"
                return 0
            fi
        fi
    done

    return 1
}

if NDK_PATH=$(detect_ndk); then
    export ANDROID_NDK_HOME="$NDK_PATH"
    NDK_HOST="$(detect_ndk_host "$ANDROID_NDK_HOME")"
    export NDK_HOST
    info "NDK: $ANDROID_NDK_HOME (host: $NDK_HOST)"
else
    warn "未自动找到 Android NDK"
    read -r -p "请输入 Android NDK 路径 (例如 ~/Android/Sdk/ndk/30.0.16248370): " NDK_INPUT
    # 展开 ~
    NDK_INPUT="${NDK_INPUT/#\~/$HOME}"
    if is_valid_ndk "$NDK_INPUT"; then
        export ANDROID_NDK_HOME="$NDK_INPUT"
        NDK_HOST="$(detect_ndk_host "$ANDROID_NDK_HOME")"
        export NDK_HOST
        info "使用 NDK: $ANDROID_NDK_HOME (host: $NDK_HOST)"
    else
        error "无效的 NDK 路径: $NDK_INPUT"
        error "需要存在 toolchains/llvm/prebuilt/linux-x86_64 或 darwin-x86_64，"
        error "且其中包含可执行的 bin/llvm-objcopy"
        exit 1
    fi
fi

CORE_DIR="$PROJECT_ROOT/core"
RUST_DIR="$PROJECT_ROOT/rust"

info "项目根目录: $PROJECT_ROOT"
info "目标 ABI: ${ABIS[*]}"

info "步骤 1/3: 检查 deltachat_ffi 与 core 子模块..."

if [[ ! -d "$CORE_DIR" ]]; then
    error "core 目录不存在: $CORE_DIR"
    error "请确保 deltachat-core-rust 子模块已初始化"
    exit 1
fi

if [[ ! -f "$CORE_DIR/Cargo.toml" ]]; then
    error "core/Cargo.toml 不存在"
    exit 1
fi

# ---------- 应用 core 子模块本地补丁 ----------
# patches/core/*.patch 以 git diff 格式存放对上游 deltachat core 的定制
# (当前: yzjtiantian.cn DNS preload 修复登录超时)。
# 幂等：已应用则跳过；未应用则 apply；无法干净应用则中止构建。
PATCH_DIR="$PROJECT_ROOT/patches/core"
if [[ -d "$PATCH_DIR" ]]; then
    shopt -s nullglob
    for patch_file in "$PATCH_DIR"/*.patch; do
        patch_name="$(basename "$patch_file")"
        if git -C "$CORE_DIR" apply --reverse --check "$patch_file" 2>/dev/null; then
            info "补丁已应用，跳过: $patch_name"
        elif git -C "$CORE_DIR" apply --check "$patch_file" 2>/dev/null; then
            info "应用 core 补丁: $patch_name"
            git -C "$CORE_DIR" apply "$patch_file"
        else
            error "core 补丁无法干净应用: $patch_name"
            error "请检查 core 子模块状态 (git -C core status)，或手动处理后重试"
            exit 1
        fi
    done
fi

# ---------- ABI <-> Rust target triple ----------
abi_to_triple() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "armv7-linux-androideabi" ;;
        x86)         echo "i686-linux-android" ;;
        x86_64)      echo "x86_64-linux-android" ;;
        *) return 1 ;;
    esac
}

# ---------- 单个 ABI 的完整构建 ----------
build_abi() {
    local abi="$1"
    local triple
    if ! triple=$(abi_to_triple "$abi"); then
        error "不支持的 ABI: $abi (可选: arm64-v8a, armeabi-v7a, x86, x86_64)"
        return 1
    fi

    local core_lib="$CORE_DIR/target/$triple/$RUST_PROFILE/libdeltachat.a"
    local patched_lib="$CORE_DIR/target/$triple/$RUST_PROFILE/libdeltachat_patched.a"
    local jni_dir="$PROJECT_ROOT/app/src/main/jniLibs/$abi"

    info "========== ABI: $abi ($triple) =========="

    info "[$abi] 编译 deltachat_ffi ($RUST_PROFILE)..."
    if ! ( cd "$CORE_DIR" && cargo ndk -t "$abi" build "${CARGO_ARGS[@]}" -p deltachat_ffi ); then
        error "[$abi] deltachat_ffi 构建失败"
        return 1
    fi
    success "[$abi] deltachat_ffi 构建成功"

    if [[ ! -f "$core_lib" ]]; then
        error "[$abi] 未找到静态库: $core_lib"
        return 1
    fi
    ls -lh "$core_lib"

    # 生成 patched 静态库（目标不存在或比源旧时重新生成）
    local need_patch=0
    if [[ ! -f "$patched_lib" || "$core_lib" -nt "$patched_lib" ]]; then
        need_patch=1
    fi
    if [[ "$need_patch" -eq 1 ]]; then
        if ! command -v lib-patcher &> /dev/null; then
            error "lib-patcher 未安装。运行: cargo install lib-patcher"
            return 1
        fi
        info "[$abi] 用 lib-patcher 生成 patched 静态库..."
        if lib-patcher \
            --input "$core_lib" \
            --output "$patched_lib" \
            --keep-prefix "dc_" \
            --triplet "$triple"; then
            success "[$abi] 补丁完成: $patched_lib"
            ls -lh "$patched_lib"
        else
            error "[$abi] lib-patcher 失败"
            return 1
        fi
    else
        info "[$abi] patched 库已是最新，跳过"
    fi

    info "[$abi] 编译 peytchat-bridge ($RUST_PROFILE)..."
    if ! ( cd "$RUST_DIR" && cargo ndk -t "$abi" build "${CARGO_ARGS[@]}" -p peytchat-bridge ); then
        error "[$abi] peytchat-bridge 构建失败"
        return 1
    fi
    success "[$abi] peytchat-bridge 构建成功"

    local so_file=""
    local candidates=(
        "$RUST_DIR/target/$triple/$RUST_PROFILE/libpeytchat_bridge.so"
        "$RUST_DIR/target/$abi/$RUST_PROFILE/libpeytchat_bridge.so"
        "/tmp/peytchat-jnilibs/$abi/libpeytchat_bridge.so"
    )
    for path in "${candidates[@]}"; do
        if [[ -f "$path" ]]; then
            so_file="$path"
            break
        fi
    done

    if [[ -z "$so_file" ]]; then
        error "[$abi] 找不到 libpeytchat_bridge.so，搜索路径:"
        for path in "${candidates[@]}"; do
            echo "  - $path"
        done
        return 1
    fi

    mkdir -p "$jni_dir"
    if cp "$so_file" "$jni_dir/libpeytchat_bridge.so"; then
        success "[$abi] .so 已复制: $so_file -> $jni_dir/"
        ls -lh "$jni_dir/libpeytchat_bridge.so"
    else
        error "[$abi] 复制 .so 失败"
        return 1
    fi
}

if [[ ! -f "$RUST_DIR/Cargo.toml" ]]; then
    error "rust/Cargo.toml 不存在"
    exit 1
fi

info "步骤 2/3: 逐 ABI 构建 native 库..."
for abi in "${ABIS[@]}"; do
    build_abi "$abi" || exit 1
done

info "步骤 3/3: 打包 APK..."
cd "$PROJECT_ROOT"

# 兼容 macOS 自带 Bash 3.2：避免使用 ${var^}
case "$PROFILE" in
    release) GRADLE_TASK="assembleRelease" ;;
    debug)   GRADLE_TASK="assembleDebug" ;;
    *)
        error "未知构建类型: $PROFILE（可选: debug | release）"
        exit 1
        ;;
esac

if ./gradlew "$GRADLE_TASK"; then
    success "APK 构建成功"
else
    error "APK 构建失败"
    exit 1
fi

APK_PATH="app/build/outputs/apk/$PROFILE/app-$PROFILE.apk"
if [[ -f "$APK_PATH" ]]; then
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    success "APK 生成: $APK_PATH (大小: $APK_SIZE)"

    if command -v unzip &> /dev/null; then
        if unzip -l "$APK_PATH" | grep -q "libpeytchat_bridge.so"; then
            success "APK 包含 libpeytchat_bridge.so"
            unzip -l "$APK_PATH" | grep "libpeytchat_bridge.so"
        else
            warn "APK 可能不包含 libpeytchat_bridge.so"
            warn "请检查 APK 内容: unzip -l $APK_PATH | grep '\.so'"
        fi
    fi
else
    error "APK 未生成: $APK_PATH"
    exit 1
fi

info "尝试安装到设备..."
if adb devices | grep -q "device$"; then
    if adb install -r "$APK_PATH"; then
        success "APK 安装成功"

        PACKAGE=$(grep "applicationId" app/build.gradle.kts | sed 's/.*= *"\(.*\)"/\1/')
        if [[ -n "$PACKAGE" ]]; then
            info "启动应用: $PACKAGE"
            adb shell monkey -p "$PACKAGE" 1 || warn "无法自动启动应用"
        fi
    else
        warn "APK 安装失败，请检查设备连接"
    fi
else
    warn "没有连接的 Android 设备，跳过安装"
    info "可以手动安装: adb install $APK_PATH"
fi

echo ""
echo "=========================================="
success "构建完成！"
echo "=========================================="
echo "构建信息:"
echo "  - 模式: $PROFILE"
echo "  - 架构: ${ABIS[*]}"
echo "  - NDK: $ANDROID_NDK_HOME (host: $NDK_HOST)"
for abi in "${ABIS[@]}"; do
    echo "  - .so 文件: $PROJECT_ROOT/app/src/main/jniLibs/$abi/libpeytchat_bridge.so"
done
echo "  - APK 文件: $APK_PATH"
echo ""
echo "下一步:"
echo "  1. 安装: adb install $APK_PATH"
echo "  2. 查看日志: adb logcat | grep -E \"cn.yzjtiantian.android|deltachat|rust\""
echo "  3. 重新构建: ./scripts/build-android.sh [debug|release] [abi1,abi2,...]"
echo "=========================================="