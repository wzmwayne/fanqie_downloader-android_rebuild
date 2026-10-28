# 番茄小说下载器 Android

Android 版番茄小说（Fanqie Novel）下载器，无需 root，无需反编译加密库。

## 原理

与 PC 版方案不同，本项目**直接调用番茄小说 App 中的原生 SO 库** `libmetasec_ml.so` 进行签名生成，而非使用 unidbg 等 ARM 模拟器方案。SO 通过 `dlopen` 加载并直接调用其签名函数，签名生成性能远优于模拟方案。

## 功能

- 搜索小说
- 按 Book ID 下载全部/指定章节
- 输出 EPUB 格式（带封面、目录）或 TXT 格式
- 下载完成后自动复制到公共 Download 目录
- 支持 Android 8.0+ (API 26)

## 构建要求

- JDK 17+
- Android SDK (compileSdk 34)
- Android NDK r25c
- Gradle 8.12 (由 wrapper 自动管理)

## 快速开始

```bash
# 设置环境变量
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/25.2.9519653
export JAVA_HOME=/path/to/jdk-21

# 构建
./gradlew assembleDebug

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

1. 打开 App，输入小说 Book ID
2. 设置起止章节（默认全部）
3. 点击「下载 EPUB」或「下载 TXT」
4. 等待下载完成，文件自动复制到 Download 目录

## 技术细节

- 核心签名 SO：`libmetasec_ml.so`（来自番茄小说 App v6.8.1.32）
- JNI 桥接：`fqsigner.cpp` 通过 `dlopen` + `/proc/self/maps` 解析 SO 基址，调用偏移 `0x168c80` 的签名函数
- API 兼容：仅使用 Android API 26 可用接口，无 Java 9+ API 依赖

## 免责声明

本项目仅供学习研究使用，不得用于商业用途。下载的内容请于 24 小时内删除。
