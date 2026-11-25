<p align="center">
  <img src="docs/assets/wordmark.svg" alt="ASTROLAB Interpreter" width="960">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-0.6.6-2859CC?style=flat-square" alt="Version 0.6.6">
  <img src="https://img.shields.io/badge/Android-8.0%2B-536275?style=flat-square" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/ABI-ARM64-82996a?style=flat-square" alt="ARM64">
  <img src="https://img.shields.io/badge/status-development-74747B?style=flat-square" alt="In development">
</p>

<p align="center">
  <a href="#features">功能</a> · <a href="#architecture">架构</a> · <a href="#build">编译</a> · <a href="#development">开发状态</a> · <a href="#english">English</a>
</p>

---

**Interpreter** 是 ASTROLAB 的 Android 实时传译工具，支持中文、英语与俄语的语音识别、文字互译、双语字幕、会话总结与专业词库。

语音识别在本地运行，DeepSeek Flash 结合上下文和词库辅助翻译。翻译和传译页右上角可切换本地与联网模式。

<a id="features"></a>

## 01 / Features

| 模块 | 功能 |
|:--|:--|
| TRANSLATE | 自动判定文字语种，选择目标语言，复制与朗读 |
| INTERPRET | 连续双语字幕，灰色临时识别文字，译文就地更新 |
| CONTEXT | 场景推测、上下文翻译、术语匹配 |
| SESSIONS | 本地会话记录、云端总结、原始识别文本 |
| GLOSSARY | 自动提取术语候选，分组、搜索、筛选、例句及确认 |
| AUDIO | 前台麦克风服务、系统 TTS、通知栏结束会话 |

字幕按语句对应排列，每段显示采集时间戳，停顿超过 60 秒后另起一组。Whisper 采用分段识别与滚动预识别，中文优先使用 SenseVoice 识别；中英俄分段识别可在实验模式中启用。俄语实时模式使用 T-one 流式预识别，句末由 Whisper 校正。点按已识别字幕可编辑原文或译文，修改原文后自动重译该句。

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
                  SenseVoice (zh / en)
                            |
             provisional -> final transcript
                            |
                +-----------+-----------+
                |                       |
            ML Kit                DeepSeek Flash
          local translate        context / translation
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
| ASR | sherpa-onnx 2.0.3 / Whisper Base INT8 / SenseVoice INT8 / T-one CTC |
| Offline translation | Google ML Kit Translate 17.0.3 |
| Online enhancement | DeepSeek Flash, HTTPS / JSON |
| Storage | Android app-private storage |
| Credentials | Android Keystore / AES-GCM |

ASR 模型随 APK 安装。本地模式使用 ML Kit 翻译及离线 TTS，需提前下载语言包；联网模式使用 Flash 增强翻译、总结及术语提取。

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

模型下载及校验见 [MODELS.md](docs/MODELS.md)。在应用设置中配置 DeepSeek，默认模型为 `deepseek-flash`。

<a id="development"></a>

## 04 / Development

当前版本 **0.6.6**，开发测试设备为 Huawei P30 Pro（Android 10 / 8 GB）。

| 版本 | 主要变化 |
|:--|:--|
| 0.6.6 | 中文优先识别、异常符号拦截、实验模式隔离 |
| 0.6.5 | 改进语言切换检测、实验性声音事件字幕、记录日期层级 |
| 0.6.4 | SenseVoice 英文大小写处理 |
| 0.6.3 | 记录左滑删除与二次确认 |
| 0.6.2 | 中英俄混合识别、原文与翻译分离 |
| 0.6.1 | 逐句时间戳 |
| 0.6.0 | T-one 俄语流式预识别、单句编辑、修正词高亮 |
| 0.5.2 | 临时字幕稳定、异常识别重试、俄语上下文及译文状态 |
| 0.5.1 | 导航与图标、字幕排版、实时音量历史、会话分组 |
| 0.5.0 | 中英混说识别、并行翻译、字幕动画、本地与联网模式 |
| 0.4.1 | 云端失败退避、相关术语筛选、音量更新节流、过期文字结果隔离 |
| 0.4.0 | 连续字幕、准流式识别、60 秒时间分组、分类词库 |
| 0.3.0 | 三语自动识别、Whisper Base INT8、重新组织主界面 |
| 0.2.0 | 本地 ASR、语义增强、记录、总结、术语与 TTS |
| 0.1.0 | Android 工程与真机录音原型 |

中英俄连续混说仍处于实验阶段，可能出现漏词、误判语种或词汇被识别模型翻译的现象。可点按字幕手动修正。

详细记录：[DEVLOG](docs/DEVLOG.md) / [App Icon Standard](docs/ASTROLAB-APP-ICON-STANDARD.md)。

### Third-party components

上游项目：[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)、[Whisper](https://github.com/openai/whisper)、[SenseVoice](https://github.com/FunAudioLLM/SenseVoice)、[T-one](https://github.com/voicekit-team/T-one)、[Silero VAD](https://github.com/snakers4/silero-vad)、[ML Kit](https://developers.google.com/ml-kit/language/translation)。第三方组件适用各自的许可证。Interpreter 源码许可证暂未确定。

<a id="english"></a>

## English

Interpreter is a native Android language assistant by ASTROLAB. It combines on-device multilingual speech recognition, aligned bilingual captions, offline translation, optional DeepSeek Flash enhancement, session notes and a personal glossary. Chinese, Russian and English are the supported speech-language candidates.

Currently in development. Built with Kotlin, Jetpack Compose and sherpa-onnx.

---

<p align="center"><sub>ASTROLAB / Interpreter</sub></p>
