# 第 24 轮改动汇总（2026-10-24）—— 内置特权按《游龙 Shizuku 防御方案》加固

本轮把《游龙 Shizuku 防御方案》（`analysis/游龙Shizuku防御方案.md`）落到内置
Stellar 特权内核里，核心是**接管 App 的 stdin 管道**：不再让它把脚本直接喂给
真实 `sh`，而是先经过服务端网关做审计、确定性拦截与能力审批，放行才转发。

> ⚠️ 本轮改动**未经整包构建与真机验证**（本机缺平台 37 / NDK 29，原因见第五节）。
> 已完成：服务端/管理器改动逐个文件编译通过 + 策略引擎离线样本回归（见 5.2）。

---

## 一、要解决的问题（方案 §2/§3）

样本「终末神明Ultra」（`mio.TerminalDeity.Ultra`）只执行 `newProcess(["sh"])`，
真正的攻击脚本写进这个 `sh` 的 stdin，然后 close：

```
PKG=com.youlong.hd
for c in $(cmd package dump $PKG ...); do pm disable-user --user 0 "$c"; done
pm disable-user --user 0 $PKG
```

原实现把 App 的 stdin 直接透传给真实进程，服务端只看到 argv=`sh`，看不到脚本，
所以既拦不住、也留不下证据。

**本轮把 stdin 换成服务端代理管道**：App 写进来的字节先到网关 → 网关拿到完整
脚本 → 判定 → 放行才写进真实 `sh`；拒绝则一个字节都不写，并回收进程树。

---

## 二、落地范围（对应方案四层）

| 层 | 本轮实现 | 说明 |
|---|---|---|
| **L1 审计** | ✅ stdin 全量（限 16KB/会话）+ 调用方（uid/包名/证书）+ argv + 判定 + 评分 + 退出码 → `shell_audit.jsonl` | 审计只记录、不改变行为；管理器自身无 stdin 的会话不记，避免刷屏 |
| **L2 确定性拦截** | ✅ ① 签名证书黑名单（`ca89a097…` 克隆证书、`6207a27d…` 发布证书）② 目标保护（本产品 / 配套 / 系统关键包 + 文件追加） | 命中直接拒绝并弹「已阻止」，不弹审批、不给社工机会 |
| **L3 能力作用域** | ✅ `shell_basic / pkg_control / settings_secure / proc_control / persistence / interactive`；未知应用按需审批，可「允许一次 / 始终允许该能力 / 拒绝」 | 授权粒度是能力，不是命令；恶意软件换写法也要用同一类能力 |
| **L4 行为链评分** | ✅ 组件级批量禁用 +50、枚举后批量禁用 +40、前台杀手 +60、持久化看门狗 +40、无障碍静默写入 +40、大脚本 +20、编码/动态执行 +40、危险目标无法解析 +20 | ≥100 拒绝；60–99 审批；<60 放行（受信应用 ≥100 改为审批） |
| 收尾（恢复/上报） | ⚠️ 部分 | 已做：进程树回收（`/proc` 子孙 PID 逐个 kill）。未做：删除落地文件、恢复被禁组件/无障碍设置、封禁与后端上报、cgroup 隔离 |
| 审批 UX | ✅ 脚本级审批弹窗（谁 / 什么能力 / 为什么 / 评分 / 命令行 / 脚本预览 + 三按钮）；能力授权可在管理器查看/撤销 | 复用既有文件握手，不新增 AIDL |

**未做（明确记录）**：stdout/stderr 审计、文件模式 TOCTOU 的副本执行、后台无界面
时强制不弹窗、威胁情报上报、`transactRemote` 绕过面（见第七节）。

---

## 三、实现

### 3.1 数据流

```
第三方 App
  └ Stellar.newProcess(["sh"])
      └ ProcessManager
          ├ CallerResolver：uid → 包名 + 证书 SHA-256 + 信任等级
          ├ PolicyEngine.precheck：签名黑名单 / argv 里的受保护目标 → 直接拒绝
          ├ applyScriptFilePolicy：sh /path/x.sh → 先读文件再判定
          ├ CommandInterceptor（历史规则）：受保护应用 / 批量 / 卸载冻结确认
          └ RemoteProcessHolder(session)
              └ getOutputStream() 返回「服务端管道的写端」
                  └ StdinGateway 读端：
                      攒块（脚本模式攒到 EOF/空闲；交互模式 180ms 切块）
                        ├ 判定 BLOCK → 不转发、杀进程树、弹「已阻止」、写审计
                        ├ 判定 ASK   → 脚本级审批弹窗（30s 超时=拒绝）
                        └ 判定 ALLOW → 写入真实 sh 的 stdin
```

