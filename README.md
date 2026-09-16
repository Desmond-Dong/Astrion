# Astrion — ESPHome 遥控面板

将 HA100 / Android 墙面板变成一台 **全功能智能家居遥控器 + 语音卫星**。

⚠️**Warning: This app is intended for 'static' local devices on a trusted network**⚠️
- It exposes a port to allow incoming connections from Home Assistant with no authentication whatsoever (the device is the server in the ESPHome API).
- It is constantly using the microphone to listen for the wake word.

App 本体**零设置**：启动即运行 ESPHome 协议服务（端口 6053，原生 API 握手），
Home Assistant 通过 ESPHome 集成一键采纳；房间布局、IR 码库、物理按键绑定、
HA 状态同步清单等**全部配置都在 Home Assistant 里完成**，通过设备暴露的
`text` 实体下发，改动约 3 秒自动生效，App 无需重启。

界面参考原 HaRemote/Astrion 应用：深色面板、房间翻页、设备卡片网格，以及
电视遥控（方向环/数字键盘）、空调（温度/模式/风速）、灯光（亮度/色温）、风扇
档位、窗帘（开停关）、媒体播放、开关/场景、天气等原生设备详情页。

# 功能

## 遥控面板（HaRemote 能力合并）
- 房间 × 卡片的主界面：布局 JSON 由 HA 下发（对齐原 `astrion/devices` 设备树）
- 11 类卡片原生详情页：tv / light / fan / scene / media-player / climate / cover / switch / weather / host / switch-monitor
- 红外发射：**逗号时序 / Broadlink base64 / AES base64** 三格式自动识别（`ConsumerIrManager`，38kHz）
- 码库由 HA 下发（`astrion_ir_codes`），按键本地直发；未命中走 `remote.send_command`
- 设备 → HA 服务调用（`HomeassistantActionRequest`），卡片控制无需自定义集成
- 面板 → HA 事件上行：用户跳页/按遥控键以 event 实体（`panel_page_visited`/
  `panel_button_pressed`）+ `panel_pages` 页面清单上报，对应原集成的
  `page_visited`/`control_command`/`navigate_list_upload`，自动化可订阅
- 物理按键：短按/长按动态语义跟随当前设备页（原版物理键体系）+ HA 下发的按键绑定
- 导航：`navigate`（A 型，300ms 复位）与 `current_activity`（B 型持久）select，
  HA 自动化可驱动面板跳页，面板操作也会回报 HA

## 语音卫星
- 本地唤醒词（microWakeWord，最多两个模型 + 自定义模型目录）
- 停止词、语音命令、播报与对话、计时器
- **麦克风实体键免唤醒对话**：任何页面按下设备上的语音键（X9/HA10=131、
  HA100=133）直接开始 Assist 对话；HA 侧 `wake_assistant` 按钮等价触发
- 唤醒词/停止词/灵敏度/降噪全部是 ESPHome 实体，在 HA 中配置并即时生效：
  - `wake_word` / `second_wake_word` / `stop_word`（select）
  - `wake_word_sensitivity`（number，阈值 = 1 − 灵敏度）
  - `audio_source` / `communication_mode` / `speakerphone` /
    `noise_suppression` / `echo_cancellation` / `auto_gain`
    （硬件降噪三件套挂载到采集 session，见 [音频处理](docs/AUDIO_PROCESSING.md)）
- 外放（TTS/媒体）通过 `media_player` 实体，音量与静音在 HA 中调节

# Setup
- 安装并运行 App，服务自动启动（无任何 App 内设置）
- Home Assistant → ESPHome 集成采纳设备（或手动 IP + 端口 6053）
- 将布局 JSON 写入 `text.astrion_layout`，IR 码库写入 `text.astrion_ir_codes`，
  按键绑定写入 `text.astrion_key_bindings`
- 详见 [配置指南](docs/HA_PANEL_SETUP.md)（含 JSON schema、按键码表、自动化示例、
  语音/降噪实体说明）

# 构建
push 到 master 自动触发 GitHub Actions：
- `test.yml`：单元测试
- `build-apk.yml`：debug APK（artifact `Astrion-debug-apk`）

# 部署（HA100）
设备 ADB、系统应用替换流程见需求文档 §8；Manifest 已声明 HOME 类别，
替换系统 launcher 后开机直接进入面板。

# Roadmap（后续批次）
- Sendspin 多房间同步音频
- 屏保 / 充电动画 / 抬手唤醒
- OTA 自更新通道
- 云码库（astrion.lifex360.com）直连拉取

# Development
1. Clone the repo
2. Open in Android Studio

# Custom wake word models
默认唤醒词见 [assets/wakeWords](app/src/main/assets/wakeWords)，也支持目录形式
的自定义 microWakeWord 模型：把模型 `.tflite` 与合法描述 `.json` 放入设备目录，
再从 HA 侧唤醒词 select 中选择。最小 json 示例：
```
{
  "type": "micro",
  "wake_word": "Custom Wake Word",
  "model": "custom_wakeword.tflite",
  "micro": {
    "probability_cutoff": 0.97,
    "sliding_window_size": 5
  }
}
```

<!-- Links -->
[homeassistant]: https://www.home-assistant.io/
[esphome]: https://esphome.io/
