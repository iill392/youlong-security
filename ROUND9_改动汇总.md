# 第 9 轮改动汇总（2026-10-01）

## 1. 状态栏在部分机型上透明 → 看不到时间 ⚠️ 本轮最重要

### 根因（四处硬编码 + 缺夜间资源）

| 位置 | 原值 | 问题 |
| --- | --- | --- |
| `values/themes.xml` | `android:statusBarColor = @color/white` | 纯白，与页面底色 `#F2F2F7` 不一致 |
| `values/themes.xml` | `android:windowLightStatusBar = true` | 写死深色图标，**没有 values-night** |
| `layout/activity_main.xml` | 根布局 + `statusBarSpacer` 背景 `@color/white` | 同上，且无夜间版本 |
| `MainActivity` 进出全屏 | 只 `set/clearFlags(FLAG_FULLSCREEN)` | 之后没有重新同步状态栏外观 |

夜间模式下 `Theme.Material3.DayNight` 会把页面背景变深，而状态栏图标仍是深色
（`windowLightStatusBar=true` 且无 night 覆盖）→ 时间与系统图标压在深色背景上，
就看不见了。国产 ROM 上还会出现「状态栏透明」的表现，同样是这条链路。

### 修法

**新增资源（白天/夜间成对）**

```
app/src/main/res/values/colors_window.xml
    <color name="app_window_bg">#FFF2F2F7</color>   <!-- 与 index.html body 背景一致 -->
    <bool  name="app_light_status_bar">true</bool>  <!-- 浅底 → 深色图标 -->

app/src/main/res/values-night/colors_window.xml
    <color name="app_window_bg">#FF1C1C1E</color>
    <bool  name="app_light_status_bar">false</bool> <!-- 深底 → 浅色图标 -->
```

**`MainActivity` 新增 `syncStatusBarAppearance()`**

- 从资源读 `app_window_bg` / `app_light_status_bar`（随深浅色自动解析）；
- `setStatusBarColor` + `setNavigationBarColor` 设**不透明实色**，直接堵死"透明状态栏"；
- 同时用两套 API 设图标明暗，覆盖新老机型：
  - 旧：`SYSTEM_UI_FLAG_LIGHT_STATUS_BAR`（API 23+）、
    `SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR`（API 26+）
  - 新：`WindowInsetsController.setSystemBarsAppearance`（API 30+）
- 调用点：`onCreate`（setContentView 之后）、`onHideCustomView`（退出全屏后）、
  `onConfigurationChanged`（深浅色切换后）；
- 全程 try/catch，状态栏只是外观，绝不影响主流程。

**`values/themes.xml` 与 `layout/activity_main.xml`**：把写死的 `@color/white`
与 `true` 换成上面两个资源，作为运行时设置的兜底。

### 真机验证（vivo V2133A / Android 14 / 浅色模式）

截图确认：状态栏底色为浅灰（与页面一致）、时间 `11:26` 与右侧系统图标清晰可见。
（深色模式下的表现需你切到系统深色再确认一次，逻辑上会解析到 `#1C1C1E` + 浅色图标。）

---

## 2. 环境异常提示改为「一直无法关闭」

`index.html` 的 `refreshEnvWarning()`：

- 删掉「（点此不再提示）」文案；
- 删掉 `localStorage` 记忆（`env_warn_ack_*`）；
- 删掉卡片的 `onclick` 关闭逻辑；
- 现在：环境异常 → 卡片常驻；环境恢复正常（`level === 'none'`）→ 自动消失。

> 理由：装了 Dhizuku / 手机有 root 意味着日常模式拿不到 100% 拦截，
> 属于必须让用户持续看到的信息，不该允许一键隐藏。

## 3. 删除「开启实时守护」时的提示弹窗

删除 `showGuardWarning()`（函数体 + 调用点）。原弹窗内容：
「温馨提示 / 建议**日常尽量不要开启**，可能会**误伤**。」

现在打开实时守护**不再弹任何提示弹窗**。

真机验证：点击开关后 `uiautomator` 文本里不再出现「温馨提示」「尽量不要开启」。

## 4. 「日常模式」改名为「日常模式2.0（100%拦截）」

`index.html` 模式项名称：
`日常模式 推荐` → **`日常模式2.0（100%拦截） 推荐`**

真机验证：`uiautomator` 显示 `日常模式2.0（100%拦截） 推荐`。

## 5. 日常模式说明补「误伤率基本没有」

`index.html` 模式说明：
`全网首发，病毒拦截率为100%`
→ **`全网首发，病毒拦截率为100%，误伤率基本没有`**

真机验证：文本原样出现。

---

## 6. 运行期权限降级（上一轮需求，本轮一并交付）

`ProtectService.java` 新增：

| 方法 | 作用 |
| --- | --- |
| `monitorPermissionDegrade()` | 在每 4 秒一轮的 tick 里巡检无障碍/悬浮窗权限；**只在状态变化时**提示与切换 |
| `canDrawOverlays()` | 悬浮窗权限判定 |
| `launchAppForRescue(reason)` | 弹不出覆盖层时**直接跳转到本应用**（`startActivity` → 失败则全屏 Intent 通知兜底） |
| `launchAppForRescueByNotification()` | 全屏 Intent + 高优先级通知，锁屏/后台也能把用户带回应用 |

行为：

- **无障碍丢失** → 自动把 `shake_trigger_on=true`（切到摇一摇）+ Toast 提示；
  若原本开着音量键触发，**临时关闭**并记住，权限恢复后自动还原。
- **悬浮窗丢失** → Toast 提示「触发时会直接跳转到游龙安全护盾」。
- **两个都丢** → 追加一条合并提示。
- **触发流程不变**：摇一摇/音量键仍走 `triggerVolumeRescue()`，
  只是覆盖层失败时改为拉起 `MainActivity` 继续流程。

---

## 构建与安装

```
JAVA_HOME=D:\scoop\apps\temurin21-jdk\21.0.12-8.0
gradlew :app:assembleRelease    → BUILD SUCCESSFUL in 6m 31s
app-release-20261001-111355.apk  37.9 MB
pm install -r -d  → Success   (lastUpdateTime=2026-10-01 11:26:16)
```

包内确认：`bool/app_light_status_bar` (0x7f050002)、
`color/app_window_bg` (0x7f06001d) 已进资源表，
主题里三处 `0x01010451/0x01010452/0x010104e0` 也已指向新资源。

## 本轮又踩到的一个坑（第二次）

**XML 注释里不能出现连续两个减号。** 我在 `colors_window.xml` 与
`themes.xml` 里用了 `-----` 做分隔线，`aapt2` 直接报
「注释中不允许出现字符串 "--"」。已全部换成等号分隔线，
并加了 `tools/fix_xml_dashes.js` 做兜底检查。
