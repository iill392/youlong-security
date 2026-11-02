# 第 14 轮改动汇总（2026-10-02）

三项需求：① 阻断外部 Shizuku 授权 ② 命令分析（中风险询问用户）③ 改日常模式文案。

---

## ① 外部 Shizuku 无法授权给安全护盾 —— 本来就是这样，已给出实测证据

这一项**不需要改代码**，因为工程里根本不存在"外部 Shizuku"这条通路。逐项核实：

| 检查项 | 结果 |
| --- | --- |
| 宿主 `AndroidManifest.xml` 里的 `shizuku` / `moe.` / `rikka` 声明 | **0 处** |
| 管理器 `AndroidManifest.xml` 里的同类声明 | **0 处**（只有文件头注释提到 Shizuku 许可） |
| 外部 Shizuku 的 Maven 坐标（`dev.rikka.shizuku:*`、`moe.shizuku.privileged.api`、`Shizuku-API`） | **0 处** |
| `embedded/api` 模块对外暴露的类 | 只有 `roro/stellar/*`（Stellar.kt、StellarHelper.kt、StellarRemoteProcess.kt…） |
| 自启动方式 | `libstellar.so` 用 `app_process` + `CLASSPATH` 指向**本应用自己的 APK**，加载 `roro.stellar.server.StellarService` |

工程里那个 `:shizuku-api` **不是外部 Shizuku**，而是 Stellar 自带的**内置兼容层客户端**
（`roro.stellar.shizuku.ShizukuCompat` / `ShizukuProvider`），只有 4 个文件。

**结论**：权限只能由内置特权的服务端（`StellarService` + `PermissionEnforcer`）授予，
而它只认自己的管理器（`MANAGER_APPLICATION_ID = com.youlong.hd`）。
外部 Shizuku 应用既拿不到我们的 binder，也没有任何入口能给我们授权。

---

## ② 命令分析：授权的应用（除自身）执行恶意命令时拦截 / 询问

### 判定总览（`embedded/server/.../CommandInterceptor.kt`）

| 优先级 | 规则 | 处理 |
| --- | --- | --- |
| 1 | 针对自家应用（`com.youlong.hd` / `com.youlong.tool`） | **直接拒绝** + 弹窗告警 |
| 2 | 批量停止所有应用（`am kill-all` / `pkill` / `killall` / 枚举包后循环） | **直接拒绝** + 弹窗告警 |
| 3 | 卸载 / 冻结 / 清数据 | **询问用户** |
| 4 | 设备管理员 / 用户 / 设备策略（`dpm`、`pm set-user-restriction`…） | **询问用户** |

> **拦截没有放宽**：原来直接拒绝的两条仍然是直接拒绝，本次只是把"要询问"的范围内
> 增加了命令分析维度。

### 新增的「命令分析」四档风险（规则 3 命中后判定，写进弹窗）

| kind | 触发条件 | 弹窗补充说明 |
| --- | --- | --- |
| `admin` | 命中 `dpm` / `set-user-restriction` / `remove-user` / `cmd device_policy` / `cpm` | 会修改设备管理员或用户策略，系统级高敏感操作 |
| `system` | 目标是系统应用 —— 24 个关键组件包名（设置、系统界面、包安装器、权限控制器、电话短信…）+ 厂商系统包前缀（`com.android.*` / `com.miui.*` / `com.oppo.*` / `com.vivo.*` …） | 冻结/卸载「设置」「系统界面」等会导致系统不可用 |
| `bulk` | 一条命令里影响 **≥3 个包**（典型「一键卸载/冻结全部应用」） | 会一次性影响多个应用，属批量操作 |
| `normal` | 其它普通卸载 / 冻结 | 无额外提示 |

风险类型由服务端经 Intent 的 `riskKind` 传到管理器弹窗
（`CommandGuardActivity`），弹窗里新增 `RiskHint()` 组件渲染上面这段说明。

### 回归验证（从源码抽取正则，保证验证的是线上同一份）

