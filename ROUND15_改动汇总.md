# 第 15 轮改动汇总（2026-10-17）

三项需求：① 顶部导航新增「扫描」② 自动拦截页新增「金龙引擎」③ 后台保活（LMK 清不掉的闹钟自启动）。

> 原则：只做加法和必要的病毒库地址修正，不改动既有的守护 / 拦截 / 逃生逻辑。

---

## ① 扫描功能（顶部导航第 3 个 Tab）

### 界面

- 顶部导航：`首页 / 自动拦截 / 扫描`（`index.html` 的 `.nav-tabs`，新增 `navTabScan`）。
- 扫描页 `#pageScan`：
  - 顶部卡片 + 大按钮 **「立即扫描」**（`scanStartBtn`）；
  - 点按钮后调用原生 `Android.scanVirusApps()`，逐个比对手机里已安装的第三方应用；
  - 结果分两档渲染：    - **100% 病毒**：整块红色渐变卡片 + 呼吸光晕动画，白色「立即卸载」按钮；
      文案固定为「这是一个 **100% 为病毒** 的软件，建议立即卸载！」；
      同时自动弹出红色警示弹窗（「⚠️ 发现 100% 病毒」+ 应用名 + 「立即查看并卸载」）。
    - **可能病毒**：琥珀色卡片，文案「这个软件**可能为病毒**（命中原因），建议立即卸载！」，同样带「立即卸载」。
  - 没有命中：绿色「未发现病毒」卡片。
  - 卸载按钮 → `Android.uninstallVirusApp(pkg)`：
    - 内置特权已连接：后台 `pm uninstall`，无需用户确认；
    - 未连接：拉起系统卸载确认框，结果经 `onActivityResult` 回传；
    - 完成后回调网页 `window.__onVirusUninstallResult(...)`，成功则自动重扫。

### 判定依据（与后台自动拦截完全同源，避免两套结论）

应用清单同时走两路并按包名去重，避免国内 ROM 的包可见性限制导致漏扫：

1. `getInstalledApplications()`（有读取应用列表权限时最全，含无桌面图标的隐藏应用）；
2. `queryIntentActivities(MAIN + LAUNCHER)`（Android 11+ 无需权限，补齐第 1 路被过滤掉的桌面应用）。

| 档位 | 数据来源 | 匹配方式 |
| --- | --- | --- |
| 100% 病毒 | `safe1.json`（包名）+ `mingcheng1.json` / `mian1.json`（应用名称） | 包名精确 / 名称精确（忽略大小写） |
| 可能病毒 | `safe2.json`（包名）+ `mingcheng2.json` / `mian2.json`（名称）+ `key.json`（关键字） | 包名精确 / 名称精确 / 关键字包含 |

> 用户指定的名称库是 `mingcheng1/2.json`，但服务器上 `mingcheng1.json` 目前只有 25 条、
> `mian1.json` 有 139 条（两份都是「100% 病毒名称」）。为避免漏检，**两份取并集**：
> `VirusDb` 对名称列表同时拉取 `mingcheng*`（主）与 `mian*`（旧/兜底）并合并。
> `StrX.VDB_PATH_CERTAIN_NAME / SUSPECT_NAME` 已改为 `mingcheng1/2.json`，
> 旧文件名保留为 `*_LEGACY` 常量。

### 新增 / 修改文件

- 新增 `app/src/main/java/com/youlong/hd/VirusScanner.java`（只读扫描，返回 JSON）
- `MainActivity`：新增 `getVirusScanStatus / scanVirusApps / uninstallVirusApp` 三个 JS 桥
- `StrX.java`：名称库路径改为 `mingcheng1/2.json`，新增 `*_LEGACY`（mian1/2）
- `VirusDb.java`：名称列表 `mergeUrl` 合并逻辑
- `index.html`：导航 Tab、扫描页、结果渲染、卸载交互
- `scripts/verify-virusdb-url.js`：补上 `*_LEGACY` 校验、去掉不存在的 `VDB_PATH_VERSION`

---

## ② 金龙引擎（自动拦截页新增开关）

### 需求映射

- 开关：`自动拦截` 页新增「金龙引擎」卡片，**默认开启**（`shield_prefs/golden_dragon_on=true`）。
- 未连接内置特权（Stellar）→ **自动关闭**；连接后 → **自动开启**。
  - 由 `GoldenDragon.sync()` 维护：自动关时写 `golden_dragon_auto_off=true`，
    重连且仍是「自动关」状态时自动开；用户手动关闭则清掉该标记（尊重用户手选）。
