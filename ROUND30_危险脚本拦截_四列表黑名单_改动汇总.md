# 第 30 轮改动汇总（2026-10-25）—— 换思路：危险脚本内容拦截 + 四列表黑名单

用户要求彻底换思路：

1. 应用执行 `sh` 文件时，**内容里出现 youlong、冻结/卸载所有应用等大量危险命令** → 精准识别、直接拦截；**加密文件也直接拦**。
2. **删除病毒库内容特征分析**；黑名单改成
   `safe1.json` + `safe2.json` + `mingcheng1.json` + `mingcheng2.json`
   里的**所有包名和名称**；这些应用执行任何命令 → 直接拦截。

---

## 一、脚本内容危险命令拦截

新增 `embedded/server/.../policy/DangerousScriptDetector.kt`，在
`PolicyEngine.precheck` / `evaluate` 里、**证书/评分之前**做确定性命中。
命中即 **BLOCK，不弹审批**。

### 规则（当前列表）

| 类别 | 命中内容 |
|---|---|
| 针对本产品 | 含 `youlong`（不区分大小写） |
| 批量冻结/卸载 | `pm disable-user … -a/--all`、`pm uninstall … -a/--all`、`pm list packages` 后接 `pm disable/uninstall/suspend/hide`、`killall`/`pkill`、`am kill-all`/`am force-stop *`、`dumpsys window` + `am force-stop` |
| 系统破坏 | `rm -rf /`、`/system`、`/data`、`/sdcard`；`reboot`/`shutdown`/`poweroff`；`mkfs`/`wipe data|system|cache`；`dd … of=/dev/block`；`mount … remount` |
| 无障碍/持久化/编码 | `settings put secure enabled_accessibility_services`；`nohup`；`base64 -d`、`openssl enc/…`、`gpg -d`、`xxd -r`、`eval` |
| 安装/设备管理 | `pm install`；`dpm set-device-owner/set-profile-owner/remove-active-admin` |
| 中文描述 | 「冻结所有应用」「卸载所有应用」「禁用所有应用」「清除所有应用」 |

### 覆盖入口

- `sh /绝对路径.sh`（相对路径按 `dir` 解析）；
- `sh -c '…'` 的 argv；
- **裸 sh 的 stdin 脚本**（按完整命令行判定）；
- **PTY** 下的 `sh 文件`；
- 文件 >1MB → 无法完整审计，直接拒绝；
- 文件含 NUL / 控制字符 ≥10% → 按「加密/二进制」直接拒绝；
- 管理器自身（`com.youlong.hd`）仍豁免，不影响本产品自己的脚本。

> 注意：这是「宁可误杀」的列表。`nohup`、`pm install`、`killall` 这类命令
> 正常的第三方工具也可能用，命中会被直接拦；如需放宽告诉我具体命令。

---

## 二、黑名单改为四个列表（内容特征分析停用）

### 2.1 数据来源

`VirusDb` 原本就拉取并缓存这些列表；本轮把它们作为**唯一黑名单来源**：

- 包名：`safe1.json`（certainPkgs）+ `safe2.json`（suspectPkgs）
- 名称：`mingcheng1.json` / `mingcheng2.json`（以及合并的 `mian1/mian2` 名称）

`VirusDb.Data.isBlacklisted(pkg, label)`：包名或应用名称命中任一即黑名单
（**safe2 / mingcheng2 里的「可能病毒」也按黑名单处理**，按你的要求）。

### 2.2 宿主快照（`VirusContentDb.snapshotJson`）

- `packages[]`：safe1 + safe2 + 扫描命中的包，全部 `action=block`；
- `names[]`：**新增字段**，mingcheng1/2（含 mian1/2）全部 `action=block`；
- `certs[]` / `rules[]`：**置空**——内容特征分析不再下发给服务端；
- 同一份快照按内容 SHA-256 去重，列表任何变化都会重推；
- 拉取节奏：3 分钟定时器（`VirusDb.refreshNow` → `pushSnapshot` → 后台扫描），
  手动「立即检查更新」、安装/更新应用时也会触发。

