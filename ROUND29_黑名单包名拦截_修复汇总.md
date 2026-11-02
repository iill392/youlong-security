# 第 29 轮改动汇总（2026-10-25）—— 黑名单应用「所有命令拦截」修复

用户反馈：**特征库显示已是最新，但黑名单应用执行命令仍然不被拦截。**

本轮定位到 4 个叠加原因，前 3 个是代码缺口，第 4 个是升级后的服务端进程问题。

---

## 一、根因

### 1. 服务端从来没查「包名黑名单」（主因）

`embedded/server/.../policy/PolicyEngine.kt` 的 `precheck` / `decide` 只调用了：

- `VirusSnapshot.matchCert()`（证书命中）
- `VirusSnapshot.matchScript()`（脚本行为规则）

`VirusSnapshot.matchPackage()` **定义了但从未被调用**。所以：

- `safe1.json` 命中的应用（包名）→ 服务端完全不知道，所有命令照常放行；
- `virus-db.json` 的 `packages[]`（`mio.TerminalDeity.Ultra`、`com.lockgen.studio`）→ 也只是解析进内存，不参与判定。

只有「证书在 certs[] 里」或「脚本命中行为规则」才会拦。

### 2. 快照里根本没有 safe1 的包名

`VirusContentDb.snapshotJson()` 原来只遍历 `virus-db.json` 的 `packages[]`（2 条），
`safe1.json` 的约 90 个 100% 病毒包名只存在宿主内存/SharedPreferences 里，
**从未推给内置特权**，服务端自然无从拦截。

### 3. 推送去重只看 version

`pushSnapshot()` 原来用 `pushedVersion` 短路。`safe1.json` 更新时 `virus-db` 的
`version` 往往不变 → 不会重推，服务端拿到的是旧包名列表。

### 4. 升级后服务端进程还是旧代码（环境问题）

ROUND26 给快照写入新增了 AIDL 事务 `writePolicyFile`。如果**装完新 APK 没有重启
内置特权服务**，正在跑的旧服务端：

- 不认识 code 113 → 快照推送失败（App 里 `pushedVersion` 为空、每轮重试）；
- 即使文件写进去了，旧服务端的 `PolicyEngine` 也没有包名拦截逻辑。

而管理器页显示的是**宿主侧**的同步状态（病毒库确实最新），所以会出现
「页面最新、服务端不生效」。

---

## 二、修复

### 2.1 服务端按包名拦截（`PolicyEngine.kt`）

在 `rawPrecheck()` 与 `decide()` 中都加入：

```kotlin
val virusPkg = VirusSnapshot.matchPackage(caller.packageName)
if (virusPkg != null && "block".equals(virusPkg.action, ignoreCase = true)) {
    return block(... "包名命中" ...)
}
```

- `action=block` → **直接拒绝，不弹审批**，该应用的所有 `newProcess` / `newPtyProcess` 都被拦；
- `action=warn` → 不硬拦（保留原有告警语义）；
- 位置在受信应用判断之前，受信应用命中黑名单也一样拦。

### 2.2 快照合并 safe1 包名（`VirusContentDb.snapshotJson`）

在 `virus-db.json` 的 `packages[]` 之后，追加宿主侧
`VirusDb.get(ctx).certainPkgs`（100% 病毒包名）：

```json
{"package_name":"com.optimizer.x","action":"block","family":"VirusDb"}
```

- 与 `virus-db` 已有包名去重；
- 体积约 90 条，快照仍只有几 KB；
- 服务端 `VirusSnapshot` 的 `packages` 索引直接可用。

### 2.3 按内容签名重推（`pushSnapshot`）

- 不再用 `version` 短路；改为对完整快照 JSON 取 SHA-256 作为签名；
- `safe1` 变化、规则变化、包名变化都会重推；
- `maybePushAsync` 也去掉 version 判断，交给 `pushSnapshot` 统一比较。

### 2.4 管理器病毒库页显示真实状态

`VirusDbSyncStatus` 新增 `snapshotPushed / blacklistCount / ruleCount`，
内置特权「病毒库」页新增三行：

| 行 | 含义 |
|---|---|
| 黑名单应用 | 快照里将按包名拦截的应用数（safe1 + virus-db packages） |
| 拦截指令 | 快照里的行为规则数 |
| 内置特权快照 | **已推送** / **未推送（重启内置特权服务后重试）** |

这样能直接区分「宿主同步成功」和「服务端真的拿到快照」——之前两者混在一起，
所以看起来“最新”但实际不生效。

---

## 三、升级后必须做的一步

**安装本版本后，重启一次内置特权服务**（管理器首页停止 → 启动，或重启手机）。

原因：快照写入使用了新的 AIDL 事务，且包名拦截是服务端新代码；旧进程不重启，
这两件事都不会生效。重启后打开管理器「病毒库」页，确认：

- 特征库版本是最新；
- **内置特权快照：已推送**；
- 黑名单应用数量 > 0。

---

## 四、构建与产物

| 项 | 结果 |
|---|---|
| 工具链 | Gradle 8.14（`D:\Agent\tmp\gradle-8.14-dist`） |
| 命令 | `gradle.bat -p D:\anquan\anquan :app:assembleRelease --console=plain` |
| 结果 | ✅ **BUILD SUCCESSFUL in 3m 58s**（249 tasks，36 executed） |
| 产物 | `build_out/_app/outputs/apk/release/app-release-20261025-003120.apk`，**40,100,756 B** |
| 复制件 | `游龙安全护盾-9.2.9-release-20261025-pkgblock.apk` |
| SHA-256 | `68D4BC5339882745BA9FF45BA718C6723A88B1AE0A11A499EA978BF1692C9AB7` |
| 版本 / 签名 | `9.2.9 / 1234567954`；V3 证书 `d3d868c9…`（与历史一致） |
| APK 内已确认 | `包名命中`、`病毒特征库：`、`黑名单应用`、`拦截指令`、`内置特权快照`、`未推送（重启内置特权服务后重试）` 均在包内 |

---

## 五、验证方法（真机）

1. 装一个 `safe1.json` 里存在的测试包（例如 `com.optimizer.x`、`com.example.apq`、
   `xiaote.demo.kill.youlong` 这类测试包）。
2. 用内置特权给它授权（或依赖自动授权），让它通过 Stellar 执行任意命令，
   例如 `pm list packages -3`。
3. 预期：
   - 命令被拒，`CommandGuard` 弹出「病毒特征库：VirusDb（包名命中）」；
   - 审计 `/data/local/tmp/stellar_guard/shell_audit.jsonl` 里 `decision=blocked-precheck`；
   - 管理器「病毒库」页显示快照已推送、黑名单应用数 > 0。
4. 反向验证：非黑名单应用执行 `pm list packages -3` 仍正常（不误杀）。

---

## 六、残余

1. 只合并了 `safe1`（100% 病毒包名）；`safe2`（可能病毒）和
   `mingcheng/mian`（应用名称库）尚未推给服务端。若按「名称」命中的样本，
   服务端仍不会拦；需要时可以在快照里加 `names[]` 并让服务端用
   `PackageManager.getApplicationLabel` 比对。
2. `transactRemote` / `getSystemService` 不走 `newProcess`，仍是历史绕过面；
   包名拦截只覆盖 shell 命令入口。
3. 随机签名 + 随机包名的克隆体不在包名黑名单里，仍依赖证书/DEX/资产/token/
   行为规则（上一轮已解释）。
4. 旧服务端未重启时，管理器页会显示「快照未推送」，这是预期提示，不是新 bug。
