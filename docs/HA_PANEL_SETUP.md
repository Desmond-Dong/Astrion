# Astrion 面板 — Home Assistant 配置指南

App 端**没有任何设置界面**：启动即运行 ESPHome 卫星服务（端口 6053），一切配置都在
Home Assistant 里通过 ESPHome 集成完成。设备上不做任何本地配置，删除应用数据也只
需要 HA 重新推送一次配置即可恢复。

---

## 1. 接入

1. 安装并打开 App（部署为系统 HOME 时开机自动进入），服务自动启动。
2. Home Assistant → 设置 → 设备与服务 → ESPHome 集成，采纳发现的设备
   （或手动用设备 IP + 端口 6053 添加）。

采纳后设备上会出现这些实体（全部由 HA 侧控制）：

| 实体 | 类型 | 用途 |
|---|---|---|
| `astrion_layout` | text | **面板布局 JSON**（房间/卡片/设备树，见 §2） |
| `astrion_ir_codes` | text | **IR 码库 JSON**（见 §3） |
| `astrion_key_bindings` | text | **物理按键绑定 JSON**（见 §4） |
| `navigate` | select | A 型复位选择器：选房间名 → 面板跳到该房间页（300ms 后自动复位） |
| `current_activity` | select | B 型持久选择器：镜像面板当前页面，供自动化读取 |
| `reconnect_ha` | button | 触发面板重连 |
| `media_player` | media_player | 语音卫星音频 |
| `mute_microphone` | switch | 麦克风静音 |
| `enable_wake_sound` / `repeat_timer_sound` | switch | 语音提示音 |
| `wake_word` / `second_wake_word` / `stop_word` | select | 唤醒词/停止词选择（`second_wake_word` 选 `None` 表示不启用） |
| `media_title` / `media_artist` | text_sensor | 面板媒体元数据 |
| `<设备名>`（每个码库设备） | infrared | 直发原始时序（ESPHome infrared 服务） |
| `<按键> (<设备名>)`（每个码库按键） | button | 单键红外发射 |

## 2. 面板布局 `astrion_layout`

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

## 3. IR 码库 `astrion_ir_codes`

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

## 4. 物理按键绑定 `astrion_key_bindings`

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

## 5. 配置下发自动化示例

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

## 6. 部署到设备（HA100）

GitHub Actions 每次 push 到 master 会构建 debug APK（artifact `Astrion-debug-apk`）。
替换系统原装应用参见需求文档 §8.4（PMS 清理 + /system/priv-app 替换 + 重启）。
Manifest 已声明 HOME 类别，替换系统 launcher 后开机直接进入面板。
