#!/usr/bin/env bash
# 无 Gradle 构建：aapt2 -> javac -> d8 -> zipalign -> apksigner
#   bash scripts/build.sh            构建 app/（无障碍版）
#   bash scripts/build.sh mousepad   构建 mousepad/（本地 adb 接管版）
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="${1:-app}"
VERNAME="${2:-1.0.0}"
VERCODE="${3:-1}"
OUTNAME="${4:-$MODULE}"
SRC="$ROOT/$MODULE"
ASSET_DIR="${ASSETS_DIR:-$SRC/assets}"
SDK="$ROOT/toolchain/android-sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
JDK="$(ls -d "$ROOT"/toolchain/jdk-17* 2>/dev/null | head -1)"
OUT="$ROOT/build/$MODULE"
KS="$ROOT/build/debug.keystore"

[ -d "$SRC" ] || { echo "模块不存在：$SRC"; exit 1; }
[ -n "$JDK" ] || { echo "缺少 JDK：请运行 scripts/setup-toolchain.sh"; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "缺少 android-34 平台：请运行 scripts/setup-toolchain.sh"; exit 1; }
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

# 内置 adb 密钥：本地缺失时自动生成（密钥不入库，见 .gitignore）
if [ -d "$ASSET_DIR" ] && { [ ! -f "$ASSET_DIR/adbkey" ] || [ ! -f "$ASSET_DIR/adbkey.pub" ]; }; then
  echo "[0/6] 生成 adb 密钥"
  bash "$ROOT/scripts/genkey.sh" "$ASSET_DIR"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/assets"

# 版本号/应用名写进临时 manifest，避免被 manifest 里的硬编码覆盖
#   APP_LABEL 可选，用于覆盖应用名（不填则用 manifest 默认）
MANIFEST="$OUT/AndroidManifest.xml"
SED_ARGS=(
  -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERCODE\"/"
  -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERNAME\"/"
)
if [ -n "${APP_LABEL:-}" ]; then
  SED_ARGS+=(-e "s|android:label=\"[^\"]*\"|android:label=\"$APP_LABEL\"|")
fi
sed "${SED_ARGS[@]}" "$SRC/AndroidManifest.xml" > "$MANIFEST"

echo "[1/6] aapt2 compile"
"$BT/aapt2.exe" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
ASSET_ARG=""
if [ -d "$ASSET_DIR" ]; then
  ASSET_ARG="-A $(cygpath -m "$ASSET_DIR")"
fi
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  $ASSET_ARG \
  --min-sdk-version 21 --target-sdk-version 28 \
  --version-code "$VERCODE" --version-name "$VERNAME" \
  --auto-add-overlay

# 生成构建信息（供「关于」显示构建日期）
BUILD_DATE="$(date +%Y-%m-%d)"
mkdir -p "$OUT/gen/com/tv/remotemouse/v2"
cat > "$OUT/gen/com/tv/remotemouse/v2/BuildInfo.java" <<EOF
package com.tv.remotemouse.v2;

public final class BuildInfo {
    public static final String DATE = "$BUILD_DATE";

    private BuildInfo() {
    }
}
EOF

echo "[3/6] javac"
find "$SRC/src" "$OUT/gen" -name '*.java' | while read -r f; do cygpath -m "$f"; done > "$OUT/sources.txt"
javac -encoding UTF-8 --release 8 -nowarn \
  -classpath "$(cygpath -m "$ANDROID_JAR")" \
  -d "$OUT/classes" "@$(cygpath -m "$OUT/sources.txt")"

echo "[4/6] d8 -> classes.dex"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 21 --lib "$ANDROID_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')
jar --update --file "$OUT/base.apk" -C "$OUT/dex" classes.dex

echo "[5/6] zipalign"
"$BT/zipalign.exe" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
if [ ! -f "$KS" ]; then
  mkdir -p "$ROOT/build"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/build/$OUTNAME.apk" "$OUT/aligned.apk"

ls -lh "$ROOT/build/$OUTNAME.apk"
echo "BUILD OK: $ROOT/build/$OUTNAME.apk  (v$VERNAME / code $VERCODE)"
