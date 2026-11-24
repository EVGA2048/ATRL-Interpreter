<p align="center">
  <img src="docs/assets/wordmark.svg" alt="ASTROLAB Interpreter" width="960">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-0.4.1-2859CC?style=flat-square" alt="Version 0.4.1">
  <img src="https://img.shields.io/badge/Android-8.0%2B-536275?style=flat-square" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/ABI-ARM64-82996a?style=flat-square" alt="ARM64">
  <img src="https://img.shields.io/badge/status-development-74747B?style=flat-square" alt="In development">
</p>

<p align="center">
  <a href="#features">功能</a> · <a href="#architecture">架构</a> · <a href="#build">编译</a> · <a href="#development">开发状态</a> · <a href="#english">English</a>
</p>

---

**Interpreter** 是 ASTROLAB 开发的 Android 语言辅助工具，面向多语言交流、课堂听讲和专业技术讨论。支持中文、英语与俄语的自动语音识别、双向文字翻译、连续双语字幕、会话总结和专业词库。

语音识别在设备上运行。翻译可以使用本地语言模型，也可以通过 DeepSeek Flash 结合语境与术语进行语义增强。应用保存原始识别结果，不用云端修正覆盖原始记录。

<a id="features"></a>

## 01 / Features

| 模块 | 功能 |
|:--|:--|
| TRANSLATE | 自动判定文字语种，选择目标语言，复制与朗读 |
| INTERPRET | 连续双语字幕，灰色临时识别文字，译文就地更新 |
| CONTEXT | 场景推测、基于前后文的识别纠错、术语匹配 |
| SESSIONS | 本地会话记录、云端总结、原始识别文本 |
| GLOSSARY | 自动提取术语候选，分组、搜索、筛选、例句及确认 |
| AUDIO | 前台麦克风服务、系统 TTS、通知栏结束会话 |

实时传译按语句处理，但显示在同一条时间线上。连续语音不产生独立卡片，只有相邻语句间的采集间隔达到 60 秒才建立新的时间分组。预识别是 Whisper 重复解码实现的准流式输出，并非原生流式声学模型。

自动模式下，中文译为俄语，俄语与英语译为简体中文。文字翻译也可以指定中文、英语或俄语为目标语言。

### Glossary

术语按铁路与交通、工程与技术、计算机与电子、学术与教育、行政与手续等领域归档；英语与俄语可独立筛选。待确认术语不会进入后续翻译提示。已确认术语按当前语句与近期上下文的文字匹配选取，支持手动调整分类、释义与例句。

<a id="architecture"></a>

## 02 / Architecture

```text
                   AudioRecord / 16 kHz
                            |
                       Silero VAD
                            |
                   Whisper Base INT8
                    zh / en / ru LID
                            |
             provisional -> final transcript
                            |
                +-----------+-----------+
                |                       |
            ML Kit                DeepSeek Flash
          local translate        context / correction
                |                       |
                +-----------+-----------+
                            |
                    aligned bilingual text
                       /          \
                   Sessions      Glossary
```

| 组件 | 实现 |
|:--|:--|
| UI | Kotlin / Jetpack Compose |
| VAD | Silero VAD |
| ASR | sherpa-onnx 2.0.3 / multilingual Whisper Base INT8 |
| Offline translation | Google ML Kit Translate 17.0.3 |
| Online enhancement | DeepSeek Flash, HTTPS / JSON |
| Storage | Android app-private storage |
| Credentials | Android Keystore / AES-GCM |

语音识别模型内置于 APK，离线翻译语言包需首次联网下载。云端增强可以关闭；启用时只发送识别或输入文字、近期上下文及相关确认术语，不上传原始音频。录音不保存，文本会话保存在应用私有目录（不等同于会话数据加密）。系统 TTS 依赖已安装的语音引擎及语言包。

<a id="build"></a>

## 03 / Build

JDK 17、Android SDK 35、Gradle Wrapper 8.9。当前构建面向 ARM64，最低 Android 8.0（API 26）。

```bash
# Prepare the pinned ASR runtime and models
./scripts/fetch-models.sh

# Configure ANDROID_HOME or local.properties first
./gradlew :app:testDebugUnitTest :app:assembleDebug

# Install on a connected Android device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

模型及 AAR 不纳入 Git，下载来源和 SHA-256 记录见 [模型清单](docs/MODELS.md)。目前 APK 为调试签名，不是正式发行包。应用内 Settings 可配置 API Key、接口及模型，默认使用 `deepseek-flash`；API Key 不随仓库发布。

<a id="development"></a>

## 04 / Development

当前版本 **0.4.1**。首台开发测试设备为 Huawei P30 Pro（Android 10 / 8 GB / ARM64）。

| 版本 | 主要变化 |
|:--|:--|
| 0.4.1 | 云端失败退避、相关术语筛选、音量更新节流、过期文字结果隔离 |
| 0.4.0 | 连续字幕、准流式识别、60 秒时间分组、分类词库 |
| 0.3.0 | 三语自动识别、Whisper Base INT8、重新组织主界面 |
| 0.2.0 | 本地 ASR、语义增强、记录、总结、术语与 TTS |
| 0.1.0 | Android 工程与真机录音原型 |

验证状态：0.4.0 已在 P30 Pro 安装和启动；0.4.1 编译及四项 JVM 回归测试通过，尚未部署到手机。实时识别准确率、延迟、长会话、实际 API 和离线模型下载仍需持续真机验证。识别过载时可能丢弃音频分段并提示，不保证无损长时间转录。

详细记录：[DEVLOG](docs/DEVLOG.md) / [App Icon Standard](docs/ASTROLAB-APP-ICON-STANDARD.md)。

### Third-party components

上游项目：[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)、[Whisper](https://github.com/openai/whisper)、[Silero VAD](https://github.com/snakers4/silero-vad)、[ML Kit](https://developers.google.com/ml-kit/language/translation)。第三方组件遵守各自许可与条款。当前为私有开发仓库，尚未为 Interpreter 源码确定对外分发许可。

<a id="english"></a>

## English

Interpreter is a native Android language assistant by ASTROLAB. It combines on-device multilingual speech recognition, aligned bilingual captions, offline translation, optional DeepSeek Flash enhancement, session notes and a personal glossary. Chinese, Russian and English are the supported speech-language candidates.

The current build uses Whisper Base INT8 with repeated decoding for provisional captions; it is not a native streaming recognizer. Version 0.4.1 passes the debug build and four JVM regression tests. Hardware acceptance and latency measurements remain in progress.

---

<p align="center"><sub>ASTROLAB / Interpreter</sub></p>
