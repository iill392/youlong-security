# 第 18 轮改动汇总（2026-10-17）—— 内置特权界面整体重写

本轮把「内置特权」管理器（`roro.stellar.manager`，即点「打开特权服务管理器」后看到的界面）的 UI 层整体重写，功能逻辑全部保留。

---

## 一、视觉方向：从「Material 蓝色卡片」改为「深色控制台」

| 项目 | 之前 | 现在 |
| --- | --- | --- |
| 主色 | 护盾蓝 #007AFF / 白底 | 霓虹薄荷 #3DF5B6 + 电光青 #6FD3FF + 琥珀 #FFC857 |
| 背景 | 纯色 surface | 近黑 #060A0F，整页三段式深色渐变 |
| 标题字体 | 系统无衬线 | **等宽字体 + 拉开字距**（`ConsoleTypography`） |
| 卡片圆角 | 24 / 28dp 大圆角 | 8~22dp 硬朗圆角（`Shape.kt`） |
| 默认主题 | 跟随系统 | **深色**（设置里仍可切浅色 / 跟随系统） |
| 底部导航 | Material NavigationBar 贴底 | **悬浮玻璃 Dock + 霓虹渐变选中胶囊** |
| 顶栏 | Material LargeTopAppBar | **自绘控制台顶栏**：渐变盾牌块 + 等宽标题 + `PRIVILEGE CONSOLE` 副标签 + 细分割线 |

主要文件：

- [BrandColors.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/theme/BrandColors.kt>)：全新深/浅两套控制台色板
- [Theme.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/theme/Theme.kt>)：等宽字体、圆角、状态栏/导航栏明暗
- [Shape.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/theme/Shape.kt>)：更硬朗的 AppShape
- [ThemePreferences.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/theme/ThemePreferences.kt>)：默认深色

---

## 二、首页完全重写

[HomeScreen.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/features/home/HomeScreen.kt>) 从「大标题 + Material 卡片网格」改成控制台结构：

- **状态舱**：`ONLINE / OFFLINE`、运行状态、`ACTIVE / IDLE` 胶囊、`CORE VERSION` / `RUN MODE` 两块指标、启动/停止（停止为红色）与重启按钮；
- **`// 启动方式`**：Root / 无线调试 / 有线 ADB 三行紧凑指令条（图标 + 标题 + 说明 + 箭头），不再是大块卡片；
- **ADB 受限特性**：红色提示条 + 「查看」按钮；
- 底部 `SERVICE CORE · ROOT/ADB CHANNEL` 装饰行；
- 原有三个弹窗（停止服务 / 复制 ADB 命令 / 受限特性）全部保留。

功能映射完全没变：
- 启动 → `onNavigateToStarter(...)`（无线调试仍走原 Starter）；
- 停止 → `Stellar.exit()`；
- 重启 → `homeViewModel.restartService()`；
- 有线 → 复制 `Starter.adbCommand`。

---

## 三、导航 / 顶栏 / 背景

- [BottomNavigationManager.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/navigation/components/BottomNavigationManager.kt>)：
  - 底部改为**悬浮玻璃 Dock**（圆角面板 + 细描边 + 阴影），选中项是薄荷→青渐变胶囊并展开文字，未选中只显示图标；
  - 补 `navigationBarsPadding()`，不被手势导航条遮挡；
  - 横屏侧边栏也改成同样的悬浮玻璃样式。
- [GuardVisuals.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/components/GuardVisuals.kt>)：
  - `BrandLargeTopAppBar` 不再使用 `LargeTopAppBar`，改为自绘控制台头；
  - 自己提供 `LocalContentColor`，避免深色下标题内容/图标变黑。
- [TopAppBarManager.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/navigation/components/TopAppBarManager.kt>)：
  - `FixedTopAppBar`（启动 / 日志页）改成同款 `SECURE CHANNEL` 控制台头；
  - `TopAppBarState` 的 offset limit 改为 0，避免旧搜索折叠动画把 offset 动画到 `-Float.MAX_VALUE`。
- [MainActivity.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/MainActivity.kt>)：整页深色渐变底 + 透明 Scaffold；底部 Dock 悬浮在渐变上。
- Apps / Settings / Starter / Logs 的 Scaffold 全部透明并显式设置 `contentColor`，保证深色下文字可见。

---

## 四、启动页 / 图标

- [themes.xml](<E:/11/anquan/anquan/embedded/manager/src/main/res/values/themes.xml>) / [values-night/themes.xml](<E:/11/anquan/anquan/embedded/manager/src/main/res/values-night/themes.xml>)：
  - 冷启动 splash 背景从浅紫 `#FEF7FF` 改为 `#060A0F`；
  - `AppTheme` 的 `windowBackground` 同步改深，避免启动/切换闪白。
- [ic_splash.xml](<E:/11/anquan/anquan/embedded/manager/src/main/res/drawable/ic_splash.xml>)：
  - 替换掉上游 Stellar 的圆形标识，改为自绘「盾牌 + 对勾」薄荷色矢量图。

---

## 五、构建与验证

- `:manager:compileReleaseKotlin` 通过；`:app:assembleRelease` 通过。
- 最终 APK：
  - [游龙安全护盾-9.2.1-release-20261017.apk](<E:/11/anquan/anquan/游龙安全护盾-9.2.1-release-20261017.apk>)
  - `versionCode 1234567944`，签名 SHA-256 `d3d868c9...`
  - APK SHA-256（本轮最终包）：见交付消息
- 已在 DEX 中确认新界面字符串存在：`PRIVILEGE CONSOLE`、`SECURE CHANNEL`、`SERVICE CORE`、`特权核心`。
- **真机视觉未验证**：设备当前不在线（adb 无设备），无法截图确认深色对比度/刘海屏适配。装好后如果某处颜色或间距不理想，把截图发我，我按截图微调。
