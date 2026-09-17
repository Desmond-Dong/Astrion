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
| `vad_threshold` | number | 本地说话结束检测（设备端 VAD）静音阈值 0–0.1（步进 0.001，默认 0.008 = 满幅的 0.8%）；`0` = 关闭本地检测，回退为等待 HA 侧 VAD（见 §3） |
| `vad_timeout` | number | 本地说话结束检测的静音时长（秒，0.3–5，步进 0.1，默认 1.2）；说完话静音达到该时长后面板主动结束语音上传 |
| `wake_assistant` | button | 远程触发一次免唤醒对话（等同按设备麦克风键） |
| `noise_suppression` / `echo_cancellation` / `auto_gain` | switch ☰ | 硬件降噪/回声消除/自动增益（机带麦克风，挂载到采集 session，设备不支持时自动跳过）。麦克风固定为机带默认源，语音固定外放，无需选择 |
| `media_title` / `media_artist` | text_sensor | 面板媒体元数据 |
| `infrared` | infrared | **硬件红外发射器**（始终存在）：HA 原生红外直接调用，`remote.send_command`/自动化发原始时序都走它 |
| `<设备名>`（每个码库设备） | infrared | 码库设备的原始时序直发（ESPHome infrared 服务） |
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
- **麦克风**：固定使用机带麦克风（原官方应用同款调用），无需选择；硬件
  降噪/回声消除/自动增益默认开启，可用开关逐项调节。
- **说话结束判定（本地 VAD）**：面板在推流的同时做静音检测——说完话（静音持续
  `vad_timeout` 秒、且前面有真实语音，音量阈值 `vad_threshold`）即向 HA 发送
  "音频结束"帧并进入识别阶段，**不依赖 HA 侧 VAD**。部分 HA 配置/设备上服务端
  VAD 始终不触发，表现为一直停在"聆听中"、没有任何响应，此时保持默认即可。
  对话收尾太快（说话中间停顿被截断）就调大 `vad_timeout`；环境太吵把末尾噪声
  当语音就调大 `vad_threshold`；设 `vad_threshold = 0` 可完全关闭本地检测。
- **外放**：语音一律外放（扬声器），TTS/媒体通过 `media_player` 实体播报，
  音量/静音在 HA 中直接调节。

## 4. 面板布局 `astrion_layout`（推荐：一行一个房间，无需 JSON）

**最简单写法**——`房间=实体1, 实体2`，多条记录用 **分号** 隔开（HA 的 text
实体是单行输入框，分号就是"换行"），类型/图标/状态全部自动：

```
客厅=remote.mi_tv, media_player.mi_tv | 电视; 卧室=light.bed, fan.bed
```

多行粘贴同样支持：

- `| 后面` 是该行唯一设备卡的显示别名（可省略）
- 不带 `=` 的行表示"所有设备"房间：`light.ceiling, climate.ac`
- `#` 开头为注释
- 实体类型自动推断：light/climate/fan/cover/media_player/remote(→电视)/switch/scene/weather
- 这些实体同时自动加入状态订阅，无需再填 `sync_entities`

写进 `astrion_layout` 文本实体即生效。**只推 `astrion_ir_codes` 也行**——布局为空时会用码库自动生成"所有设备"房间。

### 完整 JSON 形态（高级，可选）



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

## 5. 红外：推荐走 Home Assistant 原生红外（ESPHome infrared 协议）

每个码库/设备都会以 **ESPHome `infrared` 实体**暴露，在 HA 2026.9+ 中即为
**原生红外遥控器**：直接在 HA 里给这个 remote 实体配置命令（原生 UI/自动化/
Broadlink 迁移均可），面板按键时自动 `remote.send_command` → HA →
`infrared.transmit` → 面板硬件发射。**面板里不需要填任何红外码。**

面板电视卡按键的派发顺序：本地码库命中 → 本地直发；否则 → `remote.send_command`
交给 HA（原生红外即走这条）。

### 可选：面板本地码库 `astrion_ir_codes`

若想让某个设备脱离 HA 直接本地发射，才需要推送本地码库。支持极简行格式
（一行一个按键，`设备 | 按键=码`）：

```
# 注释
小米电视 | POWER=38000,9000,4500,560,560,560,1690,...
小米电视 | MUTE=sGipAAECAwQFBgcICQ==
机顶盒 | POWER=JgBMACHgERAQERAAHQAA
```

码串支持三种格式（自动识别）：逗号时序、Broadlink base64、AES base64。
设备名与布局里 tv 卡的 `name` 一致时按键本地直发。也兼容完整 JSON：
`{"小米电视": {"POWER": "...", "MUTE": "..."}}`。

每个本地码库设备同时暴露一个 `infrared` 实体（原始时序直发）和逐键 `button`。

## 6. 物理按键绑定 `astrion_key_bindings`（推荐：一行一键，无需 JSON）

**最简单写法**——`键码=目标`，多条记录用分号隔开（目标直接填实体 id，动作按域自动推断）：

```
132=home; 135=light.ceiling; 136=scene.film; 132_long=climate.ac; 93=room:客厅; 96=voice
```

- `键码_long=` 表示长按（800ms），不带后缀为短按
- `home`/`voice`/`room:标题`/`card:卡片id` 是特殊动作；实体 id 则按域自动
  推断（`scene`/`script` → 执行，`switch`/`light`/`fan` → 按当前状态开/关）

- `键码_long=` 表示长按（800ms），不带后缀为短按
- 实体 id 动作按域自动推断：`scene`/`script` → 执行；`button` → 按压；
  `switch`/`light`/`fan` 等 → 按当前状态开/关切换
- 也可用完整 JSON 形态（高级，可选）：



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
