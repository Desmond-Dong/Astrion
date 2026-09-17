# Astrion 面板 — ESPHome / Home Assistant 配置指南（零基础版）

## 1. 这个面板是怎么工作的（30 秒看懂）

面板 = 一台**自动运行的遥控器** + 一个 **Home Assistant 的 ESPHome 设备**。

- **连接**：面板和手机连同一个 WiFi，打开 HA → 设置 → 设备与服务 → ESPHome，点面板旁边的"**采纳**"就完成了。**不填地址、不用令牌、没有配对步骤**。
- **房间和设备**：全部由 HA 决定。导入"自动布局"蓝图（§3）并勾选想上屏的设备即可，面板按家里的房间自动分组；不勾就什么都不显示。
- **面板上能直接调的**：快捷键绑定（长按实体键就进绑定页，§5）；屏保时长、抬手唤醒（下拉快捷面板里滑一下，§8）。

---

## 2. 零基础上手（跟着点就行）

**第 1 步**：面板插电，让它和你的手机/电脑连**同一个路由器**（面板首次连 WiFi：面板上下拉快捷面板 → 系统设置，进入系统 WiFi 设置）。

**第 2 步**：在手机或电脑浏览器打开 HA → **设置 → 设备与服务** → 页面上方的 **ESPHome**：
- 列表里会自动出现本面板，点 **采纳**；
- 没看到就点集成页的 **+ 设备 / 其他**，地址填面板上显示的 `IP`，端口 `6053`（面板 IP 在 面板下拉快捷面板 → 网络 处可见；未采纳时面板首页也会大字显示）。

**第 3 步**：完成。面板自动出现设备，拿起就能遥控。

到此已经可以正常用了。想分房间、加电视/空调遥控、绑物理按键、开屏保，往下看对应章节，每件事都只需要"往一个框里填一句话"或"点几下屏幕"。

> 写文本类实体时的唯一规矩：**输入框是单行的，不能换行**。本文章节里的多行示例只是为了易读，实际填写时用分号 `;` 把多条连成一行。

---

## 3. 房间与设备：`astrion_layout`（推荐用蓝图，不用手写）

**自动布局（推荐）**：在 HA 里导入本仓库的蓝图
`blueprints/automation/astrion_panel_layout.yaml`（设置 → 自动化与场景 → 蓝图 →
右上角"导入蓝图"，粘贴该文件的 GitHub 链接）。创建自动化时，**像原版卡片设置一样
按类别勾选想出现在面板上的设备**——灯、开关、空调、窗帘、风扇、媒体播放器、
电视遥控、场景…每个类别可多选。之后面板自动按家里的房间分组显示，设备沿用
HA 里的名字；HA 重启、每天凌晨 4 点、设备变动时自动刷新。不勾的类别不上屏。

同一个蓝图还能顺手配置**物理键绑定**：勾选"面板按键绑定实体"后，灯/窗帘/音乐/
空调四个专属键自动绑到各类勾选的第一台设备；也可以在"灯按键绑定的设备"等
输入框里指定任意一台同步到面板的设备（单选）；需要完全自定义时在"自定义按键
绑定"里写一行式（如 `134=light.a; 96=voice`），它会覆盖前面全部设置。

**手动写法**：往 `text.astrion_layout` 填一行：

```
客厅=remote.tv, media_player.tv | 电视; 卧室=light.bed, fan.bed
```

含义：`房间=实体ID, 实体ID`，多个房间用分号隔开。规则：

- 实体 ID 在 HA 里点开任意设备详情页就能看到（齿轮图标旁）；
- `| 后面` 是显示别名，可不写；
- 不带 `=` 的一行 = "所有设备"房间；
- `#` 开头是注释；分号就是换行；
- 设备类型（灯/空调/窗帘/风扇/电视…）从实体自动识别，不用指定；这些实体也会被自动订阅状态。

改完约 3 秒面板自动刷新，不用重启。

<details>
<summary>高级：完整 JSON 结构（仅供参考，必须压成一行下发）</summary>

```json
{"rooms": [{"title": "客厅", "cards": [{"type": "tv", "uuid": "tv1", "name": "客厅电视", "tv_type": "android_tv", "entities": [{"key": "POWER", "entity_id": "remote.tv", "value": "POWER"}, {"key": "VOLUME_UP", "entity_id": "media_player.tv"}]}]}], "pages": ["全屋"], "sync_entities": ["light.ceiling", "climate.ac.temperature"]}
```

字段：card `type`（tv/light/fan/scene/media-player/climate/cover/switch/weather/host/switch-monitor）、`uuid`（页面路由 id，缺省自动生成）、`tv_type`（android_tv 或 apple_tv）、`percentage_step`（风扇档位步长）、entities[].`key`/`value`（电视卡按键名与实际红外命令名）、`alias`（显示名）、`pages`（附加导航页）、`sync_entities`（额外订阅的实体属性，支持 `实体ID.属性` 形式）。日常用上面的行格式就够了。
</details>

