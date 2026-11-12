#!/usr/bin/env bash
# 生成 adb 认证用的 RSA 密钥对（PKCS#8 私钥 + adb 格式公钥）。
# 密钥是本地调试身份，不入库；每次构建时若缺失会自动生成。
#   bash scripts/genkey.sh [输出目录] [用户名]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/mousepad2/assets}"
NAME="${2:-remotemouse@tv}"
PY="${PYTHON:-python}"

mkdir -p "$OUT"
if [ -f "$OUT/adbkey" ] && [ -f "$OUT/adbkey.pub" ]; then
  echo "密钥已存在：$OUT/adbkey"
  exit 0
fi

command -v openssl >/dev/null 2>&1 || { echo "缺少 openssl"; exit 1; }

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$OUT/adbkey" 2>/dev/null
"$PY" "$ROOT/scripts/adbkey_pub.py" "$OUT/adbkey" "$NAME" > "$OUT/adbkey.pub"
echo "已生成：$OUT/adbkey"
echo "提示：首次连接电视时，电视会弹「允许 USB 调试」，勾选「一律允许」即可。"
