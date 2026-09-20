# Astrion — Home Assistant 红外遥控面板

> **声明：本项目的全部代码均由 AI（Claude Code / ZCode 等编码代理）编写生成。**

将 HA100 / Android 墙面板变成一台**全功能智能家居遥控器**：直连 Home Assistant
WebSocket API，配合 [Astrion 集成](https://github.com/Desmond-Dong/Astrion-integration)
（`astrion`）完成配对、分类卡片布局、红外码库下发与双向事件。

- **连接**：面板是 HA WebSocket 客户端（长寿命访问令牌认证），不再扮演 ESPHome 设备，
  不暴露任何入站端口；语音助手已移除，设备不上麦克风。
- **布局**：在 Astrion 集成里按分类（TV/灯光/空调/窗帘/媒体播放器/场景…）添加子条目，
  面板通过 `astrion/get_cards` 拉取并原生渲染，改动即时生效，无需重启。
- **红外**：码库经 `astrion/get_device_codes` 下发，按键面板本地直发（38kHz，
  支持逗号时序 / Broadlink base64 / AES base64）；未命中回落 `remote.send_command`。

界面参考原 HaRemote/Astrion 应用：深色面板、房间翻页、设备卡片网格，以及电视遥控
（方向环/数字键盘）、空调（温度/模式/风速）、灯光（亮度/色温）、风扇档位、窗帘
（开停关）、媒体播放、开关/场景、天气等原生设备详情页。

# 功能

## 遥控面板
- 房间 × 卡片主界面：布局来自集成分类子条目（`astrion/get_cards` → 面板原生渲染）
- 11 类卡片原生详情页：tv / light / fan / scene / media-player / climate / cover /
  switch / weather / host / switch-monitor
- 红外发射：**逗号时序 / Broadlink base64 / AES base64** 三格式自动识别
- 码库由集成下发（`astrion/get_device_codes`），按键本地直发；未命中走 `remote.send_command`
- 设备 → HA 服务调用（WebSocket `call_service`），卡片控制即点即用
- 面板 → HA 事件上行：跳页（`astrion/page_visited`）、页面清单
  （`astrion/navigate_list_upload`）、按键（`astrion/key_pressed` 等），自动化可直接订阅
- 导航：集成的 `navigate`（A 型，300ms 复位）与 `current_activity`（B 型持久）select
  可驱动面板跳页，面板操作也回报 HA
- 物理按键：短按/长按动态语义跟随当前设备页 + 面板本机快捷键绑定
  （长按实体键即进入绑定页）
- 屏保：空闲超时显示全屏时钟/日期/电量/连接状态，任意按键/触摸退出；充电时短动画
- 抬手唤醒：面板熄屏时被拿起即自动亮屏（加速度判定，带防抖）

## OTA 自更新
- HA 侧触发事件 `astrion/ota_manifest`（data 为 `{version, url, sha256, force?}`），
  面板弹横幅，点按下载 → SHA-256 校验 → 安装（root 走 `pm install -r`，
  无 root 走系统安装确认）；完全本地化

# Setup

1. **装集成**（HA 侧）：HACS → 自定义存储库 →
   `https://github.com/Desmond-Dong/Astrion-integration`（类别 Integration），
   安装后重启 HA；或手动拷贝 `custom_components/my_ir` 到 HA `config/custom_components/`。
2. **面板连 HA（网页配对，免键盘）**：创建 HA **长寿命访问令牌**（个人资料页 →
   安全 → 长寿命访问令牌）；用手机/电脑浏览器打开面板显示的
   `http://<面板IP>:8917`，在表单里粘贴 HA 地址与令牌提交，面板立即连接。
3. **配对**：面板自动向集成上报（`astrion/submit_pair_data`）；在 HA
   设置 → 设备与服务 → **Astrion Remote** → 添加 → 选择发现的面板完成配对。
4. **建分类**：在集成页面**添加子条目**，按分类勾选设备（TV 是三步向导：
   设备 → 电源/音量 → 物理按键绑定）。面板几秒内自动刷新出房间和卡片。

物理按键"一键直达"在遥控器上绑定：下拉面板 → 快捷键绑定。
详细说明见 [配置指南](docs/HA_SETUP.md)。

# 构建
push 到 master 自动触发 GitHub Actions：
- `test.yml`：单元测试
- `build-apk.yml`：debug APK（artifact `Astrion-debug-apk`）

# 部署（HA100）
设备 ADB、系统应用替换流程见需求文档 §8；Manifest 已声明 HOME 类别，
替换系统 launcher 后开机直接进入面板。

# Roadmap（后续批次）
- 云码库（astrion.lifex360.com）面板直连拉取
- 天气/主机卡片视觉增强

# Development
1. Clone the repo
2. Open in Android Studio

<!-- Links -->
[homeassistant]: https://www.home-assistant.io/
