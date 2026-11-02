# 第 23 轮改动汇总（2026-10-23）—— 指令确认新增「始终允许」

本轮只做一件事：给「已授权应用执行指令」的确认弹窗增加 **始终允许**，并把
「始终允许」记录做成可查看、可撤销的持久化数据。

> ⚠️ 本轮**没有**版本号变更，也**没有**在真机 / 编译环境验证（原因见第五节）。

---

## 一、需求（用户原话）

> 内置特权的指令拦截白名单中的所有应用，每执行一个命令都会询问用户，这样非常
> 麻烦，你给我改成将每个指令询问加一个始终允许选项（如果一个应用执行了一个
> 命令，用户选择了始终允许，下次同一个应用执行同一个命令，就无需用户确认，
> 若同一个应用执行其他命令，那还是要询问用户）若指令中含有冻结/卸载
> com.youlong.tool 或 com.youlong.hd 的命令，那还是要拦截的

拆成三条可验收规则：

| # | 规则 |
|---|---|
| R1 | 确认弹窗必须有第三个按钮「始终允许」（现有两个：允许一次 / 拒绝） |
| R2 | 记忆粒度 = **调用方应用 + 完全相同的指令文本**；同一应用换一条指令仍然询问 |
| R3 | 含 `com.youlong.hd` / `com.youlong.tool` 的冻结、卸载类指令**永远拦截**，不受「始终允许」影响 |

---

## 二、改完以后的行为

| 场景 | 行为 |
|---|---|
| 已授权应用执行任意指令（白名单内 / 外都一样） | 弹确认框：`允许一次` / `始终允许` / `拒绝` |
| 点「允许一次」 | 只放行这一次，不落记录（与改动前一致） |
| 点「始终允许」 | 放行这次，并记录 `(包名, 指令)`；下次同一应用执行**同一条指令**直接放行，不弹窗 |
| 该应用换一条指令（参数不同也算换） | 仍然弹窗询问 |
| 指令含 `youlong`（含冻结/卸载本产品、配套应用） | 服务端**直接拦截**并弹「已阻止」告警框，根本不会出现确认框，因此也没有「始终允许」的机会 |
| 管理器自己（`com.youlong.hd`）执行指令 | 不拦截（原有逻辑，本轮未动） |
| 配套应用 `com.youlong.tool` | 免确认（原有逻辑，本轮未动） |
| 撤销 | 管理器 → 指令拦截白名单 → 「已始终允许的指令」→ 逐条「撤销」/「清除」 |
| 重启设备 / 重启服务端 / 重装管理器 | 记录保留（`/data/local/tmp` 下的文件） |
| 记录文件损坏 / 被删 | 当作无记录，重新逐条询问（安全默认） |
| 单条指令超过 4096 字符 | 本次放行，但**不记忆**（防止记录文件被撑爆），下次仍询问 |
| 记录超过 300 条 | 淘汰最早的一条 |

> 说明：「指令拦截白名单」本身的语义没变（白名单应用只豁免「加密/编码指令」的
> 自动阻止，仍然逐条询问）。本轮新增的「始终允许」是一条**独立**的按指令记忆，
> 与白名单开关互不影响 —— 把应用移出白名单也不会自动撤销它已获得的
> 「始终允许」记录，需要到「已始终允许的指令」里撤销。

---

## 三、实现

### 3.1 一次「始终允许」的完整数据流

```
第三方应用 A
  └ Stellar.newProcess("pm list packages -3")
      └ ProcessManager.guard() → CommandInterceptor.inspect()
          ├ 规则 1：含 youlong？→ Block（弹告警框，结束）
          ├ 规则 2：com.youlong.tool？→ Allow
          ├ 规则 5：CommandAlwaysAllow.contains(A, "pm list packages -3")？
          │        ├ 命中 → Allow（不弹窗）
          │        └ 未命中 → NeedConfirm
          └ CommandGuard.askForConfirmation()
              ├ 写 /data/local/tmp/stellar_guard/<id>.req
              ├ Intent → CommandGuardActivity（三个按钮）
              └ 轮询 <id>.resp
                  ├ "ALLOW"        → 放行一次
                  ├ "ALWAYS_ALLOW" → CommandAlwaysAllow.add(A, 指令) → 放行
                  └ "DENY" / 超时  → SecurityException
```

