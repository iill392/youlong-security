# 第 31 轮改动汇总（2026-10-25）—— 执行 sh 文件必审内容 + 去掉签名黑名单

本轮按用户要求只做三件事：

1. **只要应用执行 sh 文件，就先读文件内容审查**；
2. **文件被加密 / 含 youlong / 冻结或删除所有应用 / 安装应用等危险命令 → 直接拦截**；
3. **去掉签名（证书）黑名单**。

> 本轮没有动第 30 轮的黑名单包名/名称逻辑，也没有处理之前审计出的 user service / rish / Shizuku 等侧门。

---

## 一、去掉签名黑名单

已删除/移除：

- `PolicyConfig.kt`
  - `bad_certs.txt` 读取；
  - 内置证书 `ca89a097…`、`6207a27d…`；
  - `isBlacklistedCert()`。
- `CallerIdentity.kt`
  - `isBlacklisted` 字段及赋值。
- `PolicyEngine.kt`
  - `precheck` / `decide` 里的「调用方签名命中黑名单」直接拦截；
  - `blacklistReason()` 里的证书匹配（`VirusSnapshot.matchCert`）；
  - 病毒库快照 `certs[]` 本来就是空，现在服务端也不再检查证书。

保留：

- 四个列表（safe1/safe2 包名 + mingcheng1/2 名称）的包名/名称拦截；
- 脚本内容审计；
- 目标保护、审批、审计等。

**注意**：签名黑名单去掉后，「改包名 + 改应用名 + 重签名」的克隆样本不会再因为证书被识别，只能靠包名/名称名单和 sh 内容审计。这是用户明确要求的行为。

---

## 二、执行 sh 文件必审内容

新增 `embedded/server/.../policy/ScriptFileInspector.kt`，并重写
`ProcessManager.applyScriptFilePolicy()`。现在会在**进程创建前**把本次执行会读到的脚本文件找出来，逐个读内容审查。

### 2.1 覆盖的执行形态

| 形态 | 例子 |
|---|---|
| 直接 sh 文件 | `sh /data/local/tmp/x.sh`、`sh x.sh` |
| 启动器包装 | `env sh /path`、`busybox sh /path`、`toybox sh /path`、`timeout 5 sh /path`、`nohup sh /path`、`setsid sh /path`、`nice sh /path`、`stdbuf … sh /path` |
| `sh -c` 内层 | `sh -c 'sh /path'`、`sh -c '. /path'`、`sh -c 'source /path'`、`sh -c '$(cat /path)'` |
| 脚本内引用 | `. /path`、`source /path`、`sh /path`、`exec /path`、`sh < /path`、`exec 0</path`、`cat /path \| sh`、`$(cat /path)`、`$(< /path)` |
| 直接执行脚本 | `newProcess(["/data/local/tmp/x.sh"])`（`.sh` 后缀或 shebang） |
| PTY 通道 | `newPtyProcess(["sh","/path/x.sh"])` 同样调用本检查 |

### 2.2 文件级硬拦截

- **超过 1MB**：无法完整审计 → BLOCK（fail-closed）；
- **读不到**：BLOCK；
- **加密/二进制**：出现 NUL，或控制字符 ≥10% → BLOCK；
- **危险命令**：命中 `DangerousScriptDetector` → BLOCK；
- **引用动态路径**：`source $X`、`sh $X`、`sh $(...)` 等无法确认实际文件 → BLOCK（fail-closed）；
- **引用的文件本身**也会递归检查（最多 2 层、8 个文件）；
- **嵌套脚本**也会检查（脚本里的 `. /path`、`sh /path`）。

### 2.3 危险命令列表（本轮补充后）

- `youlong`（不区分大小写，任意位置）；
- 批量冻结/卸载：
  - `pm disable-user … -a/--all`、`pm uninstall … -a/--all`
  - `cmd package disable-user/uninstall … -a/--all`
  - `pm list packages` / `cmd package list packages` 后接批量 `pm/cmd package disable/uninstall/suspend/hide`
  - `killall` / `pkill` / `am kill-all` / `am force-stop *`
  - 中文「冻结所有应用 / 卸载所有应用 / 禁用所有应用 / 清除所有应用 / 删除所有应用」
- 删除系统/数据：
  - `rm -rf /`、`/system`、`/sdcard`、`/data`、`/data/app`、`/data/data`、`/data/system`
  - 中文「删除所有应用」
- 系统破坏：`reboot`/`shutdown`/`poweroff`、`mkfs`/`wipe`、`dd of=/dev/block`、`mount remount`
- 无障碍/持久化/编码：`settings put secure enabled_accessibility_services`、`nohup`、`base64 -d`、`openssl`、`gpg -d`、`xxd -r`、`eval`
- 安装应用：
  - `pm install`、`pm install-existing`
  - `cmd package install`、`cmd package install-existing`
  - 中文「安装应用」
- 设备管理员：`dpm set-device-owner` 等

### 2.4 判定顺序

1. `newProcess`/`newPtyProcess` → 调用方身份 + 包名/名称黑名单；
2. `applyScriptFilePolicy` → 找出本次执行的脚本文件（含 `-c`/`.`/`source` 引用）→ 读内容 → 加密/危险命令/动态路径硬拦截；
3. 文件内容再交给 `PolicyEngine.evaluate` 做评分/能力审批（ASK 仍会弹窗）；
4. 最后才 spawn 真实进程。

