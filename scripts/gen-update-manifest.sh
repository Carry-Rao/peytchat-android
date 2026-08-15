#!/usr/bin/env bash
#
# 生成热更新清单 update.json（用于 GitHub Pages / raw / jsDelivr 静态托管）。
#
# 用法:
#   ./scripts/gen-update-manifest.sh <base_url> <patch.dex> [<patch2.dex> ...]
#
# 示例:
#   ./scripts/gen-update-manifest.sh \
#       https://<你的用户名>.github.io/peytchat-updates \
#       updates/patches/shell_1.0.1.dex \
#       updates/patches/login_1.0.2.dex
#
# 补丁文件名格式: <module>_<version>.dex
#   module: shell | login | chat | account
#   例如: shell_1.0.1.dex  →  { "module": "shell", "version": "1.0.1", ... }
#
# 输出: 默认生成到 ./update.json（可用环境变量 OUT 指定输出路径）。
#
# 发布流程:
#   1. 用 d8 把补丁类打成 dex（见 docs/hot-update.md）
#   2. 运行本脚本生成 update.json
#   3. 把 update.json 和 patches/*.dex 提交推送到 GitHub 仓库
#   4. 客户端「检查更新」填仓库对应的清单地址即可

set -euo pipefail

BASE_URL="${1:?用法: gen-update-manifest.sh <base_url> <patch.dex> [...]}"
shift
OUT="${OUT:-update.json}"

if [ "$#" -lt 1 ]; then
    echo "错误: 至少需要提供一个补丁 dex 文件" >&2
    exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "错误: 需要 python3 计算 md5 并生成 JSON" >&2
    exit 1
fi

python3 - "$BASE_URL" "$OUT" "$@" <<'PY'
import hashlib
import json
import os
import sys

base_url, out, *files = sys.argv[1:]

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

with open(out, "w", encoding="utf-8") as fh:
    json.dump(manifest, fh, ensure_ascii=False, indent=2)
    fh.write("\n")

print(f"已生成 {out}:")
for p in patches:
    print(f"  {p['module']} v{p['version']}")
    print(f"    url : {p['url']}")
    print(f"    md5 : {p['md5']}")
PY
