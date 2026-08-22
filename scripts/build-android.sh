#!/usr/bin/env bash
# Build the Rust JNI bridge and copy the .so into the Android app's jniLibs.
#
# Usage:
#   ./scripts/build-android.sh [release]
#   release: build with --release (smaller .so; requires core release lib)
set -euo pipefail

cd "$(dirname "$0")/.."

PROFILE="${1:-debug}"
NDK_ABI=arm64-v8a
CARGO_ARGS=()
OUT_DIR=rust/target/aarch64-linux-android

if [[ "$PROFILE" == "release" ]]; then
    CARGO_ARGS+=(--release)
    OUT_DIR="$OUT_DIR/release"
    CORE_PROFILE="release"
else
    OUT_DIR="$OUT_DIR/debug"
    CORE_PROFILE="debug"
fi

# Build the deltachat core static lib if missing for this profile.
if [[ ! -f "core/target/aarch64-linux-android/$CORE_PROFILE/libdeltachat.a" ]]; then
    echo ">> building deltachat_ffi ($CORE_PROFILE)..."
    cargo ndk -t "$NDK_ABI" "${CARGO_ARGS[@]}" build -p deltachat_ffi
fi

echo ">> building peytchat-bridge ($PROFILE)..."
(cd rust && cargo ndk -t "$NDK_ABI" "${CARGO_ARGS[@]}" -o /tmp/peytchat-jnilibs build -p peytchat-bridge)

DEST="app/src/main/jniLibs/$NDK_ABI"
mkdir -p "$DEST"
cp "/tmp/peytchat-jnilibs/$NDK_ABI/libpeytchat_bridge.so" "$DEST/"

echo ">> copied to $DEST/libpeytchat_bridge.so"
ls -lh "$DEST/libpeytchat_bridge.so"
