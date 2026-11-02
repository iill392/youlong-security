# 第 16 轮改动汇总（2026-10-17）

三项需求：① 扫描动画 ② 恢复「超级拦截模式 / 终结模式」 ③ 日常模式更名为「Super Shield 模式」（名称黄→红渐变，黄色占 1/4）。

---

## ① 扫描动画

扫描页点「立即扫描」后不再只显示一行转圈文字，而是显示一套完整的扫描动画（全部 CSS，无额外图片）：

- **雷达**：三层同心圆 + 旋转扫描扇区（`conic-gradient`）+ 上下扫动的绿色光带 + 三个呼吸圆点 + 中央盾牌；
- **进度条**：蓝色→绿色渐变光带在轨道内循环流动；
- **状态文字**：先显示「正在读取已安装应用…」，病毒库未同步时逐秒显示「正在同步病毒库，请稍候…（n/20）」；
- **顶部盾牌**：扫描期间轻微呼吸放大；
- **最短展示 900ms**：扫描本身通常只要几百毫秒，强制动画至少完整播放一次再出结果（否则用户看不到动画）；
- 结果出来后动画自动移除，恢复原来的红/琥珀色结果卡片。

改动：`index.html` 新增 `.scan-anim / .scan-radar / .scan-progress` 样式与 `buildScanAnim()`，
`startVirusScan / doVirusScan / finishVirusScan` 接入动画与最短展示时间。

---

## ② 恢复「超级拦截模式」「终结模式」

第 8 轮按需求删除了三个高级模式（超级 / 极强 / 终结），但 **Java 侧实现一直保留**：
`ProtectService.showWarnOverlay()` 里对 `super` / `final` 的完整流程（超级拦截的
`am force-stop` 倒计时、终结模式的全面终止→选择应用→卸载/冻结→重启）都没有删。
本轮只恢复 UI 与入口，不改服务端逻辑：

| 模式 | mode 值 | 界面 | 点击行为 |
| --- | --- | --- | --- |
| 超级拦截模式 | `super` | 普通模式行 | 需内置特权 → 切换到 super |
| 终结模式 | `final` | 普通模式行（刻意低调） | 需内置特权 → **两次危险确认** → 切换到 final |
| 极强拦截模式 | `extreme` | 仍不恢复 | 老数据回落基础模式（并写回原生） |

- `index.html` 恢复 `modeSuper` / `modeFinal` 两行及 radio、`modeSuper/modeFinal` 点击处理；
- 恢复 `showFinalModeWarning(cb)`（两次警示弹窗，原来只留了一个 `__..._removed` 的壳）；
- `updateModeUI()` 支持 4 个模式；
- 模式合法性校验允许 `basic / daily / super / final`；`extreme` 或未知值回落 `basic` 并写回原生（修掉「界面基础模式、后台仍按 extreme 跑」的隐患）；
- 内置特权断开时，`daily / super / final` 都会自动降级为基础模式（与服务端 `shizukuDeadListener` 一致）。

---

## ③ 日常模式 → Super Shield 模式（黄→红渐变，黄色 1/4）

- 模式名改为 **Super Shield 模式**，保留「推荐」徽标；
- 名称用 `background-clip:text` 做渐变：
  `linear-gradient(90deg, #FFD60A 0%, #FFD60A 25%, #FF9F0A 55%, #FF3B30 100%)`
  —— 黄色占前 1/4，随后过渡到橙、红；
- 用户可见的旧文案同步：
  - 通知栏「拦截模式：日常模式」→「Super Shield 模式」（`ProtectService.currentModeName()`）；
  - 环境检测提示「无法享受日常模式的100%拦截」→「Super Shield 模式的100%拦截」（`MainActivity`）。

---

## 构建与验证

- 构建：`gradlew.bat :app:assembleRelease` 通过。
- 产物：`build_out/_app/outputs/apk/release/app-release-<时间戳>.apk`
  （根目录另存一份 `游龙安全护盾-9.1.1-release-<日期>.apk`）。
- 验证：
  - `node --check` 校验内联 JS 语法；
  - 无头浏览器 + 模拟原生桥实测：扫描动画（雷达/光带/进度条）显示正常；
    超级拦截点击后 `setShieldMode=super`、终结模式两次确认后 `setShieldMode=final` 且选中态正确；
  - Super Shield 模式名称渐变在深色推荐卡上显示为 黄→橙→红。
