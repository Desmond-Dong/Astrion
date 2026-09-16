# Astrion 面板 — Home Assistant 配置指南

App 端**没有任何设置界面**：启动即运行 ESPHome 卫星服务（端口 6053），一切配置都在
Home Assistant 里通过 ESPHome 集成完成。设备上不做任何本地配置，删除应用数据也只
需要 HA 重新推送一次配置即可恢复。

**两步上手**：① ESPHome 集成采纳设备 → ② 把红外码库 JSON 写入
`text.astrion_ir_codes` 即可遥控。面板布局（`astrion_layout`）是可选的：不推送时
面板直接按码库生成默认房间；推送时也只需要极简形态（卡片 `name` + `entity_id`，
其余字段都有缺省值，见 §4）。

---

## 1. 接入

1. 安装并打开 App（部署为系统 HOME 时开机自动进入），服务自动启动。
2. Home Assistant → 设置 → 设备与服务 → ESPHome 集成，采纳发现的设备
   （或手动用设备 IP + 端口 6053 添加）。

采纳后设备上会出现这些实体（全部由 HA 侧控制；标 ☰ 的高级实体默认隐藏，
在实体卡片里开启"显示"后才会出现在设备页）：

| 实体 | 类型 | 用途 |
|---|---|---|
| `astrion_layout` | text | **面板布局 JSON**（房间/卡片/设备树，见 §2） |
| `astrion_ir_codes` | text | **IR 码库 JSON**（见 §3） |
| `astrion_key_bindings` | text | **物理按键绑定 JSON**（见 §4） |
| `navigate` | select | A 型复位选择器：选房间名 → 面板跳到该房间页（300ms 后自动复位） |
| `current_activity` | select | B 型持久选择器：镜像面板当前页面，供自动化读取 |
| `reconnect_ha` | button | 触发面板重连 |
| `media_player` | media_player | 语音卫星音频（外放） |
| `mute_microphone` | switch | 麦克风静音 |
| `enable_wake_sound` / `repeat_timer_sound` | switch | 语音提示音 |
| `wake_word` / `second_wake_word` / `stop_word` | select | 唤醒词/停止词选择（`second_wake_word` 选 `None` 表示不启用） |
| `wake_word_sensitivity` | number | 唤醒词灵敏度 0.01–0.50（阈值 = 1 − 灵敏度，默认 0.03 即模型默认 0.97 阈值），运行时即时生效 |
| `wake_assistant` | button | 远程触发一次免唤醒对话（等同按设备麦克风键） |
| `audio_source` | select | 麦克风采集源：`voice_recognition`（默认，干净信号）/ `voice_communication`（系统级降噪+回声消除调音）等 |
| `communication_mode` | switch | 通信模式（`MODE_IN_COMMUNICATION`，部分机型配合 voice_communication 源才启用降噪） |
| `speakerphone` | switch | 免提路由 |
| `noise_suppression` / `echo_cancellation` / `auto_gain` | switch | 硬件降噪/回声消除/自动增益（挂载到采集 session，设备不支持时自动跳过） |
| `media_title` / `media_artist` | text_sensor | 面板媒体元数据 |
| `<设备名>`（每个码库设备） | infrared | 直发原始时序（ESPHome infrared 服务） |
| `<按键> (<设备名>)`（每个码库按键） | button | 单键红外发射 |
| `panel_page_visited` | event | 用户在面板上跳页时触发 `page_visited`（原 `astrion/page_visited`，HA 发起的跳页不触发） |
| `panel_button_pressed` | event | `button_pressed`（屏幕上的遥控键）/ `key_pressed` / `key_long_pressed`（**任意物理按键**，短按/长按，原 `astrion/control_command`） |
| `panel_last_key` | text_sensor | 最后按下的物理键键码，如 `135` / `135_long`，配合按键事件实现任意键绑定 |
| `panel_pages` | text_sensor | 面板当前页面清单，逗号分隔（原 `astrion/navigate_list_upload`） |
| `screen_saver_timeout` | number | 屏保空闲超时（秒，0–600，步进 5，默认 0=关闭）；无按键/触摸达到该时长后面板显示全屏时钟/日期/电量/HA 连接状态，任意按键或触摸即退出 |
| `raise_to_wake_threshold` | number ☰ | 抬手唤醒加速度阈值（m/s²，0–10，步进 0.1，默认 0=关闭）；面板熄屏时被拿起（加速度突变超阈值）即自动亮屏，判定间隔 3 秒防抖 |
| `astrion_ota_manifest` | text ☰ | **OTA 更新清单 JSON**（`{version, url, sha256, force?}`，见 §12）；version 比面板已装版本新时面板弹出更新横幅 |
| `astrion_ota_install` | button ☰ | 触发一次 OTA 下载→SHA-256 校验→安装（root 走 `pm install -r`，无 root 走系统安装确认弹窗），等价于点按面板横幅上的"安装" |

