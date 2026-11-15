# MIMI 耳边

MIMI 是面向 ASMR 和音声作品的 Android 本地播放器。它把整个文件夹组织成一部作品，按曲目顺序播放，并同步显示字幕。

## 安装

在仓库的 Releases 下载签名 APK。支持 ARM64 设备和 Android 8.0 以上版本。

## 功能

- 导入文件夹、音频和 ZIP / 7z / RAR 作品包。支持 CUE 分轨、连续播放、续听和睡眠定时。
- 支持 VTT、LRC、SRT、ASS / SSA、SBV、SAMI 和 TTML 文本字幕。拖动进度条预览目标字幕，轻点字幕进入纯字幕模式。
- LibVLC 播放与 FFmpeg 探测、转换，保留原声道。DSF 可生成 FLAC 兼容副本。
- 在设置中填写自己的 OpenRouter API Key 后，可识别音频、翻译外语字幕并保存为中文 VTT / LRC。识别和翻译任务支持暂停、继续和结果复用。启动云字幕任务会将相应音频片段或字幕发往所选服务。
- 在「设置 → 资料库 → 隐藏空间」管理已隐藏作品。首次设置 6 位数字密码，支持设备上可开启指纹快捷解锁。移动作品只更新应用内索引。

## 构建

需要 JDK 17、Android SDK 36（含 Build Tools 36.0.0）和 Python 3.9 或更新版本。通过 Android Studio 配置 SDK，或在环境中设置 `ANDROID_HOME`。

```sh
python tools/prepare-native-libs.py
./gradlew assembleDebug
```

Windows 使用 `gradlew.bat assembleDebug`。脚本从 Maven Central 下载并核验固定版本的原生依赖，APK 输出到 `app/build/outputs/apk/debug/`。

使用 `assembleRelease` 构建 ARM64 发行包。在本地 `signing.properties` 中配置 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword` 来签名。

## 第三方组件

上游许可证、对应源码和原生依赖来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