---

## 4. 红外遥控

面板自带红外发射头，两种用法（可同时用）：

1. **HA 原生红外（推荐，面板里零配置）**：采纳后面板会以 ESPHome `infrared` 实体出现，在 HA 2026.9+ 就是一个原生红外遥控器——在 HA 里给它配命令（原生 UI / 自动化 / 从旧遥控器迁移都行），面板直接可用。
2. **面板本地码库 `text.astrion_ir_codes`（可选）**：想让某些键不经过 HA、面板直接发射时才需要。格式 `设备 | 按键=码`，一行一个键（实际填成一行，分号分隔）：

```
客厅电视 | POWER=38000,9000,4500,560,560,560,1690,...; 客厅电视 | MUTE=sGipAAECAwQFBgcICQ==
```

码串自动识别三种格式：逗号时序、Broadlink base64、AES base64。码库里的设备名和布局里电视卡的 `name` 一致时，按该键面板直接本地发射；不一致或没配就走 HA（第 1 种方式）。

---

## 5. 快捷键绑定（面板上点几下就完成）

每个物理键**天生专属一类设备**（和原装键位一致）：

| 物理键 | 只能绑定 | 没绑定时短按 | 长按 |
|---|---|---|---|
| 灯按键 | 灯 | 打开第一张灯卡片 | 进绑定页 |
| 窗帘按键 | 窗帘 | 打开第一张窗帘卡片 | 进绑定页 |
| 音乐按键 | 媒体播放器 | 打开第一张媒体卡片 | 进绑定页 |
| 空调按键 | 空调 | 打开第一张空调卡片 | 进绑定页 |
| 自定义按键一~四 | 场景/脚本 | 进绑定页 | 进绑定页 |

**怎么绑**（两种入口任选）：
- 面板上下拉快捷面板 → **快捷键绑定**，选一个键；
- 或者**直接长按那个物理键**，立刻进入它的绑定页。

绑定页里选一个目标 → **保存**。不选中直接保存 = 解除绑定（再点一次已选中的也能取消）。

绑定后的效果：灯/开关/风扇 = 开关切换；场景/脚本 = 执行；空调/窗帘/电视/媒体 = 直接打开它的遥控页；房间 = 跳到该房间。

**设备页里物理键自动变成该设备的遥控**（进了空调页就是温度加减，进了灯页就是亮度）：

| 键 | 空调页 | 风扇页 | 灯页 | 电视页 | 媒体页 | 窗帘页 |
|---|---|---|---|---|---|---|
| 音量 +/− | 温度 +/− | 风量 +/− | 亮度 +/− | 音量 +/− | 音量 +/− | 位置 +/− |
| 通道 −/+ | 风速切换 | — | 色温 −/+ | 频道 −/+ | 上/下曲 | 翻转 −/+ |
| OK | — | — | — | 确认 | 播放/暂停 | 停止 |
| 电源键 | 开关 | 开关 | 开关 | — | — | 开/关 |

（步进类按键短按小步、长按大步自动连发。）

<details>
<summary>高级：用 HA 批量下发按键绑定 <code>text.astrion_key_bindings</code></summary>

一行式：`132=home; 135=light.ceiling; 136=scene.film; 132_long=climate.ac; 93=room:客厅; 96=voice`（`键码_long=` 为长按；`home`/`voice`/`room:标题`/`card:卡片id` 是特殊动作；实体 id 按域自动动作）。完整 JSON：`{"bindings": [{"keycode": 135, "action": "card", "target": "tv1"}]}`，`action` 可为 `room`/`card`/`service`/`home`/`voice`。HA 推送的绑定优先于面板本地绑定。
</details>

---

## 6. 语音

- **免唤醒对话**：按住面板的语音键（HA100 上是键码 133）直接说话，说完自动结束，不需要唤醒词。
- **唤醒词**：在设备实体的 `wake_word` / `second_wake_word` 下拉里选；`wake_word_sensitivity` 调灵敏度（默认 0.03，调大更灵敏但误唤醒变多）。
- **说完没反应**：面板自带"说完检测"（`vad_timeout` 静音秒数，`vad_threshold` 静音判定音量），默认即可用；说话被打断就调大 `vad_timeout`，环境吵就调大 `vad_threshold`，设 `vad_threshold = 0` 关闭本地检测。
- 麦克风固定用机带麦克风；降噪/回声消除/自动增益默认开启，可用开关逐项调节。语音一律外放，音量在 HA 或面板下拉里调。

---

## 7. 面板上会出现的实体清单（自动生成，点开即用）