## 3. 语音（麦克风 / 免唤醒 / 灵敏度 / 降噪）

- **麦克风按键（免唤醒对话）**：设备上的语音键（X9/HA10=131，HA100=133）在
  任何页面按下都会直接开始一次 Assist 对话，不需要唤醒词；松开语速说完即自动
  结束。HA 侧的 `wake_assistant` 按钮等价触发。
- **唤醒词**：`wake_word` / `second_wake_word` 选择模型，`wake_word_sensitivity`
  调灵敏度（默认 0.03 = 模型默认阈值 0.97；调大更灵敏、误唤醒也会增多），即时生效。
- **降噪与拾音调优**：默认开启硬件降噪/回声消除/自动增益（设备支持时自动挂载）。
  嘈杂环境建议：`audio_source` 切到 `voice_communication` + 打开
  `communication_mode`（部分机型必须组合使用）；安静环境用默认
  `voice_recognition` 对唤醒词更友好。
- **外放**：TTS/媒体通过 `media_player` 实体播报，音量/静音在 HA 中直接调节。

## 4. 面板布局 `astrion_layout`

写入 JSON 即生效；面板 UI、导航 select 选项、HA 状态订阅清单都会随之重建。

```json
{
  "rooms": [
    {
      "title": "客厅",
      "cards": [
        {
          "type": "tv",
          "uuid": "tv1",
          "name": "小米电视",
          "tv_type": "android_tv",
          "entities": [
            {"key": "POWER", "entity_id": "remote.mi_tv", "value": "POWER"},
            {"key": "VOLUME_UP", "entity_id": "media_player.mi_tv"}
          ]
        },
        {
          "type": "light",
          "name": "吸顶灯",
          "uuid": "light1",
          "entities": [{"entity_id": "light.ceiling", "alias": "主灯"}]
        },
        {
          "type": "climate",
          "name": "客厅空调",
          "uuid": "ac1",
          "entities": [{"entity_id": "climate.ac"}]
        }
      ]
    },
    { "title": "卧室", "cards": [] }
  ],
  "pages": ["全屋"],
  "sync_entities": [
    "light.ceiling",
    "climate.ac",
    "climate.ac.temperature",
    "climate.ac.current_temperature",
    "climate.ac.fan_mode",
    "media_player.mi_tv",
    "media_player.mi_tv.media_title",
    "media_player.mi_tv.media_artist"
  ]
}
```

### 字段说明

- **card `type`**（对齐原 aiks-\* 卡片）：
  `tv` / `light` / `fan` / `scene` / `media-player` / `climate` / `cover` /
  `switch` / `weather` / `host` / `switch-monitor`
- **card `uuid`**：详情页路由 id；缺省时由 type+name 生成。
- **card `tv_type`**：`android_tv`（默认）或 `apple_tv`。
- **card `percentage_step`**：风扇档位步长（默认 20，最多 5 档）。
- **entities[].`key`**：tv 卡的按键名（POWER、UP、DOWN、LEFT、RIGHT、CENTER、
  BACK、HOME、MENU、VOLUME_UP、VOLUME_DOWN、MUTE、UN_MUTE、PLAY、PAUSE、
  PLAY_PAUSE、CHANNEL_UP、CHANNEL_DOWN、NUM_0…NUM_9、DELETE …）。