### 3.2 存储

```
/data/local/tmp/stellar_guard/command_always_allow.txt
每行：<调用方包名><TAB><指令文本>
权限：0600（属主 = 服务端身份 shell/root）—— 普通应用连目录都列不了，
      无法伪造记录来给自己永久授权。
指令归一化：制表符/换行/回车 → 空格，连续空白压成一个空格，首尾去空白
            （与服务端 CommandInterceptor.flatten() 规则一致，保证「弹窗里看到的」
             和「下次比对的那条」是同一串）
```

### 3.3 改动清单

| 文件 | 类型 | 改动 |
|---|---|---|
| `embedded/server/.../service/process/CommandAlwaysAllow.kt` | **新增** | 「始终允许」记录的读写、缓存、上限、文件权限；`contains()` / `add()` |
| `embedded/server/.../service/process/CommandInterceptor.kt` | 修改 | 新增**规则 5**：在规则 1/2 之后、NeedConfirm 之前查「始终允许」；文件头判定总览补规则 5 与顺序说明 |
| `embedded/server/.../service/process/CommandGuard.kt` | 修改 | 新增常量 `REPLY_ALWAYS = "ALWAYS_ALLOW"`；读到该答复时落记录再放行；顺手清理已答复的 `.req/.resp`（原先成功路径不清理，会残留文件） |
| `embedded/server/.../service/process/CommandWhitelist.kt` | 注释 | 说明「始终允许」是独立机制 |
| `embedded/manager/.../authorization/CommandGuardActivity.kt` | 修改 | 确认框新增「始终允许」按钮（tertiary 配色）；`REPLY_ALWAYS` 答复 |
| `embedded/manager/.../authorization/CommandWhitelistActivity.kt` | 修改 | 新增「已始终允许的指令」卡片（条数/管理/清除）+ 管理弹窗（逐条撤销）+ 清除二次确认；文件读取与重写走内置特权 shell |
| `embedded/manager/src/main/res/values/strings.xml` | 修改 | `command_guard_allow_always`（英文） |
| `embedded/manager/src/main/res/values-zh-rCN/strings.xml` | 修改 | `command_guard_allow_always`（中文） |

### 3.4 为什么自我保护的命令仍然拦得住（R3 的关键）

- 拦截发生在**服务端**（`CommandInterceptor`），不在 UI 层；
- 规则 1 判断的是「命令文本里出现 `youlong`」→ 直接 `Block`，这一步在
  「始终允许」查询（规则 5）**之前**；
- 因此 `pm uninstall com.youlong.tool`、`pm disable-user --user 0 com.youlong.hd`、
  `pm suspend ... com.youlong.hd` 等命令，无论用户此前对同一应用记录过多少
  「始终允许」，都只会看到「已阻止」告警框，不会执行；
- 管理器界面也无法把这记录写进去「骗过」服务端：管理器只有查看/撤销入口，
  真正写记录的是服务端（用户点按钮后由 `CommandGuard` 落盘）。

---

## 四、兼容与降级

| 情况 | 表现 |
|---|---|
| 旧 APK（升级前） | 无此功能，不受影响 |
| 升级 APK 后**服务端进程仍是旧版本**（上次启动的 `stellar_server` 还在跑） | 旧服务端不认识 `ALWAYS_ALLOW`，会按「拒绝」处理（`startsWith("ALLOW")` 不成立）。**升级后请重启内置特权服务**；杀掉旧 `stellar_server` 后重新激活即可 |
| 新服务端 + 新管理器 | 正常（两者在同一 APK 内，源码一起编译） |
| 内置特权未连接 | 管理器打开白名单页时会 Toast「读取/更新始终允许记录失败」，白名单开关本身仍可用 |
| `/data/local/tmp` 文件被清理 | 记录丢失，重新逐条询问（不会误放行） |

---

## 五、验证状态

### 5.1 已完成的检查