### 3.2 文件清单

**服务端新增（`embedded/server/.../service/process/policy/`）**

| 文件 | 作用 |
|---|---|
| `PolicyConfig.kt` | `Capability` 枚举、默认黑名单/受保护/受信名单、`policy_mode.txt`、`scope_grants.txt`、名单文件读取与缓存 |
| `CallerIdentity.kt` | uid → 包名（PackageManager 优先）+ 签名证书 SHA-256 + 信任/黑名单判定 |
| `ShellScriptAnalyzer.kt` | 语句拆分、变量展开（`$VAR`/`${VAR}`）、危险动词与目标提取、能力分类、行为链评分 |
| `PolicyEngine.kt` | 决策顺序：管理器 → 黑名单 → 目标保护 → 系统组件 → 受信 → 评分/能力 → 放行；只审计模式降级 |
| `ShellSession.kt` | 会话状态（stdin 累计、判定、阻断、结束审计） |
| `StdinGateway.kt` | stdin 代理网关（攒块/判定/转发/阻断/进程回收） |
| `AuditLog.kt` | JSONL 审计（0600、2MB 轮转） |
| `ProcessTree.kt` | `/proc` 扫子孙 PID 后逐个 kill（不按进程组 kill，避免杀死服务端自己） |

**服务端修改**

| 文件 | 改动 |
|---|---|
| `ProcessManager.kt` | 接入调用方解析、precheck、脚本文件分析、PTY 审批、会话审计；`guard()` 改为接收会话 |
| `api/RemoteProcessHolder.kt` | `getOutputStream()` 改为返回代理管道；`waitFor/destroy` 写审计、阻断返回 1、回收进程树 |
| `api/RemotePtyProcessHolder.kt` | 会话审计（PTY 交互内容无法逐条拦截） |
| `CommandGuard.kt` | 新增 `kind=policy` 请求/答复协议与 `askPolicyConfirmation()`；新增 `ALLOW_ALWAYS` token |
| `CommandInterceptor.kt` | 裸 shell（无 `-c`、无脚本文件）不再按命令行询问，交给 stdin 网关判定 |
| `CommandWhitelist.kt` | 注释说明「能力授权」是独立机制 |

**管理器修改**（由 `policy-ui` 完成）

| 文件 | 改动 |
|---|---|
| `CommandGuardActivity.kt` | 新增 `kind=policy` 分支与 `PolicyGuardDialog`：谁 / 什么能力 / 风险 / 评分 / 命令行 / 脚本预览 + 拒绝 / 允许一次 / 始终允许该能力 |
| `CommandWhitelistActivity.kt` | 新增「已授权能力（Shizuku）」卡片与弹窗：读取/撤销 `scope_grants.txt`、全部清除 |
| `values/strings.xml`、`values-zh-rCN/strings.xml` | 新增 7 条 `command_guard_policy_*` |

### 3.3 配置与数据文件（`/data/local/tmp/stellar_guard/`）

| 文件 | 格式 | 说明 |
|---|---|---|
| `policy_mode.txt` | `enforce`（默认）/ `audit` | `audit` = 只审计不拦截（新策略层的灰度/回退开关） |
| `bad_certs.txt` | 每行一个证书 SHA-256 | 追加进内置黑名单（内置两条样本证书） |
| `trusted_apps.txt` | 每行一个包名 | 追加进内置受信名单（内置 `com.youlong.tool`） |
| `protected_apps.txt` | 每行一个包名 | 追加进内置受保护名单（内置本产品/配套/系统关键包） |
| `scope_grants.txt` | `包名<TAB>能力id` | 「始终允许该能力」的记录（0644→0600） |
| `shell_audit.jsonl` | 每行 JSON | 会话审计，0600，超 2MB 轮转为 `.1` |

> 名单是「内置默认值 ∪ 文件追加」：文件只能加、不能清空安全底线。

### 3.4 决策矩阵