### 2.3 服务端拦截

`VirusSnapshot` 新增 `names` 索引与 `matchName()`；`CallerResolver` 通过
`PackageManagerCompat.getApplicationLabel` 取调用方显示名称（60 秒缓存）。

`PolicyEngine`：

- `precheck` / `decide`：**包名命中** 或 **应用名称命中** → 直接 BLOCK，
  拦截该应用的所有 `newProcess` / `newPtyProcess`；
- 新增 `blacklistReason()`，在非 shell 入口同样生效：
  - `transactRemote`
  - `getSystemService`
  - `getApplications`
  
  这三处之前可以绕过 `newProcess`，现在黑名单应用也会被拒。

### 2.4 内容特征分析停用

- `VirusDb.refreshNow` 不再调用 `VirusContentDb.refreshNow`（不再拉 `virus-db.json`）；
- 扫描页不再做证书/哈希/token 检测，只按四列表包名/名称匹配；
- 后台弹窗也只按四列表判定；
- 管理器「病毒库」页显示：**黑名单应用数**、**黑名单名称数**、
  **内置特权快照：已推送/未推送**，版本位置显示列表拉取时间。

> `VirusContentDb` 里旧的证书/哈希/token 代码**已停用但未物理删除**
> （避免大范围重构风险）。需要的话下一轮可以整类删除。

---

## 三、升级后必须做

**安装后重启一次内置特权服务**（管理器首页停止 → 启动，或重启手机）。
原因：包名/名称拦截、快照写入接口都是服务端新代码，旧进程不重启不会生效。

重启后打开管理器「病毒库」页确认：

- 黑名单应用 > 0、黑名单名称 > 0；
- **内置特权快照：已推送**。

然后装一个 safe1/safe2 里的测试包，授权后执行任意命令，应当直接弹
「病毒特征库：…（包名命中 / 应用名称命中）」并拒绝。

---

## 四、构建与产物

| 项 | 结果 |
|---|---|
| 工具链 | Gradle 8.14（`D:\Agent\tmp\gradle-8.14-dist`） |
| 命令 | `gradle.bat -p D:\anquan\anquan :app:assembleRelease --console=plain` |
| 结果 | ✅ **BUILD SUCCESSFUL in 3m 18s**（249 tasks，15 executed） |
| 产物 | `build_out/_app/outputs/apk/release/app-release-20261025-010238.apk`，**40,091,361 B** |
| 复制件 | `游龙安全护盾-9.2.9-release-20261025-lists.apk` |
| SHA-256 | `F0E30F1A3BD1A22C8CD0C1A38B795EC38ED35670D46FFF2E5354CF1B4D4C1D56` |
| 版本 / 签名 | `9.2.9 / 1234567954`；V3 证书 `d3d868c9…` |
| APK 内已确认 | `脚本包含危险命令`、`批量冻结应用`、`含 youlong 关键字`、`应用名称命中`、`黑名单名称`、`包名命中`、`编码/解密后执行` 均在包内 |

---

## 五、残余与风险

1. **名称匹配是精确匹配**（忽略大小写），且服务端取的是**当前系统语言**的应用名称；
   列表里是中文名时，英文系统下可能匹配不到。
2. **误杀风险**：`safe2`/`mingcheng2`（可能病毒）也按黑名单全拦；
   危险脚本列表里的 `nohup`、`pm install`、`killall` 等也会拦正常工具。
3. **加密识别**：二进制/NUL、base64/openssl/gpg/xxd/eval 已覆盖；
   纯可打印字符的 XOR/自定义加密文本仍可能漏（可下一步加熵检测）。
4. **非 shell 入口只覆盖了** `transactRemote` / `getSystemService` / `getApplications`；
   其它 AIDL 方法未做全量黑名单拦截（多数是管理器专用或权限受限）。
5. 内容特征分析代码已停用但未删除，管理器页也不再展示 certs/rules。
6. 旧服务端未重启时，管理器页会显示「快照未推送」，这是提示，不是新 bug。
