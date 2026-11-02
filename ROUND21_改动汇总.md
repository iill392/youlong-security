# 第 21 轮改动汇总（2026-10-18）

本轮：内置特权界面改白色、修复指令拦截弹窗跳回安全护盾主页、改善无障碍服务在部分 ROM 不显示的问题。

---

## 一、内置特权界面改为白色

- [BrandColors.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/theme/BrandColors.kt>)：
  亮色背景/卡片改为纯白 `#FFFFFF`，容器色改为中性浅灰；
- [Theme.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/theme/Theme.kt>)：
  `AUTO` 按白色处理，只有用户显式选「深色」才保持深色；
- [ThemePreferences.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/theme/ThemePreferences.kt>)：
  默认主题从 `DARK` 改为 `LIGHT`；
- 启动窗口/splash 底色改为白色（`values/themes.xml`、`values-night/themes.xml`）；
- **指令拦截弹窗**：告警框、确认框、权限弹窗、通用 `StellarDialog` 的卡片底色
  改为 `surfaceContainerLowest`（纯白），不再用浅灰/深色；
- 管理器顶部、底部悬浮 Dock、首页状态舱等沿用浅色配色，整体为白色界面。

> 如果用户在设置里显式选择过「深色」，仍会保持深色；默认安装/未改设置的用户是白色。

## 二、修复指令拦截弹窗自动回到安全护盾主页

原因：`CommandGuardActivity` 只加了 `FLAG_ACTIVITY_NEW_TASK`，系统复用了安全护盾
主界面所在的任务栈；弹窗关闭后就露出了主页。

修复：

- manifest 给 `CommandGuardActivity` 增加独立
  `android:taskAffinity="com.youlong.hd.commandguard"`；
- [CommandGuard.kt](<E:/11/anquan/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandGuard.kt>)
  启动 flags 改为 `NEW_TASK | MULTIPLE_TASK`，强制新建独立任务，不复用主页任务；
- 弹窗关闭时 `finishAndRemoveTask()`（仅在 `isTaskRoot` 时），把空任务一起移除，
  回到调用方应用而不是安全护盾主页；
- `RequestPermissionActivity` 同样加独立 `taskAffinity`、`MULTIPLE_TASK` 与
  `finishAndRemoveTask`，避免权限弹窗出现同类问题。

## 三、部分 ROM 无障碍列表不显示安全护盾

已发现 2 例；其余用户正常。这轮做了三件事：

1. **给无障碍服务补 `android:label="@string/app_name"`**
   （[AndroidManifest.xml](<E:/11/anquan/anquan/app/src/main/AndroidManifest.xml>)），
   部分 OEM 的无障碍列表依赖 service 自身 label 才能显示条目；
2. **改进无障碍服务描述**，明确说明用于前台识别、音量键逃生和广告跳过；
3. **直接打开本服务的详情页**：
   - 新增 `buildAccessibilitySettingsIntent()`，优先使用
     `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` + `EXTRA_COMPONENT_NAME`，
     直接进入「游龙安全护盾」无障碍开关页，不再依赖一级列表能否显示；
   - 权限小栏「授权」、启动守护缺权限、`openAccessibilitySettings()` 都走这个入口；
   - HTML 权限弹窗补充路径提示：
     「列表找不到 → 先重开一次应用 → 设置 → 无障碍 → 已下载的应用/已安装的服务 →
     部分手机需在应用信息右上角允许受限设置」。

如果这 2 位用户仍看不到，需要他们提供机型和系统版本，再判断是否为厂商
「受限设置」或工作资料/双开环境限制。

## 四、版本与产物

- `versionName`：**9.2.2**
- `versionCode`：**1234567945**
- APK：[游龙安全护盾-9.2.2-release-20261017.apk](<E:/11/anquan/anquan/游龙安全护盾-9.2.2-release-20261017.apk>)
- SHA-256：`FF86553E38AC58BAADC5783365269F1BC3D204750B5A94C8A37FC1064F024715`

设备仍不在线，未做真机白色主题、弹窗任务栈和无障碍列表验证。
