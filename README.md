# 本地 ASR（LocalASR）

完全离线的 Android 语音识别 App，五个 ASR 引擎各自独立成 module。工程风格与 [LocalTTS](https://github.com/dengjunhui1-byte/LocalTTS) 对齐：`EngineDescriptor` + `ModelInstaller` + JSON 驱动参数。

## 引擎

| Module | 引擎 | 运行时 | 参数量（官方口径） | 模型包 | 交付 |
| --- | --- | --- | --- | --- | --- |
| `asr-whisper` | Whisper Base | sherpa-onnx / ONNX | ~74M（OpenAI Whisper Base） | `sherpa-onnx-whisper-base` | assets，首启导入 |
| `asr-paraformer` | Paraformer-zh-small | sherpa-onnx / ONNX | ~220M（FunASR Paraformer-zh） | `sherpa-onnx-paraformer-zh-small-2024-03-09` | assets，首启导入 |
| `asr-sensevoice` | SenseVoice Small | sherpa-onnx / ONNX | 234M（FunASR / SenseVoice 模型卡） | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` | assets，首启导入 |
| `asr-parakeet` | **Parakeet-TDT 0.6B v2（0.3B–0.6B 旗舰）** | sherpa-onnx / NeMo TDT | **600M**（[NVIDIA HF 模型卡](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2)） | `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` | assets，首启导入 |
| `asr-streaming` | 流式 Zipformer 中英 | sherpa-onnx OnlineRecognizer | 流式 bilingual mobile 档 | `...-bilingual-zh-en-2023-02-20-mobile` | assets，首启导入 |

`asr-core` 提供共享契约：`AsrEngine`、`EngineDescriptor`（解析 `assets/asr/<engineId>/config.json`）、`ModelInstaller`（assets 拷贝 / 断点续传 / SHA-256 / `.installed` 标记）、`AudioIo`（WAV 读取、16 kHz 重采样、麦克风采集）。

### 0.3B–0.6B 档说明（300M–600M）

- **band 内旗舰**：`asr-parakeet` → NVIDIA **Parakeet-TDT-0.6B-v2，600M 参数**（官方模型卡）。英文 ASR；中文请用 SenseVoice / Paraformer。
- **中英主力但低于 300M**：SenseVoice **234M**（不满足 band 下限，仍保留为多语/情感标签引擎）。
- **band 内但未默认集成**：FireRedASR-AED-**S 413M**（论文 Table 1）、Distil-Whisper **medium.en 394M**（论文 Table 3）— 见 `docs/02-model-selection-0.3b-0.6b.md` 的缺口分析。
- 未在 APK 内捆绑 MNN 大模型或 JNI 树；更大模型可参考 LocalTTS 的按需下载模式单独加 module。

## 参数由 JSON 驱动

每个 module 在 `src/main/assets/asr/<engineId>/config.json` 声明语言、提示语、模型与 `params`。UI 按 `FLOAT/INT/ENUM/BOOL` 渲染控件。assets 必须带 `asr/<engineId>/` 前缀，避免多 module 合并冲突。

## 构建

```bash
./tools/fetch_models.sh              # 下载五个 sherpa-onnx 模型到各 module assets
python3 tools/update_asset_sizes.py  # 回写 sizeBytes，供首启进度条使用
./gradlew assembleDebug
```

- **JDK 17**（与 AGP 8.3.1 匹配；Android Studio 自带 JBR 即可）。
- 模型**不入库**；未执行 `fetch_models.sh` 时 Gradle 仍可同步，但运行前需有 assets 或设置页在线安装（`config.json` 中已填 GitHub/HuggingFace 镜像 URL）。
- 真机建议 **arm64-v8a**，RAM **6 GB+**（SenseVoice + 流式 Zipformer 同装时建议 **8 GB+**）。

## 运行时

- **首次启动**：阻塞式进度对话框，将 assets 解压到 `getExternalFilesDir(null)/localasr/models/<engineId>/<dir>/`，写入 `.installed`。
- **输入**：选择 WAV/音频文件，或麦克风录音；`asr-streaming` 在录音过程中输出 **partial** 文本，其余引擎在停录后整段识别。
- **异常**：`ModelNotInstalledException`、`UnsupportedDeviceException`（ABI 等）会在 UI 提示。

## 文档

| 文件 | 内容 |
| --- | --- |
| [docs/01-offline-asr-landscape.md](docs/01-offline-asr-landscape.md) | 离线 ASR 产业与开源模型概览 |
| [docs/02-model-selection-0.3b-0.6b.md](docs/02-model-selection-0.3b-0.6b.md) | 0.3B 档选型与 SenseVoice 旗舰 |
| [docs/03-architecture-like-localtts.md](docs/03-architecture-like-localtts.md) | 与 LocalTTS 同构的多引擎架构 |
| [docs/04-build-and-run.md](docs/04-build-and-run.md) | 克隆 → 拉模型 → 运行 |
| [docs/05-extend-a-new-engine.md](docs/05-extend-a-new-engine.md) | 新增第五个引擎 |

## 许可证

应用代码以项目仓库为准。各模型权重遵循上游模型卡（SenseVoice 见 FunASR Model Open Source License；Whisper 见 OpenAI 使用条款；sherpa-onnx 预导出包见 k2-fsa 发布页说明）。