| 调用方 / 情况 | 结果 |
|---|---|
| 管理器自身 `com.youlong.hd` | 放行（仍审计） |
| 签名命中黑名单 | **拒绝**（不弹窗） |
| 脚本/argv 操作受保护包（`com.youlong.hd`、`com.youlong.tool`、系统关键包） | **拒绝**（不弹窗） |
| 受信应用（`com.youlong.tool` 等） | 放行；行为链 ≥100 时改为审批 |
| 未知应用 + 行为链 ≥100 | **拒绝** |
| 未知应用 + 缺少所需能力 | 审批（授权后放行/撤销） |
| 未知应用 + 行为链 60–99 | 审批 |
| 未知应用 + 编码/动态执行 | 审批 |
| 只读/普通命令 | 放行（仍审计） |
| 未知应用打开 PTY | 审批「交互式终端」能力（默认拒绝） |
| `policy_mode.txt=audit` | 以上 BLOCK/ASK 一律降级为放行，只记录 |

---

## 四、兼容与降级

| 情况 | 表现 |
|---|---|
| 旧 APK 升级 | 服务端类随新 APK 一起加载；**升级后请重启内置特权服务**，否则仍在跑旧服务端逻辑 |
| 管理器自身命令 | 完全不受影响（`isManager` 早退） |
| 应用不写 stdin（只执行命令） | 不创建网关，按既有命令行规则处理 |
| 应用不读 stdin 也不写 | 无网关，无审计；进程结束由 `waitFor/destroy` 补审计 |
| PTY（rish / 终端类） | 管理器与受信应用放行；未知应用弹一次授权，拒绝则该次会话不建立 |
| 拒绝/超时 | 不写入任何字节；进程树被回收；`waitFor` 返回 1；审计记录 `blocked` |
| 审计文件写失败 | 只打日志，不影响命令执行 |

---

## 五、验证

### 5.1 已完成

| 项 | 结果 |
|---|---|
| **整包构建 `:app:assembleRelease`** | ✅ **BUILD SUCCESSFUL in 8m 7s**（249 tasks）；APK 40,002,337 B；SHA-256 `49A10B8F…C6C0`；V3 签名证书与历史一致；APK 内已确认新策略类（`StellarStdinGateway` 等）、8 个策略数据文件名、7 条 `command_guard_policy_*` 资源 |
| 服务端 8 个新增 + 6 个修改文件编译（Kotlin 2.2.10，android.jar 36，外部依赖用同签名 stub） | ✅ 0 error |
| 管理器 2 个修改文件编译（Kotlin 2.2.10 + Compose 编译器插件，本机 Gradle 缓存里的真实 Compose/AndroidX） | ✅ 0 error |
| 两处 `strings.xml` XML 解析 + 14 个 `R.string` 键存在性 | ✅ |
| **策略引擎离线样本回归**（真实样本脚本重建 + 合法命令） | ✅ 见 5.2 |

### 5.2 离线回归结果（`ShellScriptAnalyzer` + `PolicyEngine`，未知应用）

| 用例 | 结果 |
|---|---|
| 样本 `disableYoulong`（`$PKG` 展开 + 组件循环 + `pm disable`） | **BLOCK**，score 130，命中目标保护 `com.youlong.hd` |
| 样本 S1 看门狗（无障碍写回 + nohup + 心跳） | **BLOCK**，score 100（持久化/无障碍/大脚本） |
| 样本 S3 前台杀手（`dumpsys window` + `am force-stop $FRONT_PKG`） | **BLOCK**，score 100（前台杀手/目标无法解析/大脚本） |
| 样本启动器（base64 落盘 + chmod + nohup + force-stop/suspend） | **BLOCK**，score 100（编码/持久化/大脚本） |
| 合法 `pm list packages -3` | ALLOW |
| 合法单个第三方 `pm disable-user --user 0 com.example.someapp` | ASK（`pkg_control` 能力） |
| `pm uninstall com.youlong.tool` | **BLOCK**（目标保护） |
| `pm list packages \| grep com.youlong.hd` | ALLOW（只读） |
| `settings put secure enabled_accessibility_services …` | ASK（`settings_secure` 能力 + 无障碍信号） |
| `am force-stop com.android.settings` | **BLOCK**（系统关键包目标保护） |
| `echo … \| base64 -d \| sh` | ASK（编码/动态执行） |
| 黑名单签名调用方执行 `pm list packages` | **BLOCK**（签名黑名单） |
| 受信应用执行 `disableYoulong` | **BLOCK**（目标保护优先于受信） |
| 管理器自身执行 `disableYoulong` | ALLOW（管理器豁免） |

### 5.3 未完成（交付前必须补）

