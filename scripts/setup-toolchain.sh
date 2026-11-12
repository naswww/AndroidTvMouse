#!/usr/bin/env bash
# 一次性环境准备（幂等）：JDK 17 + platform-tools + cmdline-tools + platform-34 + build-tools 34
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/toolchain"
SDK="$TC/android-sdk"
export MSYS_NO_PATHCONV=1
mkdir -p "$TC" "$SDK"

# ---- JDK 17（华为云镜像，实测 ~10MB/s；Adoptium 官方源在国内偏慢）----
if ! ls -d "$TC"/jdk-17* >/dev/null 2>&1; then
  echo ">> 下载 JDK 17"
  curl -sSL -o "$TC/jdk17.zip" \
    "https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip"
  unzip -q -o "$TC/jdk17.zip" -d "$TC"
fi
JDK="$(ls -d "$TC"/jdk-17* | head -1)"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
echo "JDK: $JDK"

# ---- platform-tools ----
if [ ! -f "$TC/platform-tools/adb.exe" ]; then
  echo ">> 下载 platform-tools"
  curl -sSL -o "$TC/pt.zip" "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
  unzip -q -o "$TC/pt.zip" -d "$TC"
fi

# ---- cmdline-tools ----
if [ ! -d "$SDK/cmdline-tools/latest" ]; then
  echo ">> 下载 cmdline-tools"
  curl -sSL -o "$TC/cmdline.zip" "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
  unzip -q -o "$TC/cmdline.zip" -d "$TC/cmdline-tmp"
  mkdir -p "$SDK/cmdline-tools"
  mv "$TC/cmdline-tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

# sdkmanager 直接用 java -cp 调主类，绕开 .bat 在 Git Bash 下的调用问题
SDKMGR=("$JDK/bin/java.exe" -cp "$SDK/cmdline-tools/latest/lib/*"
        com.android.sdklib.tool.sdkmanager.SdkManagerCli --sdk_root="$SDK")

if [ ! -f "$SDK/platforms/android-34/android.jar" ] || [ ! -f "$SDK/build-tools/34.0.0/aapt2.exe" ]; then
  echo ">> 接受许可协议"
  yes | "${SDKMGR[@]}" --licenses >/dev/null 2>&1
  echo ">> 安装 platforms;android-34 + build-tools;34.0.0"
  "${SDKMGR[@]}" "platforms;android-34" "build-tools;34.0.0"
fi

echo ">> 校验"
"$SDK/build-tools/34.0.0/aapt2.exe" version
"$JDK/bin/java.exe" -version 2>&1 | head -1
echo "SETUP OK"
