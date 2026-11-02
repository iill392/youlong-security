# 第 27 轮改动汇总（2026-10-24 夜）—— 脚本文件拦截补口 + 病毒库后台循环检测

本轮两项需求（用户指定）：

1. 已授权应用执行 `sh` 文件时，文件里含 `youlong` 要阻止；文件是加密的也要阻止。
2. 内置特权的病毒库要在后台一直循环：每 3 分钟请求一次数据，然后扫描已安装应用；
   命中特征就弹窗「XX 高风险应用，建议立即卸载」，给两个选项。

> 未改版本号：`versionName 9.2.9` / `versionCode 1234567954`，可覆盖安装。

---

## 一、脚本文件拦截（需求 1）

### 已有基础（ROUND26）

`sh /path/x.sh` 在 fork 前会读文件内容交给 `PolicyEngine.evaluate`；ROUND26 起
`evaluate` 会再跑 `CommandInterceptor.inspect(["sh","-c",content])`，因此：

- 内容含 `youlong`（不区分大小写）→ 直接 BLOCK；
- 内容疑似加密/编码（base64/openssl/gpg/xxd/eval/长 blob）→ 直接 BLOCK。

这两条对**绝对路径**、**≤64KB** 的脚本已经生效。

### 本轮补掉的四个口子（`ProcessManager.kt`）

| 缺口 | 改动 |
|---|---|
| 相对路径不分析（`sh evil.sh` + `dir=/data/local/tmp`） | 按 `newProcess` 的 `dir` 解析成绝对路径后再读 |
| >64KB 直接跳过（等于不检查） | 上限提到 **1MB**；超过 1MB → 直接 BLOCK（无法完整审计，fail-closed） |
| 加密/二进制文件 `readText` 看不出 | 新增 `looksBinary()`：含 NUL 或控制字符 ≥10% → 按「加密/二进制」BLOCK |
| PTY 通道不调用脚本文件策略 | `newPtyProcess` 同样调用 `applyScriptFilePolicy(..., dir)` |

新增 `blockScriptFile()` 统一处理：写审计 → 弹「已阻止」→ 抛 `SecurityException`。

**仍然存在的缺口（未改）**：
- `bash -c 'sh /path/x.sh'`、`cat /path/x.sh | sh` 这类**嵌套**执行，外层 argv/stdin
  看不到文件内容；
- TOCTOU：检查后到执行前文件仍可被替换（ROUND24 已记录）。

---

## 二、病毒库后台循环检测（需求 2）

### 2.1 循环

`VirusContentDb` 的 3 分钟定时器（主进程）在每次 `refreshNow()` 之后调用
`VirusAutoScanner.scanOnce(app)`，形成「请求 → 检测」循环：

- 每 **3 分钟**：拉 `virus-db.json` / `virus-db-rules.json`（ETag 304 省流量）→ 扫描；
- 启动 **30 秒**后先补一次检测（用启动阶段已拉到的缓存），不必等 3 分钟；
- 管理器页「立即检查更新」手动同步完成后也检测一轮；
- 单飞（`AtomicBoolean`），不会与上一轮重叠。

### 2.2 扫描（新增 `VirusAutoScanner.java`）

- 枚举已安装应用，跳过自己 / `com.youlong.tool` / `com.youlong.zoo` / 纯系统应用；
- 命中判定两路：
  1. 老包名/名称列表 `VirusDb.isCertain(pkg, label)`；
  2. 内容特征库 `VirusContentDb.inspectApk(...)` 判定 **BLOCK**（证书/整包/DEX/资产/token）；
- 每轮 **20 秒**预算，超时记游标，下一轮从断点继续（判定结果有进程内缓存，
  第二轮起基本是缓存命中）；
- **同一个「包名 + versionCode + lastUpdateTime」只提示一次**，应用升级后会再提示；
- 一轮只弹一个，其余命中下轮继续，避免连续弹窗。

### 2.3 弹窗（新增 `VirusAlertActivity`）

- 深色半透明卡片（`activity_virus_alert.xml` + `bg_virus_alert_card.xml` +
  `Theme.YouLongTool.VirusAlert`），显示：
  - 「检测到高风险应用」
  - 「「应用名」」
  - 「该应用与病毒特征库匹配，建议立即卸载。」
  - 「匹配原因：…」
- 两个选项：
  - **立即卸载**：用内置特权执行 `pm uninstall --user 0 <pkg>`（20s 超时）；
    失败或内置特权不可用时，打开系统卸载界面；
  - **稍后处理**：关闭弹窗，同一版本不再重复提示。