- **entities[].`value`**：remote 实体实际下发的命令名；缺省用 `key`。
- **entities[].`alias`**：卡片显示别名。
- **pages**：附加导航页（出现在 navigate/current_activity 选项里，房间标题自动加入）。
- **sync_entities**：面板要订阅的 HA 实体状态。支持 `entity_id.attribute`
  形式订阅单个属性（空调温度、媒体标题等就靠它，卡片状态与详情页数据全部来自这里）。

### 卡片控制如何映射到 HA 服务

| 卡片/动作 | 面板行为 |
|---|---|
| tv 卡按键，码库命中 | **本地红外直发**（码库设备名 = 卡片 name） |
| tv 卡按键，码库未命中 | `remote.send_command {entity_id, command}` |
| tv 卡 media_player 实体 | `media_player.media_play / volume_up / volume_mute …` |
| tv 卡 select/script/button 实体 | `select_option / script.turn_on / button.press` |
| light | `light.turn_on{brightness_pct, color_temp_kelvin}` / `light.turn_off` |
| climate | `climate.set_temperature / set_hvac_mode / set_fan_mode` |
| fan | `fan.set_percentage{percentage}` |
| cover | `cover.open_cover / stop_cover / close_cover` |
| switch / scene | `<domain>.turn_on / turn_off` |
| media-player | `media_player.media_play_pause / media_next_track / volume_set …` |

## 5. IR 码库 `astrion_ir_codes`

```json
{
  "小米电视": {
    "POWER": "38000,9000,4500,560,560,560,1690,...",
    "MUTE": "sGipAAECAwQFBgcICQ=="
  },
  "机顶盒": {
    "POWER": "JgBMACHgERAQERAAHQAA"
  }
}
```

- **设备名必须与布局里 tv 卡的 `name` 一致**，按键名（外层键）与 entities 的
  `key`/`value` 一致，面板按下时才会在本地直接发射（无 HA 往返）。
- 支持三种码格式（与原 HaRemote 相同，自动识别）：
  1. **逗号时序**：`频率,脉宽,脉宽,…`（µs，正负交替）；
  2. **Broadlink base64**：首字节 0x26（IR）的 base64 包；
  3. **AES base64**：原 AES/ECB 加密格式。
- 每个"设备"同时暴露一个 `infrared` 实体，HA 可直接 `infrared.transmit`
  原始时序（不打码库）。

## 6. 物理按键绑定 `astrion_key_bindings`

原快捷键体系（短按/长按 → 房间/设备页/服务），现在完全由 HA 下发：

```json
{
  "bindings": [
    {"keycode": 132, "action": "room",  "target": "客厅"},
    {"keycode": 135, "action": "card",  "target": "tv1"},
    {"keycode": 135, "long_press": true, "action": "service",
     "service": "scene.turn_on", "entity_id": "scene.film"}
  ]
}
```

- `action`: `room`（target=房间标题）| `card`（target=卡片 uuid/cardId）|
  `service`（service + entity_id/data）。
- `keycode` 为 Android 键码；`long_press` 缺省 false（长按 800ms 阈值）。
- **设备页内的按键语义自动跟随卡片类型**（与原版一致），绑定只兜底未被页面
  消费的键：

| 键码 | 空调页 | 风扇页 | 灯页 | 电视页 | 媒体页 | 窗帘页 | 开关页 |
|---|---|---|---|---|---|---|---|
| 24 / 25 | 温度 +/− | 风速 +/− | 亮度 +/− | 音量 +/− | 音量 +/− | — | — |
| 92 / 93 | 风速切换 | — | — | 频道 −/+ | 上/下曲 | — | — |
| 23 (OK) | — | — | — | 确认 | 播放暂停 | 停止 | — |
| 132 (电源) | 开关 | 开关 | 开关 | — | — | 开/关切换 | 开关 |
| 164 | — | — | — | 静音 | 静音 | — | — |
| 19-22 / 4 / 82 | — | — | — | 方向/BACK/菜单 | — | — | — |

## 7. 配置下发自动化示例

