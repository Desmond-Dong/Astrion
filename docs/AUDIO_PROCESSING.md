# 音频处理（Audio Processing）

许多 Android 设备内置了硬件音频处理能力（回声消除、噪声抑制、自动增益）。
Astrion 把这些能力全部暴露为 **ESPHome 实体**，全部在 Home Assistant 里配置，
App 内没有任何设置界面。

> [!NOTE]
> 具体实现与支持程度因厂商/设备差异很大，不同场景需要实验调优。

> [!TIP]
> 可以让 Home Assistant 保存语音命令的调试录音来排查麦克风问题。
> 在 HA 配置文件中加入（**/share/assist_pipeline** 为保存目录）：
>
> ```yaml
> assist_pipeline:
>   debug_recording_dir: /share/assist_pipeline
> ```

## 相关实体

| 实体 | 类型 | 说明 |
|---|---|---|
| `audio_source` | select | Android 采集源（决定物理麦克风与系统调音） |
| `communication_mode` | switch | 通信模式（`MODE_IN_COMMUNICATION`） |
| `speakerphone` | switch | 免提路由 |
| `noise_suppression` | switch | 硬件噪声抑制（挂载到采集 session，设备不支持时自动跳过） |
| `echo_cancellation` | switch | 硬件回声消除（同上） |
| `auto_gain` | switch | 硬件自动增益（同上） |
| `wake_word_sensitivity` | number | 唤醒词灵敏度 0.01–0.50（阈值 = 1 − 灵敏度），即时生效 |

修改音频设置后面板会自动以新配置重启采集，无需重启 App。

## 采集源（audio_source）

采集源决定了使用的物理麦克风以及系统叠加的调音/效果，各机型实现不同，通用规律：

- **Voice Recognition（默认）**：为语音助手调优，信号干净，安静环境下唤醒词
  识别与语音转文字效果最好。
  - *缺点：*几乎不抑制背景噪声，回声消除效果差。
- **Voice Communication**：为通话调优，通常自带针对语音的回声消除与噪声抑制，
  嘈杂环境表现更好。
  - *注意：*部分机型（如三星）必须同时开启 **Communication mode** 才生效。
- **Mic**：通用采集，可能有自动增益和轻度降噪，但会拾取背景噪声。
- **Camcorder**：为摄像收音调优，常使用朝向摄像头的麦克风，风噪抑制较好。
- **Unprocessed**：原始未处理音频，部分设备不可用，不可用时行为同 Mic。

## Communication mode（通信模式）

为实时语音聊天调优的 Android 音频模式，可启用回声消除与噪声抑制；
但音频可能被路由到听筒，影响音质与音量。默认**关**。

> [!NOTE]
> 某些机型上 **Voice Communication** 采集源必须配合通信模式才能启用回声消除与降噪。

## Speakerphone（免提）

使用通信模式时，开启免提可能提升麦克风增益与音量，适合远场使用。

> [!NOTE]
> 免提让设备更响的同时可能使用机身底部的小扬声器，播放音质会下降。

## 推荐配置

### 安静环境（默认）
拾音与音质均衡：
- `audio_source`: **voice_recognition**
- `communication_mode`: **关**
- `speakerphone`: **关**
- `noise_suppression` / `echo_cancellation` / `auto_gain`: **开**

### 嘈杂环境（含面板自身正在外放媒体时）
按顺序实验（具体组合因设备而异）：
1. `audio_source`: **voice_communication**
2. 无改善则追加 `communication_mode`: **开**
3. 音量变得很小再加 `speakerphone`: **开**