| 检查 | 结果 |
|---|---|
| `strings.xml`（values / values-zh-rCN）XML 合法性 | ✅ 两文件均通过 XML 解析 |
| 新增字符串存在性 | ✅ 两个语言各 1 条 |
| **服务端 4 个文件编译**（`CommandAlwaysAllow` / `CommandInterceptor` / `CommandGuard` / `CommandWhitelist`） | ✅ 用 Kotlin 2.2.10 独立编译通过（0 error；`Logger` / `ServerConstants` / `ActivityManagerApis` 用同签名 stub，`android.jar` 取 SDK 36） |
| **管理器 2 个文件编译**（`CommandGuardActivity` / `CommandWhitelistActivity`） | ✅ 用 Kotlin 2.2.10 + Compose 编译器插件 2.2.10 编译通过（0 error；`R` / `StellarTheme` / `AppShape` / `Logger` / `Stellar` 用同签名 stub，Compose/AndroidX 取本机 Gradle 缓存里的真实产物） |
| 判定顺序复核 | ✅ 规则 1 → 2 → 5 → 3，自我保护命令不可能走到规则 5 |

> 说明：这是「单文件级」编译验证，不是整包构建。它能证明改动文件没有语法/类型错误、
> 引用的 Compose/AndroidX API 与依赖签名匹配；但 `R`、主题、`Stellar` 用的是 stub，
> 真实资源 ID 与模块间可见性仍需完整 Gradle 构建确认。

### 5.2 ⚠️ 未完成的验证（必须知道）

- **没有整包构建**：当前机器缺 `platforms;android-37.0`、`build-tools;37.0.0`、
  `ndk;29.0.13113456` 与 `D:\tmp`，按《编译指南》直接 `gradlew assembleRelease`
  会在配置阶段失败。交付前必须在具备上述环境的机器上完整编译一次。
- **没有真机验证**（9.2.x 一向没有真机记录）。

### 5.3 建议的真机验证步骤（编译出包后）

```bash
# 1) 安装并启动内置特权服务（管理器 → 启动）
# 2) 在「指令拦截白名单」里勾选一个测试工具（如 MT 管理器 / ShizukuRunner）
# 3) 让它执行一条指令，确认弹窗是三个按钮
# 4) 点「始终允许」→ 命令应执行；logcat 应出现：
#      CommandGuard: 用户选择了「始终允许」
#      CommandAlwaysAllow: 「始终允许」记录已保存，共 1 条
# 5) 再执行完全相同的指令 → 不应弹窗；logcat：
#      CommandInterceptor: 命中「始终允许」免确认
# 6) 让它执行另一条指令 → 应重新弹窗
# 7) 执行 pm uninstall com.youlong.tool → 应直接「已阻止」，无确认框
# 8) 管理器 → 指令拦截白名单 → 管理 → 撤销该条 → 再执行 → 应重新弹窗
adb shell cat /data/local/tmp/stellar_guard/command_always_allow.txt   # root/ shell 可看
```

---

## 六、未做 / 后续建议

1. **未更新 `NOTICE`**：新增/修改的文件都是本工程自有（Apache-2.0）或本工程新增
   文件，不涉及 MPL-2.0 的文件头修改声明义务；同时 `NOTICE` 与 APK 内两份
   `open_source_licenses.txt` 必须逐字一致，改动成本较高，留待合规轮次一起处理。
2. **未加自动化测试**：工程没有测试基建。建议后续给 `CommandInterceptor` 抽一个
   可注入的「always-allow 查询」接口，再用 `tools/verify_command_rules.ps1`
   的离线思路补 3 条用例（同指令命中 / 换指令不命中 / 自我保护命令不被记忆绕过）。
3. **未做「记录可见性」的日志审计**：例如记录何时、由哪次弹窗产生。当前只记
   `LOGGER.i`；日志本身可在管理器「日志」页看。
4. **潜在竞态（已知、可接受）**：管理器撤销记录时会整体重写文件；若恰好同时
   服务端在追加一条新记录，后写者覆盖先写者，可能丢一条记录（不会误放行，
   只会多问一次）。记录量小、概率低，暂不引入锁文件。
