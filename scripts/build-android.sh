#!/usr/bin/env bash
#
# 用法:
#   ./scripts/build-android.sh [release|debug]
#   release: 构建 release 版本 (默认 debug)

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
NDK_ABI="arm64-v8a"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CARGO_ARGS=()

info "检查必要的工具..."
check_command "cargo"
check_command "adb"
check_command "zip" || warn "zip 命令未安装，部分验证功能可能不可用"

if [[ "$PROFILE" == "release" ]]; then
    CARGO_ARGS+=(--release)
    RUST_PROFILE="release"
    info "构建模式: RELEASE"
else
    RUST_PROFILE="debug"
    info "构建模式: DEBUG"
fi

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
            if [[ -n "$latest" && -x "${latest}toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy" ]]; then
                echo "${latest%/}"
                return 0
            fi
            # 有些路径本身就是 NDK 根
            if [[ -x "$base/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy" ]]; then
                echo "$base"
                return 0
            fi
        fi
    done

    return 1
}

if NDK_PATH=$(detect_ndk); then
    export ANDROID_NDK_HOME="$NDK_PATH"
    info "NDK: $ANDROID_NDK_HOME"
else
    warn "未自动找到 Android NDK"
    read -r -p "请输入 Android NDK 路径 (例如 ~/Android/Sdk/ndk/30.0.16248370): " NDK_INPUT
    # 展开 ~
    NDK_INPUT="${NDK_INPUT/#\~/$HOME}"
    if [[ -d "$NDK_INPUT" && -x "$NDK_INPUT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy" ]]; then
        export ANDROID_NDK_HOME="$NDK_INPUT"
        info "使用 NDK: $ANDROID_NDK_HOME"
    else
        error "无效的 NDK 路径: $NDK_INPUT"
        error "需要包含 toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy"
        exit 1
    fi
fi

CORE_DIR="$PROJECT_ROOT/core"
RUST_DIR="$PROJECT_ROOT/rust"
JNI_LIBS_DIR="$PROJECT_ROOT/app/src/main/jniLibs/$NDK_ABI"
TARGET_DIR="$PROJECT_ROOT/rust/target/aarch64-linux-android/$RUST_PROFILE"

info "项目根目录: $PROJECT_ROOT"
info "NDK ABI: $NDK_ABI"

info "步骤 1/5: 检查 deltachat_ffi..."

if [[ ! -d "$CORE_DIR" ]]; then
    error "core 目录不存在: $CORE_DIR"
    error "请确保 deltachat-core-rust 子模块已初始化"
    exit 1
fi

cd "$CORE_DIR"
if [[ ! -f "Cargo.toml" ]]; then
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

info "构建 deltachat_ffi ($RUST_PROFILE)..."
if cargo ndk -t "$NDK_ABI" build "${CARGO_ARGS[@]}" -p deltachat_ffi; then
    success "deltachat_ffi 构建成功"
else
    error "deltachat_ffi 构建失败"
    exit 1
fi

# ---------- 检查并生成 patched 库 ----------
CORE_LIB="$CORE_DIR/target/aarch64-linux-android/$RUST_PROFILE/libdeltachat.a"
PATCHED_LIB="$CORE_DIR/target/aarch64-linux-android/$RUST_PROFILE/libdeltachat_patched.a"

if [[ ! -f "$CORE_LIB" ]]; then
    error "deltachat 静态库未找到: $CORE_LIB"
    exit 1
fi

success "deltachat 静态库: $CORE_LIB"
ls -lh "$CORE_LIB"

NEED_PATCH=0
if [[ ! -f "$PATCHED_LIB" ]]; then
    info "patched 库不存在，需要生成"
    NEED_PATCH=1
elif [[ "$CORE_LIB" -nt "$PATCHED_LIB" ]]; then
    info "libdeltachat.a 比 patched 库新，需要重新生成"
    NEED_PATCH=1
else
    info "patched 库已是最新，跳过"
fi

if [[ "$NEED_PATCH" -eq 1 ]]; then
    if ! command -v lib-patcher &> /dev/null; then
        error "lib-patcher 未安装。运行: cargo install lib-patcher"
        exit 1
    fi
    info "给 libdeltachat.a 打补丁..."
    if lib-patcher \
        --input "$CORE_LIB" \
        --output "$PATCHED_LIB" \
        --keep-prefix "dc_" \
        --triplet aarch64-linux-android; then
        success "补丁完成: $PATCHED_LIB"
        ls -lh "$PATCHED_LIB"
    else
        error "lib-patcher 失败"
        exit 1
    fi
fi

info "步骤 2/5: 构建 peytchat-bridge..."

cd "$RUST_DIR"
if [[ ! -f "Cargo.toml" ]]; then
    error "rust/Cargo.toml 不存在"
    exit 1
fi

info "构建 peytchat-bridge ($RUST_PROFILE)..."
if cargo ndk -t "$NDK_ABI" build "${CARGO_ARGS[@]}" -p peytchat-bridge; then
    success "peytchat-bridge 构建成功"
else
    error "peytchat-bridge 构建失败"
    exit 1
fi

info "步骤 3/5: 查找并复制 .so 文件..."

SO_FILE=""
POSSIBLE_PATHS=(
    "$RUST_DIR/target/aarch64-linux-android/$RUST_PROFILE/libpeytchat_bridge.so"
    "$RUST_DIR/target/$NDK_ABI/$RUST_PROFILE/libpeytchat_bridge.so"
    "/tmp/peytchat-jnilibs/$NDK_ABI/libpeytchat_bridge.so"
)

for path in "${POSSIBLE_PATHS[@]}"; do
    if [[ -f "$path" ]]; then
        SO_FILE="$path"
        info "找到 .so 文件: $path"
        break
    fi
done

if [[ -z "$SO_FILE" ]]; then
    error "找不到 libpeytchat_bridge.so 文件"
    error "搜索路径:"
    for path in "${POSSIBLE_PATHS[@]}"; do
        echo "  - $path"
    done
    exit 1
fi

info "步骤 4/5: 复制到 jniLibs..."

mkdir -p "$JNI_LIBS_DIR"

if cp "$SO_FILE" "$JNI_LIBS_DIR/libpeytchat_bridge.so"; then
    success "复制成功: $SO_FILE -> $JNI_LIBS_DIR/"
    ls -lh "$JNI_LIBS_DIR/libpeytchat_bridge.so"
else
    error "复制失败"
    exit 1
fi

info "步骤 5/5: 验证构建..."

cd "$PROJECT_ROOT"

if [[ -f "$JNI_LIBS_DIR/libpeytchat_bridge.so" ]]; then
    SO_SIZE=$(du -h "$JNI_LIBS_DIR/libpeytchat_bridge.so" | cut -f1)
    success ".so 文件已就位 (大小: $SO_SIZE)"
else
    error ".so 文件未在预期位置: $JNI_LIBS_DIR/"
    exit 1
fi

#info "清理旧的 APK..."
#./gradlew clean

info "构建 APK ($PROFILE)..."
if ./gradlew assemble${PROFILE^}; then
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
echo "  - 架构: $NDK_ABI"
echo "  - NDK: $ANDROID_NDK_HOME"
echo "  - .so 文件: $JNI_LIBS_DIR/libpeytchat_bridge.so"
echo "  - APK 文件: $APK_PATH"
echo ""
echo "下一步:"
echo "  1. 安装: adb install $APK_PATH"
echo "  2. 查看日志: adb logcat | grep -E \"cn.yzjtiantian.android|deltachat|rust\""
echo "  3. 重新构建: ./scripts/build-android.sh [debug|release]"
echo "=========================================="