# 本地 ASR（LocalASR）

完全离线的 Android 语音识别 App，六个 ASR 引擎各自独立成 module。工程风格与 [LocalTTS](https://github.com/dengjunhui1-byte/LocalTTS) 对齐：`EngineDescriptor` + `ModelInstaller` + JSON 驱动参数。

## 引擎

| Module | 引擎 | 运行时 | 参数量（官方口径） | 模型包 | 交付 |
| --- | --- | --- | --- | --- | --- |
| `asr-whisper` | Whisper Base | sherpa-onnx / ONNX | ~74M（OpenAI Whisper Base） | `sherpa-onnx-whisper-base` | assets，首启导入 |
| `asr-paraformer` | Paraformer-zh-small | sherpa-onnx / ONNX | ~220M（FunASR Paraformer-zh） | `sherpa-onnx-paraformer-zh-small-2024-03-09` | assets，首启导入 |
| `asr-sensevoice` | SenseVoice Small | sherpa-onnx / ONNX | 234M（FunASR / SenseVoice 模型卡） | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` | assets，首启导入 |
| `asr-parakeet` | **Parakeet-TDT 0.6B v2（0.3B–0.6B 旗舰）** | sherpa-onnx / NeMo TDT | **600M**（[NVIDIA HF 模型卡](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2)） | `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` | assets，首启导入 |
| `asr-streaming` | 流式 Zipformer 中英 | sherpa-onnx OnlineRecognizer | 流式 bilingual mobile 档 | `...-bilingual-zh-en-2023-02-20-mobile` | assets，首启导入 |
| `asr-qwenasr` | **Qwen ASR (MNN)** | MNN LLM + `qwen3_asr` 音频编码 | **600M**（[Qwen/Qwen3-ASR-0.6B](https://huggingface.co/Qwen/Qwen3-ASR-0.6B)） | `qwen3-asr-0.6b-int8-mnn` | 设置页按需下载（~1.3 GB） |

`asr-core` 提供共享契约：`AsrEngine`、`EngineDescriptor`（解析 `assets/asr/<engineId>/config.json`）、`ModelInstaller`（assets 拷贝 / 断点续传 / 多文件远程清单 / SHA-256 / `.installed` 标记）、`AudioIo`（WAV 读取、16 kHz 重采样、麦克风采集）。

### Qwen3-ASR (MNN) 说明

- **模型**：社区 int8 MNN 包 [hb-dev/Qwen3-ASR-0.6B-INT8-MNN](https://huggingface.co/hb-dev/Qwen3-ASR-0.6B-INT8-MNN)（ModelScope：[huangzhengxiang/Qwen3-ASR-0.6B-INT8-MNN](https://www.modelscope.cn/models/huangzhengxiang/Qwen3-ASR-0.6B-INT8-MNN)），由 [Qwen/Qwen3-ASR-0.6B](https://huggingface.co/Qwen/Qwen3-ASR-0.6B) 经 MNN `llmexport.py` 导出为 `llm.mnn` + `audio.mnn`（见 [MNN PR #4478](https://github.com/alibaba/MNN/pull/4478)）。
- **JNI**：`libqwenasr_jni.so` 静态链接 MNN master（与 LocalTTS `tts-qwentts` 相同思路，符号隐藏）。未提交到 git；需本地构建：
  ```bash
  git clone --depth 1 https://github.com/alibaba/MNN.git .mnn-build/MNN
  export ANDROID_NDK=$ANDROID_HOME/ndk/25.1.8937393
  ./tools/build_qwenasr_native.sh
  ```
- **权重**（任选其一）：
  ```bash
  # App 内按需下载（config.json 已填 HF / ModelScope 分文件 URL）
  # 或预先拉到 assets 做离线调试：
  ./tools/fetch_qwenasr_models.sh
  python3 tools/update_asset_sizes.py
  ```
- **自行导出**（需 GPU/CPU PyTorch 环境与 MNN 源码）：
  ```bash
  huggingface-cli download Qwen/Qwen3-ASR-0.6B --local-dir ./Qwen3-ASR-0.6B
  python3 tools/export_qwen3_asr_mnn.py --path ./Qwen3-ASR-0.6B --dst ./QwenASR/Qwen3-ASR-0.6B-MNN
  ```
- **设备**：仅 **arm64-v8a**；建议 **8 GB+ RAM**，存储预留 **~1.5 GB**。

### 0.3B–0.6B 档说明（300M–600M）

- **band 内旗舰（sherpa）**：`asr-parakeet` → NVIDIA **Parakeet-TDT-0.6B-v2，600M 参数**（英文 ASR）。
- **band 内旗舰（MNN 多语）**：`asr-qwenasr` → **Qwen3-ASR-0.6B，600M**（52 语系，UI 提供中英语言提示）。
- **中英主力但低于 300M**：SenseVoice **234M**。

## 参数由 JSON 驱动

每个 module 在 `src/main/assets/asr/<engineId>/config.json` 声明语言、提示语、模型与 `params`。UI 按 `FLOAT/INT/ENUM/BOOL` 渲染控件。assets 必须带 `asr/<engineId>/` 前缀，避免多 module 合并冲突。

## 构建

```bash
./tools/fetch_models.sh              # 下载五个 sherpa-onnx 模型到各 module assets
./tools/build_qwenasr_native.sh      # 可选：编译 Qwen ASR JNI（需 NDK + MNN 源码）
python3 tools/update_asset_sizes.py  # 回写 sizeBytes，供首启进度条使用
./gradlew assembleDebug
```

- **JDK 17**（与 AGP 8.3.1 匹配；Android Studio 自带 JBR 即可）。
- sherpa 模型**不入库**；Qwen MNN 权重与 JNI **不入库**。
- 真机建议 **arm64-v8a**，RAM **6 GB+**（含 Qwen ASR 时建议 **8 GB+**）。

## 运行时

- **首次启动**：阻塞式进度对话框，将 assets 解压到 `getExternalFilesDir(null)/localasr/models/<engineId>/<dir>/`，写入 `.installed`（Qwen ASR 可在设置页单独按需下载）。
- **输入**：选择 WAV/音频文件，或麦克风录音；`asr-streaming` 在录音过程中输出 **partial** 文本，其余引擎在停录后整段识别。
- **异常**：`ModelNotInstalledException`、`UnsupportedDeviceException`（ABI 等）会在 UI 提示。

## 许可证

应用代码以项目仓库为准。各模型权重遵循上游模型卡（Qwen3-ASR 见 [Qwen 模型许可](https://huggingface.co/Qwen/Qwen3-ASR-0.6B)；SenseVoice 见 FunASR Model Open Source License；Whisper 见 OpenAI 使用条款；sherpa-onnx 预导出包见 k2-fsa 发布页说明）。
