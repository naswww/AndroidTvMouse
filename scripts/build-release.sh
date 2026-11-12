#!/usr/bin/env bash
# 构建发布包：使用独立 adb 密钥，并做隐私校验。
# 发布密钥固定存放在 build/release-key/（已被 .gitignore 忽略），多次发版沿用同一把，
# 避免用户每升级一次就要在电视上重新授权。
#
#   bash scripts/build-release.sh [版本名] [版本码] [输出名]
# 例：bash scripts/build-release.sh 2.1.1 13 tvmouse-v2.1.1-release
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERNAME="${1:-2.1.1}"
VERCODE="${2:-13}"
OUTNAME="${3:-tvmouse-v$VERNAME-release}"
SRC="$ROOT/mousepad2"
REL_ASSETS="$ROOT/build/release-assets"
REL_KEY="$ROOT/build/release-key"
KEY_NAME="tvmouse@release"

# 本地隐私词表（不入库）：scripts/privacy.env 里写 PRIVACY_NAMES=xxx,yyy
[ -f "$ROOT/scripts/privacy.env" ] && . "$ROOT/scripts/privacy.env"
PRIVACY_NAMES="${PRIVACY_NAMES:-}"
PRIVACY_NAME_RE="$(echo "$PRIVACY_NAMES" | tr ',' '|')"
MAC_RE='([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}'
PRIVACY_RE="192\\.168\\.[0-9]+\\.[0-9]+|${MAC_RE}"
if [ -n "$PRIVACY_NAME_RE" ]; then
  PRIVACY_RE="${PRIVACY_NAME_RE}|${PRIVACY_RE}"
fi

echo "[release] 准备发布资源目录（使用独立密钥）"
rm -rf "$REL_ASSETS"
mkdir -p "$REL_ASSETS"
for f in "$SRC"/assets/*; do
  [ -f "$f" ] || continue
  case "$(basename "$f")" in
    adbkey|adbkey.pub) continue ;;
  esac
  cp -f "$f" "$REL_ASSETS/"
done

echo "[release] 准备独立发布密钥（$KEY_NAME）"
mkdir -p "$REL_KEY"
if [ ! -f "$REL_KEY/adbkey" ] || [ ! -f "$REL_KEY/adbkey.pub" ]; then
  bash "$ROOT/scripts/genkey.sh" "$REL_KEY" "$KEY_NAME"
fi
cp -f "$REL_KEY/adbkey" "$REL_KEY/adbkey.pub" "$REL_ASSETS/"

# 构建前断言：发布密钥必须是独立密钥
if [ -f "$SRC/assets/adbkey" ] && cmp -s "$REL_ASSETS/adbkey" "$SRC/assets/adbkey"; then
  echo "[release] 错误：发布密钥与已有密钥相同，拒绝构建" >&2
  exit 1
fi
if [ -n "$PRIVACY_NAME_RE" ] && grep -qiE "$PRIVACY_NAME_RE" "$REL_ASSETS/adbkey.pub"; then
  echo "[release] 错误：发布公钥含个人标识" >&2
  exit 1
fi

echo "[release] 开始构建"
ASSETS_DIR="$REL_ASSETS" bash "$ROOT/scripts/build.sh" mousepad2 "$VERNAME" "$VERCODE" "$OUTNAME"

APK="$ROOT/build/$OUTNAME.apk"
echo "[release] 隐私校验：$APK"
TMP="$ROOT/build/_verify"
rm -rf "$TMP"
mkdir -p "$TMP"
unzip -q "$APK" -d "$TMP"

# 1) 私钥必须是发布密钥
if [ -f "$SRC/assets/adbkey" ] && unzip -p "$APK" assets/adbkey | cmp -s - "$SRC/assets/adbkey"; then
  echo "[release] 校验失败：APK 内嵌个人私钥" >&2
  rm -rf "$TMP"
  exit 1
fi
# 2) 全包不得出现个人标识 / 真实内网地址 / 真实 MAC
if grep -raqiE "$PRIVACY_RE" "$TMP"; then
  echo "[release] 校验失败：APK 内发现隐私信息：" >&2
  grep -raliE "$PRIVACY_RE" "$TMP" >&2
  rm -rf "$TMP"
  exit 1
fi
rm -rf "$TMP"

echo "[release] 隐私校验通过"
echo "[release] 完成：$APK  (v$VERNAME / code $VERCODE)"
