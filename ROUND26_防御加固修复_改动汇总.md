# 第 26 轮改动汇总（2026-10-24 夜）—— 内置特权防御加固（审查修复）

本轮按 `analysis_notes/05_ROUND24-25_代码审查.md` 的结论，修复 **C1 / C2 / H1 / C3 / H2 / M1**
六项（用户确认「改」）。核心目标：**不再允许被审查应用自己关闭/绕过整套防御**。

> 未改版本号：`versionName 9.2.9` / `versionCode 1234567954`，可直接覆盖安装。

---

## 一、修复项与落点

### C1. 策略/快照不再走 shell 可写通道（新增受信写入）

| 文件 | 改动 |
|---|---|
| `embedded/aidl/.../IStellarService.aidl` | 新增 `boolean writePolicyFile(String name, String content) = 113` |
| `embedded/api/.../Stellar.kt` | 新增 `writePolicyFile(name, content): Boolean`（异常返回 false，不抛） |
| `embedded/server/.../StellarService.kt` | `override writePolicyFile`：`permissionEnforcer.enforceManager(caller)` 校验调用方 uid 必须是管理器 |
| `embedded/server/.../policy/PolicyStore.kt`（**新增**） | 文件名白名单（`virus_db.json`）、schema 校验、≤2MB、临时文件 + `rename` 原子落盘、0644 |
| `app/.../StellarUtils.java` | 删除 `writeFileViaStdin()`（sh + base64 写文件），改为 `writeVirusSnapshot()` → Binder |
| `app/.../VirusContentDb.java` | `pushSnapshot()` 改用 `StellarUtils.writeVirusSnapshot()` |
| `policy/ShellScriptAnalyzer.kt` | 新增 `facts.policyTamper`：任何对 `/data/local/tmp/stellar_guard` 的 `rm/mv/cp/tee/ln/truncate/dd/install/chmod/chown/sed` 或 `>` 重定向都置位；**同时检查变量展开后的文本**，防止 `D=/data/local/tmp/stellar_guard; cp x $D/trusted_apps.txt` 绕过 |
| `policy/PolicyEngine.kt` | `precheck` 与 `decide` 都把 `policyTamper` 判为 **BLOCK**（放在受信判断之前，受信应用也一样） |

效果：管理器写快照必须经过 Binder 且调用方会被认证；第三方应用即使拿到 shell，
`cp`/`rm`/`tee` 改 `trusted_apps.txt` / `policy_mode.txt` / 快照也会被直接阻止并写审计。

### C2. 裸 `sh` 的 stdin 重新经过 CommandInterceptor 的硬拦截

`PolicyEngine.evaluate()` 在脚本判定前调用：

```kotlin
CommandInterceptor.inspect(arrayOf("sh", "-c", analyzedText), caller.packageName, caller.isManager)
```

- `Block`（含 `youlong` 关键字、编码/加密命令）→ **BLOCK**；
- `NeedConfirm` **不**直接转 ASK，仍交给能力模型，避免每条命令都弹窗。

### H1 + C3. 网关只按「完整命令行」判定；超限 fail-closed

| 文件 | 改动 |
|---|---|
| `policy/StdinGateway.kt` | 删除「180ms 空闲就 flush 半条命令」的逻辑；只在**换行/EOF**时判定；`for/do`、引号未闭合继续攒；没有换行且超过 **64KB** → 直接 BLOCK（`blockTooLarge()`），一个字节都不转发；语法不完整最多等 **10s**，超时同样 BLOCK（`blockIncomplete()`）；`looksIncomplete` 改为注释感知（`# don't` 不再被当成未闭合） |
| `policy/ShellScriptAnalyzer.kt` | `ScriptFacts.truncated`：超过 64KB 置位；`PolicyEngine.decide` 对 `truncated` → **ASK**（不再静默放行剩余部分） |
| 同上 | 补上此前完全不覆盖的命令：`rm/rmdir/unlink`（系统/数据目录 100 分、普通删除 40 分）、`reboot/shutdown/poweroff`（60）、`mkfs/wipe/dd of=/dev/block/mount remount`（100）、`pm install`（20）、`appops`、`input/sendevent/monkey`、`content`、`svc`、`dpm`（100） |

效果：把一条命令拆成多次写入（中间 sleep）不再能绕过目标保护/能力/评分；
64KB 填充也不能把危险命令藏到分析范围之外。

### H2. 快照原子写 + 解析失败不再负缓存

