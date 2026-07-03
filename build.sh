#!/bin/bash
# Build script for fanqie_downloader-android
# Prerequisites:
#   - Android SDK (set ANDROID_HOME)
#   - Android NDK (set ANDROID_NDK_HOME)
#   - Java 17+
#   - Gradle 8.5+ (or use ./gradlew)
#
# Build:
#   ./gradlew assembleDebug
#
# Install:
#   adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "=== 番茄下载器 Android 构建脚本 ==="
echo ""
echo "请确保已安装:"
echo "  ANDROID_HOME=$ANDROID_HOME"
echo "  JDK: $(java -version 2>&1 | head -1)"
echo ""
echo "执行: ./gradlew assembleDebug"
echo "安装: adb install -r app/build/outputs/apk/debug/app-debug.apk"
