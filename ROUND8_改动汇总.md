# 本轮（第 8 轮）改动汇总

## 1. 去掉宿主网页顶部的「游龙安全护盾」标题栏
- `app/src/main/assets/index.html`：删除 `.header` + `<h1>游龙安全护盾<span class="ver">beta</span></h1>`。
- 真机验证：界面不再出现该标题；`uiautomator` 只剩导航标签（首页 / 自动拦截）。

## 2. 激活内置特权后自动授权 com.youlong.hd，不再询问
- 服务端本来就按 `MANAGER_APPLICATION_ID == com.youlong.hd` 判定管理器并直接放行
  （`PermissionEnforcer.enforcePermission` / `hasPermission` 都是 `isSelf || isManager → return`）。
- 本轮补上持久化：`embedded/manager/.../MainActivity.kt` 新增 `selfGrantPermission()`，
  在 Binder 连上时把自身 uid 的 stellar 权限强制写成 GRANTED（幂等）。
- 目的：清掉历史上可能残留的 FLAG_DENIED 记录（授权应用列表里会显示成「拒绝」）。

## 3. 拦截弹窗文案与标识
- `embedded/manager/.../authorization/CommandGuardActivity.kt`
  - 标题固定为「**游龙工具已阻止此威胁**」（不再用 `command_guard_blocked_title`）。
  - 顶部标识改为**本应用自己的封面（启动图标）**：运行时 `applicationInfo.loadIcon()`
    → Bitmap → ImageBitmap（因为 `:manager` 编译期看不到 `:app` 的 mipmap）。
  - 正文改为「X 尝试 Y」，避免与标题的「已阻止」重复。

## 4. 「个性化删除内置特权」
- 用户已确认是看错了，**不需要改**。
- 实采证据（真机）：「设置 → 个性化」展开后只有 应用主题 / 配色来源 / 默认启动页 三项。

## 5. 删除超级拦截 / 极强拦截 / 终结三个模式
- `index.html`：删除三个 `.mode-row`（`modeSuper` / `modeExtreme` / `modeFinal`）及其
  radio、点击处理；`updateModeUI()` 精简为 basic + daily；模式合法性校验只认这两项；
  特权断开时的降级条件只判 `daily`；`showFinalModeWarning()` 入口废弃。
- 老用户若存的是这三个模式之一 → 回落「基础模式」。
- 真机验证：界面只剩「日常模式（推荐）」「基础模式」。

## 6. 日常模式注释改为「全网首发，病毒拦截率为100%」
- `index.html`：`日常模式` 的 `.mode-desc` 替换。
- 真机验证：`全网首发，病毒拦截率为100%`。

## 7. 摇一摇默认力度 = 猛摇
- `index.html`：`var shakeLevel = 4`；`data-level="4"` 的按钮加 `active`；
  提示文案与说明文案同步。
- `ProtectService.java`：`SHAKE_LEVEL_DEFAULT` 由 2 改为 4（两端默认值必须一致）。
- 真机验证：紧急逃生文案为「以 **猛摇** 力度摇晃手机」。

## 8. 环境检测（Dhizuku / root）+ 首页提示
- `MainActivity.java` 新增 `@JavascriptInterface getEnvironmentWarning()`：
  返回 `{"level":"none|dhizuku|root","title","message"}`。
  - Dhizuku（`com.rosan.dhizuku`）→ 「当前系统环境异常，您无法享受日常模式的100%拦截，当前拦截率为96.2%」
  - root（Magisk / KernelSU / APatch / SuperSU 包名 + 常见 su 路径）→ 同上但 **73.2%**
  - 两者同时命中只报更严重的 root。
  - ⚠️ 只做无副作用探测（查文件、查包），**绝不执行 `su -c`**，避免弹 root 授权框。
- `index.html`：
  - 新增卡片 `#envWarnCard`，位置在「开启实时守护」卡片**上方**；
  - 新增 `refreshEnvWarning()`，在 `init()` 里调用；
  - 无异常时整卡隐藏；点击卡片可「不再提示」（localStorage 记住）。
- 真机验证：本机无 root / 无 Dhizuku → 卡片隐藏（符合预期），桥接方法已编入 dex。

## 构建与安装
```
JAVA_HOME=D:\scoop\apps\temurin21-jdk\21.0.12-8.0   ← 必须用具体版本目录，current 链接会让 Gradle 报 invalid
gradlew :app:assembleRelease :app:assembleDebug     → BUILD SUCCESSFUL in 9m 29s
app-release-20261001-103928.apk  37.9 MB   → pm install -r -d  Success（lastUpdateTime=10:50:54）
```

## 本轮踩到的两个环境坑
1. **PowerShell 的 `[IO.File]::WriteAllText` 会写 BOM**，给 `ProtectService.java` 加了
   `EF BB BF`，javac 直接报「非法字符: '\ufeff'」+ 满屏语法错误。已去掉 BOM，
   并把本轮动过的文件全部检查一遍（`ic_notification_shield.xml` / `keep.xml` 也有 BOM，已清）。
   → 以后改 Java/Kotlin/XML 一律用 Node 写文件，或用 `New-Object System.Text.UTF8Encoding($false)`。
2. **`JAVA_HOME` 指向 `…\temurin21-jdk\current`（junction）会让 Gradle 报
   "JAVA_HOME is set to an invalid directory"**，改用具体版本目录即可。

## 未完成 / 待办
- 安装过程中 vivo 弹过两次 `INSTALL_FAILED_ABORTED: User rejected permissions`，
  第三次才成功 —— 属设备侧确认弹窗，非构建问题。
- 授权应用列表里仍有一条历史遗留行：`Stellar / roro.stellar.manager / 拒绝`。
  服务端配置里按 uid 存了这条记录，包名标签由 PackageManager 解析为应用名「游龙安全护盾」。
  服务端对管理器的放行是硬编码的，功能不受影响；本轮新增的 `selfGrantPermission()`
  会在 Binder 连接时把自身 uid 写为 GRANTED。是否要在界面上隐藏这条历史行，待确认。