| 实体 | 用途 |
|---|---|
| `astrion_layout` | 房间/设备布局（§3） |
| `astrion_ir_codes` | 面板本地红外码库（§4） |
| `astrion_key_bindings` | 批量下发按键绑定（§5 高级） |
| `navigate` | 选一个房间名 → 面板跳过去（选完自动弹回） |
| `current_activity` | 显示面板当前页面，供自动化读取 |
| `reconnect_ha` | 让面板重连 HA |
| `wake_assistant` | 远程触发一次免唤醒对话 |
| `media_player` | 语音/TTS 音频外放 |
| `mute_microphone` / `enable_wake_sound` / `repeat_timer_sound` | 麦克风静音 / 语音提示音 |
| `wake_word` / `second_wake_word` / `stop_word` / `wake_word_sensitivity` | 唤醒词与灵敏度（§6） |
| `vad_threshold` / `vad_timeout` | 说完检测参数（§6） |
| `noise_suppression` / `echo_cancellation` / `auto_gain` ☰ | 降噪 / 回声消除 / 自动增益 |
| `media_title` / `media_artist` | 面板正在播放的媒体信息 |
| `infrared` | 硬件红外发射器（HA 原生红外，§4） |
| 码库里的每个设备 / 每个按键 | 对应一个红外实体 / 一个按键按钮 |
| `panel_page_visited` / `panel_button_pressed` / `panel_last_key` / `panel_pages` | 面板事件与状态，供自动化联动（§11） |
| `screen_saver_timeout` | 屏保：空闲多少秒后显示全屏时钟（0–600 秒，0=关）；也可在面板下拉里滑 |
| `charging_display` | 充电空闲时显示**屏保**还是**熄屏**（§8） |
| `raise_to_wake_threshold` ☰ | 抬手唤醒：拿起面板自动亮屏（0–10，建议先试 4，0=关） |
| `astrion_ota_manifest` / `astrion_ota_install` ☰ | OTA 更新清单 / 触发安装（§9） |

（标 ☰ 的高级实体默认隐藏，在实体设置里打开"显示"即可。）

---

## 8. 屏保 / 充电 / 抬手唤醒

- **屏保**：面板下拉快捷面板里直接滑"屏保"时长（30 秒步进，关=关闭），或把空闲秒数填进 `number.screen_saver_timeout`（0–600，0=关）。到时间面板显示全屏系统时钟/日期/电量，碰一下或按任意键即退出，不会误触遥控。
- **充电时**：在设备实体 `select.charging_display` 里选择充电空闲时显示**屏保**（默认）还是**熄屏**（平台支持时真熄屏，否则纯黑整屏）；轻点或按键即恢复。插拔充电器时仍会短暂显示电量提示。
- **抬手唤醒**：快捷面板开关一键开（阈值固定 4），或把 `number.raise_to_wake_threshold` 设为大于 0：面板熄屏时被拿起自动亮屏约 10 秒；误触发多就调大数值。

---

## 9. OTA 自更新

更新完全由 HA 驱动，不需要碰面板：把一行 JSON 写进 `text.astrion_ota_manifest`：

```json
{"version": "1.2.1", "url": "https://example.com/Astrion-1.2.1.apk", "sha256": "<可选，APK 摘要>", "force": false}
```

版本比面板当前新时，面板顶部出现"发现新版本"横幅；点"安装"（或按 `button.astrion_ota_install`）自动 下载 → 校验 → 安装。有 root 静默安装，无 root 弹系统安装确认。

---

## 10. 进阶：长配置的自动化下发

文本实体输入框短且单行。需要很长的布局/码库时，把内容放在 HA 的 `input_text`（长度可到 10000）里，再用一个自动化同步：

```yaml
automation:
  - alias: 同步面板布局
    triggers:
      - trigger: state
        entity_id: input_text.astrion_layout
    actions:
      - action: text.set_value
        target:
          entity_id: text.astrion_layout
        data:
          value: "{{ states('input_text.astrion_layout') }}"
```

（旧版 HA 用 `service:`/`service:` 写法，含义相同。）布局、码库、绑定建议各放一个 `input_text` 分别同步。

---

## 11. 面板 → HA 事件（想做联动时看）

面板上的操作会以事件实体上报，直接在自动化里当触发器用：

- `event.panel_button_pressed`：面板上的遥控键 / 任意物理键（短按 `key_pressed`、长按 `key_long_pressed`）；
- `event.panel_page_visited`：用户在面板上跳页；
- `panel_last_key`：最后按下的键码（如 `135` / `135_long`）；
- `panel_pages` / `current_activity`：当前页面清单 / 当前页面。

示例：有人在面板上操作了电视就发通知——

```yaml
automation:
  - alias: 面板操作提醒
    triggers:
      - trigger: state
        entity_id: event.panel_button_pressed
    actions:
      - action: notify.notify
        data:
          message: "面板按下了遥控键（当前页面：{{ states('select.current_activity') }}）"
```

---

## 12. 部署到面板硬件

GitHub Actions 每次 push 自动构建 APK（artifact `Astrion-debug-apk`）。正式部署：替换系统原装应用（/system/priv-app），面板 manifest 已声明 HOME 类别，开机直接进入本面板。
