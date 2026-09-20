# Astrion 面板 — Home Assistant 直连配置指南（零基础版）

面板通过 **Home Assistant WebSocket API** 直连 HA（长寿命访问令牌认证），配合
**Astrion Remote 集成**完成配对、布局与红外码库下发。没有 ESPHome、没有模板蓝图、
没有 text 实体，所有配置都在集成页面里点选完成。

## 1. 这个面板是怎么工作的（30 秒看懂）

- **连接**：面板 → HA 的 WebSocket（`ws://HA地址:8123/api/websocket`），用你在
  面板上填的**长寿命访问令牌**认证；断线自动重连。
- **房间和设备**：全部由 HA 决定。在 Astrion 集成里按**分类**添加子条目并勾选设备，
  面板自动拉取（`astrion/get_cards`）并按分类分页渲染。
- **红外**：面板自带发射头。码库由集成下发（`astrion/get_device_codes`），按键本地
  直发；没有码的键回落 HA `remote.send_command`。
- **面板上能直接调的**：快捷键绑定（长按实体键进绑定页）、屏保时长、抬手唤醒、
  音量/亮度（下拉快捷面板）。

---

## 2. 零基础上手（跟着点就行）

**第 1 步 · 装 HA 集成**
- HACS → 右上角 ⋮ → 自定义存储库 → 填
  `https://github.com/Desmond-Dong/Astrion-integration`，类别选 Integration；
- 安装 **Astrion Remote**，重启 HA；
- 或手动：把仓库里 `custom_components/my_ir` 拷到 HA 的 `config/custom_components/my_ir`。

**第 2 步 · 建长寿命令牌**
浏览器打开 HA → 点左下角你的头像（个人资料页）→ 安全 →
**长寿命访问令牌 → 创建令牌**，复制保存（只显示一次）。

**第 3 步 · 面板连接 HA（网页配对，免键盘）**
面板屏幕太小，不适合敲 180 字符的令牌——所以面板自带**网页配对**：

- 面板未配置时会自动开启配对服务（也可在 下拉快捷面板 → 连接设置 → 手动开关）；
- 用**手机/电脑浏览器**打开面板上显示的地址：`http://<面板IP>:8917`；
- 网页表单里填 HA 地址、端口，**粘贴**第 2 步的令牌 → 保存并连接；
- 面板立即重连（下拉快捷面板可看到"Home Assistant 已连接"）。

> 也可以在连接设置页手动输入（键盘能用的场景）。

**第 4 步 · 完成配对**
HA → 设置 → 设备与服务 → **Astrion Remote** → 添加：
- 选择对话代理（用默认 Assist 管线即可）；
- 集成广播发现后，列表里会出现本面板（`Smart Remote SN:xxxx`），选中完成。

**第 5 步 · 建分类卡片**
在 Astrion Remote 集成页面 → **添加子条目**，选分类并勾选设备：
- 灯光 / 开关 / 风扇 / 温控 / 窗帘 / 媒体播放器：多选设备即可；
- TV：三步向导（选电视设备与遥控类型 → 电源/音量/媒体键 → F4-F11 物理键绑定）；
- 场景/脚本、天气、主机：直接选实体。

每分类全局一个子条目，重复添加会自动并入。保存后面板几秒内自动刷新出
房间和卡片，拿起就能用。

---

## 3. 红外遥控

面板本地发射优先：电视卡按了某键后，面板先查本地码库（按键名匹配），
没有码再让 HA 走 `remote.send_command`。码库来源：

1. **集成云码库（推荐）**：Astrion Remote 集成卡片 → **配置** → 按库/分类/品牌
   选择驱动，保存即生成 `remote.*` 实体并写入码库；TV 子条目的
   remote_entities 绑定该实体后，面板自动经 `astrion/get_device_codes` 拉取。
2. **博联 Broadlink**：在 HA 学习/导入博联码后，TV 子条目可直接绑定
   `remote.broadlink_*` 实体，按键按 `设备/按键` 语义执行。

HA 侧主动发码：调用 `astrion.send_command`（`entity_id: remote.xxx`、
`button: 电源` 或原生长码），事件 `astrion/control_command` 会下发到面板发射。

---

## 4. 导航与自动化

- **HA → 面板**：集成自动创建 `select.*_navigate`（A 型，选完 300ms 复位）与
  `select.*_current_activity`（B 型持久）两个实体，自动化设置它们的选项即可让
  面板跳页（事件 `astrion/navigate_to`）。
- **面板 → HA**：面板上报以下事件（自动化 → 触发器 → 事件）：
  - `astrion/page_visited`（data：`serial_number` / `page` / `source=user`）
  - `astrion/navigate_list_upload`（data：`serial_number` / `pages`，页面清单）
  - `astrion/key_pressed` / `astrion/key_long_pressed` / `astrion/button_pressed`
    （data：`serial_number`）——任意物理键都可被自动化绑定。

示例：按 F4 面板键时开灯——
```yaml
automation:
  - triggers:
      - trigger: event
        event_type: astrion/key_pressed
    actions:
      - action: light.toggle
        target:
          entity_id: light.ceiling
```

---

## 5. 面板本机设置（下拉快捷面板）

| 项目 | 说明 |
| --- | --- |
| 音量 / 亮度 | 系统媒体音量、屏幕亮度 |
| 刷新设备 | 断开并重连 HA WebSocket，重拉布局与码库 |
| 息屏 | 立即熄屏 |
| 抬手唤醒 | 熄屏时拿起亮屏的开关 |
| 屏保 | 0–600 秒空闲超时，0=关闭 |
| 快捷键绑定 | F4–F11 实体键绑定设备/场景/房间，长按实体键直接进绑定页 |
| 连接设置 | HA 地址 / 端口 / 访问令牌 / 设备名 |

---

## 6. OTA 自更新

HA 侧触发事件即可让面板弹出更新横幅：

```yaml
- action: fire_event
  event: astrion/ota_manifest
  event_data:
    version: "1.2.1"
    url: "https://example.com/Astrion-1.2.1.apk"
    sha256: "<apk 的 sha256>"
    force: false   # true = 不弹横幅直接安装
```

点横幅（或 `force: true`）后：下载 → SHA-256 校验 → 安装（root 直装，
无 root 弹系统安装确认）。

---

## 7. 故障排查

| 现象 | 处理 |
| --- | --- |
| 连接设置显示"未配置…" | 地址/令牌没填或为空，回连接设置补全 |
| 显示"访问令牌无效" | 令牌复制错误或已失效，重新创建 |
| 显示"连接失败"反复出现 | 地址/端口不对；HA 未监听该端口；或面板与 HA 不同网段 |
| 已连接但"暂无设备" | 还没完成配对（第 4 步）或没建分类子条目（第 5 步）；也可点"刷新设备" |
| 配对列表里看不到面板 | 面板"已连接"吗？未连接先解决连接；再点集成添加页刷新（面板会响应 `astrion/pair_request`） |
| TV 键按了没反应 | 该键无本地码且未绑 remote 实体；检查 TV 子条目第二步的绑定 |
| 布局改了面板没变 | 集成保存后等几秒自动拉取；或下拉"刷新设备" |

面板侧完整日志：`adb logcat -s PanelService HaPanelBridge`。