把布局放到 `input_text`（或模板 sensor）里，再自动同步到面板实体：

```yaml
# configuration.yaml
input_text:
  astrion_layout:
    name: Astrion 布局
    max: 10000
```

```yaml
automation:
  - alias: Astrion 同步布局
    trigger:
      - platform: state
        entity_id: input_text.astrion_layout
    action:
      - service: text.set_value
        target:
          entity_id: text.astrion_layout
        data:
          value: "{{ states('input_text.astrion_layout') }}"
```

配置写入后面板约 3 秒自动重建实体列表（IR 码库按键、导航选项），无需重启 App。

> 注意：布局/码库/绑定是三份独立 JSON，建议各放一个 `input_text` 分别同步。

## 8. 面板 → Home Assistant 事件

原 astrion 集成的 bus 事件在 ESPHome 里以 event 实体承载（ESPHome 事件不携带
payload，需要细节时配合 `current_activity` / `panel_pages` 读取）：

```yaml
automation:
  - alias: 有人在面板上操作了电视
    trigger:
      - platform: state
        entity_id: event.panel_button_pressed
    action:
      - service: notify.mobile_app
        data:
          message: "面板按下了遥控键（当前页面：{{ states('select.current_activity') }}）"
```

面板本地新增/修改布局后，`panel_pages` 会自动上报新的页面清单；HA 发起的
`navigate`/`current_activity` 变更不会回环触发 `panel_page_visited`。

## 9. 部署到设备（HA100）

GitHub Actions 每次 push 到 master 会构建 debug APK（artifact `Astrion-debug-apk`）。
替换系统原装应用参见需求文档 §8.4（PMS 清理 + /system/priv-app 替换 + 重启）。
Manifest 已声明 HOME 类别，替换系统 launcher 后开机直接进入面板。

## 10. 屏保 / 充电动画（§3.10.7）

- **屏保**：把空闲秒数写入 `number.screen_saver_timeout` 即启用（0–600，0=关闭）。
  面板无按键/触摸达到该时长后，显示全屏深色屏保：大号时钟（HH:mm）、本地日期、
  电量（<20% 红色）+ 充电标记、HA 断连标记。任意**按键或触摸**立即退出，
  按键仅用于唤醒、不会穿透到遥控页面（与原版 ScreenSaverDialog 行为一致）。
- **充电动画**：运行中插入充电器时，屏幕上短暂显示"充电中 xx%"提示（约 3.5 秒，
  无需配置，对应原版 ChargingAnimationManager）。

## 11. 抬手唤醒（§3.8）

把 `number.raise_to_wake_threshold` 设为大于 0（建议先试 4，与原版 WakeupUtils
灵敏度一致）即启用：面板熄屏时被拿起/移动，加速度突变（相对缓变重力基线的向量差）
超过阈值就自动亮屏约 10 秒；3 秒内不重复触发，平时缓慢的转向/振动不会误触发。
阈值 0（默认）关闭该功能；无加速度计的设备上设置后自动忽略。

## 12. OTA 自更新（§3.9/§8.6）

更新包不依赖云端服务，完全由 HA 驱动：把清单 JSON 写入
`text.astrion_ota_manifest`：

```json
{"version": "1.2.1", "url": "https://example.com/Astrion-1.2.1.apk", "sha256": "<可选，APK 摘要>", "force": false}
```

- `version` 与面板已装版本做点分数值比较，只有更新时面板顶部才出现
  "发现新版本" 横幅；`force: true` 表示收到清单后立即开始安装。
- 点横幅上的"安装"或按 `button.astrion_ota_install`：下载 APK 到本地缓存 →
  （有 sha256 时）流式校验 SHA-256 → 安装。设备有 root 时经
  `su -c pm install -r`（APK 先暂存到 `/data/local/tmp/astrion_update.apk`）；
  无 root 时走 PackageInstaller session，由系统弹出安装确认，用户确认后完成。
- 安装期间横幅显示 下载中 x% → 校验 → 安装中；失败会显示原因（网络、校验、
  root 不可用等）。
- 该 text/button 实体默认隐藏（☰），需要在设备页启用后才会出现。