- 后台启动兜底：先 `startActivity`，同时发一条带 `fullScreenIntent` 的高优先级
  通知；Activity 起来后会取消这条通知。这样在 Android 10+ 后台启动被限制、
  但应用有悬浮窗权限/通知权限时仍能提醒。
- 清单新增 `.VirusAlertActivity`（`exported=false`、`singleTop`、`excludeFromRecents`）。

### 2.4 新增/修改文件

| 文件 | 说明 |
|---|---|
| `app/.../VirusAutoScanner.java`（新增） | 后台扫描、游标、去重、通知/弹窗拉起 |
| `app/.../VirusAlertActivity.java`（新增） | 高风险应用弹窗 + 卸载 |
| `app/res/layout/activity_virus_alert.xml`（新增） | 弹窗布局 |
| `app/res/drawable/bg_virus_alert_card.xml`（新增） | 卡片背景 |
| `app/res/values/themes.xml` | 新增 `Theme.YouLongTool.VirusAlert` |
| `app/res/values/strings.xml` | 新增 11 条 `virus_alert_*` 文案 |
| `app/AndroidManifest.xml` | 注册 `VirusAlertActivity` |
| `app/.../VirusContentDb.java` | 定时器/手动同步后触发 `scanOnce`，启动 30s 补扫 |
| `embedded/server/.../ProcessManager.kt` | 相对路径/1MB/二进制/PTY 四项 |

---

## 三、构建与产物

| 项 | 结果 |
|---|---|
| 工具链 | Gradle 8.14（`D:\Agent\tmp\gradle-8.14-dist`） |
| 命令 | `gradle.bat -p D:\anquan\anquan :app:assembleRelease --console=plain` |
| 结果 | ✅ **BUILD SUCCESSFUL in 4m 3s**（249 tasks，35 executed） |
| 编译告警 | 仅既有 NDK/SDK 版本提示与 `VirusSnapshot.kt` 的 3 处 null 判断告警 |
| 产物 | `build_out/_app/outputs/apk/release/app-release-20261024-233513.apk`，**40,088,457 B** |
| 复制件 | `游龙安全护盾-9.2.9-release-20261024-scanalert.apk` |
| SHA-256 | `25C10C2FE588B66DFFAFBF1F3CC3D8DDA1D8CA8BBE46B558A23A973842BB2D06` |
| 版本 / 签名 | `9.2.9 / 1234567954`；V3 证书 `d3d868c9…`（与历史一致） |
| APK 内已确认 | `VirusAutoScanner`、`VirusAlertActivity`、`病毒库检测`、`立即卸载`、`稍后处理`、`脚本文件疑似加密`、`脚本文件过大` 均在包内 |

---

## 四、验证状态与残余

### 已完成

- 整包构建 + 资源合并（新增 Activity/主题/字符串/布局无冲突）；
- APK 内类名/文案标记核对；
- 静态核对：相对路径解析、1MB 上限、二进制判定、PTY 调用链。

### 真机待验证

1. 后台检测：装一个命中特征库的测试包，等 3 分钟（或重启后 30 秒），确认弹窗；
   点「立即卸载」确认走 `pm uninstall` 成功；点「稍后处理」确认同版本不再弹。
2. 后台启动限制：应用在后台、屏幕关闭时，确认弹窗或全屏通知能出现。
3. 脚本文件：`sh /data/local/tmp/has_youlong.sh`、`sh rel.sh`（dir 指定）、
   含 base64/GPG 的文件、>1MB 文件、PTY 下 `sh file`，确认全部 BLOCK 且审计有记录。
4. 误杀：正常脚本（如 `pm list packages -3`）不被拦截。

### 残余风险

1. 后台弹窗依赖「悬浮窗权限」或「通知 + fullScreenIntent」；两者都被系统限制时，
   用户只会看到普通通知，需点开进入弹窗。
2. 同一应用版本只提示一次；用户点「稍后处理」后不会每 3 分钟重复弹。
3. 嵌套 `sh -c 'sh file'` / `cat file | sh` 仍未分析文件内容。
4. 首轮 20 秒预算可能扫不完所有应用，游标会在后续轮次继续；应用越多，首轮覆盖越慢。
5. 扫描在主进程；主进程被杀死后停止，靠 ProtectService / :guard 保活恢复。
6. `pm uninstall` 对设备管理员/系统应用会失败，已回退系统卸载界面。
7. 内置特权升级后仍需重启服务（沿用 ROUND26 说明）。