| 文件 | 改动 |
|---|---|
| `policy/VirusSnapshot.kt` | `current()`：解析失败/文件被删/超过 2MB 时**保留上一份有效快照**，且不更新 `loadedMtime`（下次重试）；告警按 mtime 节流 |
| 同上 | `parse()`：校验 `schema == youlong/virus-db/snapshot/1`；跳过 Gson null 元素；规则要求非空、≤4096 字符、`action` 白名单、`weight` 钳到 0..200、未知 `kind` 跳过；单条正则编译失败（含 Error）只跳过该条 |
| 同上 | `matchScript()`：单条规则匹配 `runCatching(Throwable)`，脚本上限 256KB |

### M1. App 侧解析结构校验

`VirusContentDb.parse()` 新增：`schema` 必须 `youlong/virus-db/` 前缀、`version` 非空、
特征集合非空；否则返回 `null` 走「保留旧版」流程。CDN 返回 200 + `{}` 不会再清空特征库。

---

## 二、构建与产物

| 项 | 结果 |
|---|---|
| 工具链 | Gradle **8.14**（从 `D:\Agent\tmp\gradle-8.14-bin.zip` 解压到 `D:\Agent\tmp\gradle-8.14-dist`；本机 PATH 无 gradle，wrapper 的 8.14 发行版缺 `bin`） |
| 命令 | `gradle.bat -p D:\anquan\anquan :app:assembleRelease --console=plain` |
| 结果 | ✅ **BUILD SUCCESSFUL in 3m 53s**（249 tasks，26 executed；最终版含变量展开与网关不完整超时加固） |
| 编译告警 | `VirusSnapshot.kt` 3 处 `Condition is always 'false'`（`cert == null` / `pkg == null` / `rule == null`，为防 Gson null 元素保留）；其余为 NDK/SDK 版本提示 |
| 产物 | `build_out/_app/outputs/apk/release/app-release-20261024-230907.apk`，**40,083,133 B** |
| 复制件 | `游龙安全护盾-9.2.9-release-20261024-fix3.apk` |
| SHA-256 | `51C097B79D1A03D832FE052E414FFA3CD36532C83ECAB8E8D90FCA6FFB4B3F2E` |
| 版本 / 签名 | `9.2.9 / 1234567954`；V3 证书 `d3d868c90dfcdfe45ffa6891ea8f2e2eb6ceb49bc63eea5fd1aad54c5d46ae2c`（与历史一致，可覆盖安装） |
| APK 内已确认 | `StellarPolicyStore`、`youlong/virus-db/snapshot/1`、`writePolicyFile`、`脚本超过`、`尝试修改内置特权策略`、`无法完整审计`、`删除系统/数据目录` 均在包内 |

---

## 三、语义变化（需要知道）

1. **管理器推送快照不再需要 shell**：`VirusContentDb.pushSnapshot` → Binder → `PolicyStore`。
   内置特权未连接时照旧跳过，下轮刷新重试。
2. **快照权限 0644**（原 0600）：内容是公开威胁情报（证书/规则），不含密钥；0644 可让
   内置特权在 root / shell 两种激活方式切换后仍读得到。目录本身仍由 shell 拥有。
3. **第三方应用写策略目录 = 直接 BLOCK**，不再只是「可能询问」。
4. **交互式 shell 的命令判定时机**：从「180ms 空闲」改为「换行/EOF」；用户逐行输入
   体验不变，但半条命令不会再被提前判定/转发。
5. **`pm install` 现在需要 `pkg_control` 能力**（未知应用会弹审批）。
6. **升级后必须重启内置特权服务**：`writePolicyFile` 是本轮新增的 AIDL 事务；
   升级后仍在跑的旧服务端不认识 code 113，快照推送会失败（App 下轮重试），
   重启服务后恢复。这与 ROUND24 的升级说明一致。

---

## 四、未完成 / 残余风险

1. **真机端到端未跑**：Binder 快照写入、篡改拦截、分块/超长绕过、审批链路、
   `pm list packages -3` 等常规命令 0 误杀，均需真机按 README §8 验证。
2. `transactRemote` / `getSystemService` / `getApplications(10001)` 的绕过面仍在（历史遗留），
   本轮未动；若走这些非 shell 通道，仍可能绕过新的策略。
3. `CallerIdentity` 仍只取第一个签名者、失败结果负缓存 60s（C5，未改）。
4. `action=ask` 仍无独立语义（按 warn 处理，高分才 block）；正则只有长度/单条隔离，
   没有超时或 RE2/J（C4 部分缓解）。
5. 管理器「病毒库」页状态机（M6 `refreshing` 不置位、M7 error 覆盖 ready）与
   扫描页可观测性（M4/M5）本轮未改。
6. root / shell 切换时，若 `stellar_guard` 目录由 root 创建，shell 身份可能无法
   `rename`（目录 0755）；表现为快照推送失败并在下轮重试，不会破坏旧快照。
7. `packages[]` 在 App/服务端仍未参与判定（M3，产品语义待确认）。