stdin 网关的 `. /path`、`source /path`、`sh /path` 等引用也会经过同一个 `ScriptFileInspector` 检查。

---

## 三、构建与产物

| 项 | 结果 |
|---|---|
| 工具链 | Gradle 8.14（`D:\Agent\tmp\gradle-8.14-dist`） |
| 命令 | `gradle.bat -p D:\anquan\anquan :app:assembleRelease --console=plain` |
| 结果 | ✅ **BUILD SUCCESSFUL in 3m 56s**（249 tasks，26 executed） |
| 产物 | `build_out/_app/outputs/apk/release/app-release-20261025-094102.apk`，**40,105,269 B** |
| 复制件 | `游龙安全护盾-9.2.9-release-20261025-shaudit.apk` |
| SHA-256 | `830EA1A8CCD051D066B3816760A7BD5763BAAAD704880A29834CC53058C928C3` |
| 版本 / 签名 | `9.2.9 / 1234567954`；V3 证书 `d3d868c9…`（覆盖安装） |
| APK 内已确认 | `脚本文件包含危险命令`、`脚本引用的文件不安全`、`脚本文件疑似加密/二进制`、`删除所有应用`、`安装应用`、`批量冻结应用` 均在包内；旧证书 `ca89a097…`/`6207a27d…` 已不存在 |

---

## 四、真机实测（2026-10-25，vivo V2133A / Android 14 / adb shell）

- 设备安装 `9.2.9`（versionCode `1234567954`），用新 APK 的
  `libstellar.so --apk=<new base.apk>` 重启内置特权服务，确认
  `stellar_server` 的 `CLASSPATH` 已指向 `com.youlong.hd` 的新 APK。
- 临时构建了一个最小 Stellar 客户端 App（`com.youlong.testclient`，声明
  `StellarProvider` + `roro.stellar.permissions=stellar`），通过
  `am start` 触发 `newProcess`；测试完成后已卸载并删除该模块。

| 测试 | 结果 |
|---|---|
| `sh /sdcard/yl_test_a.sh`（内容 `echo youlong`，文件名中性） | **BLOCK**：`脚本文件包含危险命令：含 youlong 关键字` |
| `env sh /sdcard/yl_test_a.sh` | **BLOCK**：同上 |
| `sh -c 'sh /sdcard/yl_test_a.sh'` | **BLOCK**：同上 |
| `sh -c '. /sdcard/yl_test_a.sh'` | **BLOCK**：同上 |
| `sh -c 'cat /sdcard/yl_test_a.sh \| sh'` | **BLOCK**：弹窗显示 `脚本引用的文件不安全：脚本文件包含危险命令：含 youlong 关键字` |
| 外层脚本 `. /sdcard/yl_test_a.sh`（嵌套引用） | **BLOCK**：引用文件内容命中 youlong |
| 脚本内容 `pm disable-user --user 0 -a` | **BLOCK**：`批量冻结应用` |
| 脚本内容 `pm uninstall -a` | **BLOCK**：`批量卸载应用` |
| 脚本内容 `pm install /sdcard/nonexistent.apk` | **BLOCK**：`通过 shell 安装应用` |
| 脚本内容 `rm -rf /data/app` | **BLOCK**：`删除系统/数据目录` |
| 1KB 随机二进制 `yl_test_encrypted.bin` | **BLOCK**：`脚本文件疑似加密/二进制，无法审计` |
| 安全脚本 `echo safe` | 未命中内容规则；走普通「命令执行确认」弹窗（低风险） |

说明：硬拦截弹窗（`CommandGuardActivity`「游龙安全护盾已阻止此威胁」）需要点「好的」后
binder 调用才返回；实测拦截原因和弹窗内容均正确。

签名黑名单删除的验证：新 APK 内已不存在 `ca89a097…`、`6207a27d…` 字符串；
设备上没有对应私钥的样本证书，未做“同证书克隆体”行为测试。

---

## 五、升级后必须做

**安装后重启一次内置特权服务**（管理器首页停止 → 启动，或重启手机）。本轮改的是服务端 `ProcessManager`/`PolicyEngine`，旧进程不重启不会生效。

---

## 六、已知限制 / 残余风险

1. **签名黑名单已删**：改包名+改应用名+重签名即可脱离名单；这是用户要求的行为。
2. **只审 sh 文件**：不经过文件执行、直接写 stdin 的脚本仍由 stdin 网关按行判定；`env` 变量承载的 payload（如 `X=pm …; echo $X | sh`）不在本轮范围。
3. **TOCTOU**：先读文件、后执行原文件，检查到 spawn 之间文件仍可能被偷换；后续可改为「复制到服务端独占目录执行副本」。
4. **动态路径 fail-closed**：`source $HOME/...`、`sh $X` 会被直接拦；正常脚本如依赖变量拼接路径可能误伤（可后续改成解析变量后再审）。
5. **stdin 里的相对路径引用**（如 `. lib.sh`）在不知道 cwd 时可能解析不到；直接文件执行和 `-c` 引用不受影响。
6. 之前审计出的 **user service / rish / Shizuku / 策略目录可写** 等侧门本轮未处理，仍可绕过整个 shell 防御。
7. 危险命令列表是「宁可误杀」的确定性列表，正常脚本里的 `nohup`、`pm install`、`rm -rf /data/local/tmp/xx` 也会被拦。