1. **真机端到端**（9.2.x 一向没有真机记录）：
   - 样本模拟：授权一个测试 APK → `newProcess(["sh"])` 写 `disableYoulong` 脚本 →
     应「已阻止」，`stellar_server` 日志出现 `StdinGateway`/`PolicyEngine` 记录；
   - 合法工具：MT/冰箱/黑阈 各跑一次常规命令，确认 0 误杀；
   - 审批链路：未知应用执行 `pm disable-user 第三方` → 弹「请求使用【包管理】能力」→
     允许一次/始终允许/拒绝三种路径；管理器中撤销后重新询问；
   - PTY：未知应用打开 PTY 弹「交互式终端」授权；
   - 审计：`adb shell su -c 'cat /data/local/tmp/stellar_guard/shell_audit.jsonl'` 可看到完整脚本。
2. **性能/稳定性观察**：交互式 shell 每块 180ms 决策延迟；审批超时 30s 内 App 的
   写入会被背压（管道队列上限约 2MB）。

### 5.4 构建记录（2026-10-24）

- 本机补装：`platforms;android-37.0`、`build-tools;37.0.0`、`ndk;29.0.13113456`，
  并创建 `D:\tmp`（`gradle.properties` 里 `-Djava.io.tmpdir=D:/tmp`）。
- 清理了上一台机器路径（`E:/11/anquan/...`）留下的 `build_out/`、`.gradle/`、
  `.kotlin/`、`app/.cxx/`、`app/.prefab/`，避免 CMake 缓存指向旧路径。
- wrapper 下载 Gradle 8.14 过慢，改用本机已解压的 8.14 发行版直接构建
  （`gradle.bat -p D:\anquan\anquan :app:assembleRelease`）。
- 产物：`build_out/_app/outputs/apk/release/app-release-20261024-191258.apk`
  → 已复制到工程根目录 `游龙安全护盾-9.2.9-release-20261024.apk`。
- 未改版本号：`versionName 9.2.9` / `versionCode 1234567954`（与上一版相同，覆盖安装可行）。

---

## 六、已知绕过与残余风险

1. **`transactRemote` / `getSystemService` / `getApplications(10001)` 仍然没有设防**
   （历史遗留，前几轮已记录）——恶意应用可以不走 shell 命令面；本轮未改动。
2. **`newProcess` 的 argv 直连危险命令**（非 `sh`）仍走既有「每条命令询问」逻辑，
   没有套用能力作用域；能力模型目前只覆盖 stdin 脚本与 PTY。
3. **编码/动态脚本**：`base64 | sh`、远端拉取等只能看到密文；本轮对编码执行改为
   ASK，但不做硬拦（避免误杀合法用途）。
4. **TOCTOU**：`sh /path/x.sh` 是「先读后执行」，检查后文件仍可能被偷换；后续应改成
   「复制到服务端独占目录 → 分析副本 → 执行副本」。
5. **进程树回收**依赖反射取 PID（`Process.pid()` / `ProcessImpl.pid`）；取不到时只能
   杀根进程，nohup 子进程可能残留。
6. **stdout/stderr 未审计**（方案 L1 的可选字段），排障时只能看到 stdin 与退出码。
7. **审批无「后台不弹窗直接拒绝」**：服务端用 shell 身份拉起 Activity，超时 30s 兜底；
   若系统限制弹窗，等同于拒绝（安全默认）。
8. **受信名单只能在 `/data/local/tmp/stellar_guard/trusted_apps.txt` 手工维护**，暂无 UI。
9. **`policy_mode.txt=audit` 只降级新策略层**，既有的命令行规则（CommandInterceptor）
   仍然生效。

---

## 七、后续建议（按优先级）

1. 补整包构建 + 真机端到端（5.3 清单），确认样本 100% 拦截且日常工具 0 误杀。
2. 把 `transactRemote` 等非 shell 通道纳入能力模型（或对未知应用直接拒绝）。
3. 文件模式改为「副本执行」，消除 TOCTOU。
4. 增加「受信应用」管理 UI 与 `policy_mode` 开关 UI。
5. 收尾阶段补：删落地物、恢复被禁组件与无障碍设置、违规累计与封禁、威胁情报上报。
6. 合规：本轮新增文件均为本工程自有（Apache-2.0），`NOTICE` 与 APK 内两份
   `open_source_licenses.txt` 未同步（与既有待办一致），留待合规轮次统一处理。