- 开启后检测「软件修改系统音量」：
  - 三个音量检测通道（无障碍回调 / `VOLUME_CHANGED_ACTION` / 120ms 轮询）统一汇入
    `ProtectService.onVolumeStreamChanged()`；
  - 判定：1.5 秒去重、2.5 秒内按过物理音量键则跳过、前台必须是第三方应用
    （自己/系统应用/白名单跳过）、媒体流要求「变化 ≥3 格且到 0/最大」且当前没有媒体播放，
    其他流要求 ≥2 格（1 格视为物理按键）、无无障碍时门槛提到 4 格、20 秒冷却；
  - 命中后后台线程执行 `GoldenDragon.neutralize()`：
    1. 若该应用声明了 Stellar/Shizuku 接入 → 先 `am force-stop` 它；
    2. **停止所有第三方指令调用内置特权**：对声明过 Stellar / Shizuku 接入
       （meta-data 或 provider）的第三方应用，调用 `Stellar.updateFlagForUid(...)`
       把 `stellar` / `follow_stellar_startup` / `shizuku` 三条权限全部置为 DENIED；
       Stellar 服务端在撤销 runtime 权限时会对已授权应用执行 `forceStopPackage`
       （`PermissionManager.updateFlagForUid`），因此已连接 / 正在调用特权的第三方应用
       会被立即停掉，后续指令调用被服务端直接拒绝；
    3. 写拦截记录（`block_history`，type=`golden_dragon`）、Toast、
       高优先级通知（「金龙引擎已处置」），开关卡片下方显示「最近一次：…」。

### 新增 / 修改文件

- 新增 `app/src/main/java/com/youlong/hd/GoldenDragon.java`
- `ProtectService.java`：金龙引擎音量判定 / 处置 / 记录；每 4 秒与特权状态同步
- `MainActivity`：新增 `getGoldenDragonStatus / setGoldenDragonEnabled` 两个 JS 桥
- `index.html`：金龙引擎卡片、状态渲染、手动切换、2.5 秒同步

---

## ③ 后台保活：LMK 清除不掉的闹钟自启动

### 机制

- 新增 `KeepAliveAlarm`：
  - 用 `AlarmManager.setExactAndAllowWhileIdle` 排**一次性精确闹钟**（2 分钟），
    触发后由 `KeepAliveReceiver` 重新排下一次 → **链式续期，不会断**；
  - 闹钟登记在 `system_server`，**进程被 LMK（或任何方式）杀掉都不会清除它**，
    到点后系统重新拉起本应用进程接收广播 → 再拉起两个前台服务；
  - 没有精确闹钟权限时自动降级为 `setAndAllowWhileIdle`（Doze 中仍会唤醒），最差降级为普通 `set()`；
  - Android 13+ 通过新增 `USE_EXACT_ALARM`（安装即授予）保证精确闹钟可用。
- `KeepAliveReceiver`：拿 10 秒 WakeLock → 先排下一次闹钟 → 检查并拉起
  `ProtectService`（主）与 `ForegroundService`（`:guard` 哨兵，双进程互拉）。
- `BootReceiver`：除 `BOOT_COMPLETED` 外新增 `MY_PACKAGE_REPLACED`
  （覆盖安装会清掉旧 PendingIntent，必须重排）与部分 ROM 的 `QUICKBOOT_POWERON`。
- 排闹钟时机：开机 / 应用更新 / 打开应用 / 开启守护 / 服务启动 / 每次闹钟触发。
- 取消时机：用户关闭守护（`stopProtect`）/ 所有开关全关（服务销毁、接收器自检）。

### 多层保活（新增后）

1. 系统级精确闹钟链（本项，进程被全灭也能拉回）
2. `ProtectService` ↔ `ForegroundService(:guard)` 双进程哨兵互拉
3. `ForegroundService` 的 `ACTION_TIME_TICK` 每分钟自检
4. `START_STICKY` + 开机广播 + 应用更新广播 + `onTaskRemoved` 划掉后保活

### 修改文件

- 新增 `app/src/main/java/com/youlong/hd/KeepAliveAlarm.java`
- `KeepAliveReceiver.java` / `BootReceiver.java` 重写
- `AndroidManifest.xml`：`USE_EXACT_ALARM`、BootReceiver 广播 action
- `ProtectService.java` / `MainActivity.java`：统一走 `KeepAliveAlarm`，开启守护时排定

---

## 构建与验证

- 构建：`gradlew.bat :app:assembleDebug` / `:app:assembleRelease` 均通过。
- **versionCode 1234567901 → 1234567942**：设备上已有版本为 1234567941，
  版本号只增不减，否则系统安装器会以 `INSTALL_FAILED_VERSION_DOWNGRADE` 拒绝覆盖安装。
- 产物：
  - `build_out/_app/outputs/apk/debug/app-debug-<时间戳>.apk`
  - `build_out/_app/outputs/apk/release/app-release-<时间戳>.apk`
- 已验证：
  - `node scripts/verify-virusdb-url.js` → 7 个接口全部解码正确
    （safe1/safe2/mingcheng1/mingcheng2/key/mian1/mian2）；
  - 混淆后的 `index.html` 通过 `node scripts/validate-js.js` 语法校验；
  - 用无头浏览器 + 模拟 `Android` 桥渲染扫描页 / 自动拦截页，确认红色 100% 病毒卡片、
    琥珀色可能病毒卡片、警示弹窗、金龙引擎开关（开 / 未连接自动关闭）显示正常；
  - **额外用混淆后的最终产物再跑一次运行时测试**：页面无 JS 报错，
    `window.__onVirusUninstallResult` 在混淆后仍为 `function`（原生回传通道有效）；
  - 编译产物 APK 清单已包含 `USE_EXACT_ALARM` 与两个新广播 action。