```
powershell -File E:\anquan\tools\verify_command_rules.ps1
抽取: STOP_ALL=6 NEED_CONFIRM=8 ADMIN=7 CRITICAL=24

PASS pm list packages -3                                  ALLOW
PASS pm list packages | grep com.youlong.hd                ALLOW
PASS dumpsys package com.youlong.hd                        ALLOW
PASS am force-stop com.youlong.hd                        BLOCK-SELF
PASS pm uninstall com.youlong.tool                       BLOCK-SELF
PASS am kill-all                                         BLOCK-STOPALL
PASS pkill -f com.example                                BLOCK-STOPALL
PASS pm list packages -3 | while ... force-stop            BLOCK-STOPALL
PASS pm uninstall --user 0 com.example.evil              CONFIRM:normal
PASS pm disable-user/suspend/hide/clear ...              CONFIRM:normal
PASS pm uninstall --user 0 com.android.settings          CONFIRM:system
PASS pm disable-user --user 0 com.android.systemui       CONFIRM:system
PASS pm suspend com.android.packageinstaller             CONFIRM:system
PASS pm disable-user --user 0 com.miui.home              CONFIRM:system
PASS pm uninstall --user 0 com.a.b com.c.d com.e.f       CONFIRM:bulk
PASS dpm remove-active-admin ...                         CONFIRM:admin
PASS pm set-user-restriction no_uninstall_apps 1         CONFIRM:admin
PASS cmd device_policy set-device-owner ...              CONFIRM:admin
PASS am force-stop com.example.evil                        ALLOW
PASS settings put global adb_enabled 1                     ALLOW

TOTAL: PASS=23  FAIL=0
```

---

## ③ 日常模式文案

| 位置 | 原文 | 现在 |
| --- | --- | --- |
| 模式名 | `日常模式2.0（100%拦截） 推荐` | **`日常模式2.0 推荐`** |
| 模式说明 | `全网首发，病毒拦截率为100%，误伤率基本没有` | **`经测试600款【高级病毒】拦截率为99.6%`** |

源码核实：`index.html` 里 `100%拦截` 残留 **0** 处，`600款` / `99.6%` 各 1 处。

---

## 构建

```
JAVA_HOME=D:\scoop\apps\temurin21-jdk\21.0.12-8.0
:server:compileDebugKotlin   → BUILD SUCCESSFUL
:manager:compileDebugKotlin  → BUILD SUCCESSFUL
:app:assembleRelease         → BUILD SUCCESSFUL in 6m 1s
app-release-20261002-083006.apk  37.9 MB
→ 已复制为 E:\anquan\游龙安全护盾-9.1.0-release.apk
```

## ⚠️ 装机验证被设备掉线打断

本轮安装时设备与 adb 反复断开（`no devices/emulators found`），
**APK 已构建完成但还没装上去**。设备恢复后需要补做：

1. `pm install -r -d` 安装；
2. 打开应用确认日常模式显示 `日常模式2.0 推荐` / `经测试600款【高级病毒】拦截率为99.6%`；
3. 命令分析的端到端（用授权应用触发 `pm uninstall com.android.settings`
   → 应弹确认框并显示"目标是系统应用"的红色提示）。

## 本轮踩到的坑

1. **Kotlin 文件也是混合行尾**：`CommandGuard.kt` 有 2 处 CRLF、其余 LF，
   `ProcessManager.kt` 全 LF —— 按 CRLF 拼的锚点匹配不上，导致上一轮
   `riskKind` 只接通了一半（`askForConfirmation` 里引用了未定义的 `riskKind`）。
   现在统一做法：**读进来先把行尾归一化成 LF，改完按 LF 写回**，彻底躲开这个问题。
2. **`InterceptDecision.Block` 没有 `kind` 字段**：我给 NeedConfirm 加了 kind，
   却在 ProcessManager 里顺手给 Block 也传了 `decision.kind`，编译报
   `Unresolved reference 'kind'`。已改回 4 参调用。
