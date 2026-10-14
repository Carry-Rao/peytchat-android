#!/usr/bin/env bash
#
# 生成热更新清单 update.json（用于 GitHub Pages / raw / jsDelivr 静态托管）。
#
# 用法:
#   ./scripts/gen-update-manifest.sh [--apk <apk文件> --apk-version-code <int> \
#       --apk-version-name <str> [--apk-force-update] [--apk-release-notes <文件>]] \
#       <base_url> <patch.dex> [<patch2.dex> ...]
#
# 示例（仅补丁）:
#   ./scripts/gen-update-manifest.sh \
#       https://<你的用户名>.github.io/peytchat-updates \
#       updates/patches/shell_1.0.1.dex \
#       updates/patches/login_1.0.2.dex
#
# 示例（附带 APK 大版本）:
#   ./scripts/gen-update-manifest.sh \
#       --apk app/build/outputs/apk/release/app-release.apk \
#       --apk-version-code 2 \
#       --apk-version-name 0.2.0 \
#       --apk-release-notes release-notes.txt \
#       https://peyt.org/peytchat-android-update/ \
#       updates/patches/shell_1.0.1.dex
#
# 补丁文件名格式: <module>_<version>.dex
#   module: shell | login | chat | account
#   例如: shell_1.0.1.dex  →  { "module": "shell", "version": "1.0.1", ... }
#
# APK 说明:
#   --apk 指定 APK 文件后，会自动计算 MD5 和字节数写入顶层 `apk` 字段；
#   没有 --apk 时不输出 `apk` 字段（向后兼容，客户端视为无 APK 更新）。
#   versionCode 必须比线上已发布版本严格 +1，否则客户端不会提示更新。
#
# 输出: 默认生成到 ./update.json（可用环境变量 OUT 指定输出路径）。
#
# 发布流程:
#   1. ./gradlew :app:assembleRelease（签名必须和线上一致，否则无法覆盖安装）
#   2. 用 d8 把补丁类打成 dex（见 docs/hot-update.md）
#   3. 运行本脚本生成 update.json
#   4. 把 update.json、patches/*.dex 和 APK 上传到静态服务器
#   5. 客户端「检查更新」填仓库对应的清单地址即可

set -euo pipefail

APK_PATH=""
APK_VERSION_CODE=""
APK_VERSION_NAME=""
APK_FORCE_UPDATE=false
APK_RELEASE_NOTES_FILE=""
POSITIONAL=()

while [ $# -gt 0 ]; do
    case "$1" in
        --apk)               APK_PATH="$2"; shift 2 ;;
        --apk-version-code)  APK_VERSION_CODE="$2"; shift 2 ;;
        --apk-version-name)  APK_VERSION_NAME="$2"; shift 2 ;;
        --apk-force-update)  APK_FORCE_UPDATE=true; shift ;;
        --apk-release-notes) APK_RELEASE_NOTES_FILE="$2"; shift 2 ;;
        --)                  shift; while [ $# -gt 0 ]; do POSITIONAL+=("$1"); shift; done; break ;;
        -*)                  echo "错误: 未知参数: $1" >&2; exit 1 ;;
        *)                   POSITIONAL+=("$1"); shift ;;
    esac
done

if [ "${#POSITIONAL[@]}" -lt 1 ]; then
    echo "用法: gen-update-manifest.sh [--apk <apk> ...] <base_url> <patch.dex> [...]" >&2
    exit 1
fi

BASE_URL="${POSITIONAL[0]}"
PATCHES=("${POSITIONAL[@]:1}")
OUT="${OUT:-update.json}"

if [ -n "$APK_RELEASE_NOTES_FILE" ] && [ ! -f "$APK_RELEASE_NOTES_FILE" ]; then
    echo "错误: 更新说明文件不存在: $APK_RELEASE_NOTES_FILE" >&2
    exit 1
fi

if [ -n "$APK_PATH" ]; then
    if [ ! -f "$APK_PATH" ]; then
        echo "错误: APK 文件不存在: $APK_PATH" >&2
        exit 1
    fi
    if [ -z "$APK_VERSION_CODE" ] || [ -z "$APK_VERSION_NAME" ]; then
        echo "错误: 使用 --apk 时必须同时提供 --apk-version-code 和 --apk-version-name" >&2
        exit 1
    fi
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "错误: 需要 python3 计算 md5 并生成 JSON" >&2
    exit 1
fi

python3 - "$BASE_URL" "$OUT" "$APK_PATH" "$APK_VERSION_CODE" "$APK_VERSION_NAME" \
    "$APK_FORCE_UPDATE" "$APK_RELEASE_NOTES_FILE" "${PATCHES[@]}" <<'PY'
import hashlib
import json
import os
import sys

base_url, out, apk_path, apk_vcode, apk_vname, apk_force, apk_notes_file, *files = sys.argv[1:]

patches = []
for f in files:
    name = os.path.basename(f)
    if not os.path.isfile(f):
        print(f"错误: 文件不存在: {f}", file=sys.stderr)
        sys.exit(1)
    stem = name[:-4] if name.endswith(".dex") else name
    if "_" not in stem:
        print(f"错误: 文件名必须是 <module>_<version>.dex，得到: {name}", file=sys.stderr)
        sys.exit(1)
    module, version = stem.rsplit("_", 1)
    md5 = hashlib.md5(open(f, "rb").read()).hexdigest()
    url = base_url.rstrip("/") + "/" + name
    patches.append({"module": module, "version": version, "url": url, "md5": md5})

manifest = {"appVersion": "0.1.0", "patches": patches}

if apk_path:
    apk_url = base_url.rstrip("/") + "/" + os.path.basename(apk_path)
    apk_md5 = hashlib.md5(open(apk_path, "rb").read()).hexdigest()
    apk_size = os.path.getsize(apk_path)
    apk = {
        "versionCode": int(apk_vcode),
        "versionName": apk_vname,
        "url": apk_url,
        "md5": apk_md5,
        "size": apk_size,
    }
    if apk_notes_file:
        with open(apk_notes_file, "r", encoding="utf-8") as fh:
            apk["releaseNotes"] = fh.read()
    if apk_force == "true":
        apk["forceUpdate"] = True
    # appVersion 仅作展示，跟随最新 APK 版本名
    manifest["appVersion"] = apk_vname
    manifest["apk"] = apk

with open(out, "w", encoding="utf-8") as fh:
    json.dump(manifest, fh, ensure_ascii=False, indent=2)
    fh.write("\n")

print(f"已生成 {out}:")
if apk_path:
    a = manifest["apk"]
    print(f"  apk  : v{a['versionName']} (versionCode={a['versionCode']})")
    print(f"    url : {a['url']}")
    print(f"    md5 : {a['md5']}")
    print(f"    size: {a['size']} bytes")
for p in patches:
    print(f"  {p['module']} v{p['version']}")
    print(f"    url : {p['url']}")
    print(f"    md5 : {p['md5']}")
PY
