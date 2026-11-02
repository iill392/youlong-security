# 第 32 轮改动汇总（2026-10-25）—— 外部白名单打通内置特权 + 只询问 + 风险评估

本轮按用户要求做三件事：

1. **外面的「白名单管理」和内置特权的「指令拦截白名单」连在一起**（同一份列表）；
2. 用户加入白名单的应用**不再硬拦截命令，只弹窗询问是否执行**；
3. **每个执行命令的弹窗都显示风险评估**（分数 + 等级 + 原因）。

---

## 一、白名单打通（双向）

### 数据源

- 外面：`WhitelistActivity` 的 `whitelist_pkgs` + 内置默认白名单
  （支付宝/微信/拼多多/游龙 zoo/QQ）。
- 内置特权：`/data/local/tmp/stellar_guard/command_whitelist.txt`
  （管理器「指令拦截白名单」页读写，服务端 `CommandWhitelist` 读取）。

### 同步实现

- `StellarUtils.writeCommandWhitelist()`：管理器通过 Binder
  `IStellarService.writePolicyFile("command_whitelist.txt", ...)` 写入。
- `PolicyStore` 允许写 `command_whitelist.txt`，并校验：
  - 每行一个包名；
  - 最多 1000 行；
  - 只接受合法包名格式（防止变成任意文件写入口）。
- `WhitelistActivity.syncToStellar()`：
  - 把「内置默认 + 用户自定义」写进 `command_whitelist.txt`；
  - 同时镜像到管理器页的 `stellar_command_whitelist` SharedPreferences；
  - 打开白名单页、保存白名单、从救援页自动加入、开启「全部信任」时都会同步。
- `CommandWhitelistActivity`（管理器页）：
  - 打开时合并外面 `whitelist_pkgs` 与本页列表；
  - 勾选/取消时同时更新外面的 `whitelist_pkgs`；
  - 两边看到同一份列表。

> 实测：从首页进入「白名单管理」后，
> `command_whitelist.txt` 被同步为 5 个默认包名（101 字节），说明外部白名单已成功写入内置特权。

---

## 二、白名单应用：不再硬拦截，只询问

服务端所有原本会 **BLOCK** 的路径，对白名单应用统一改成 **ASK**：

| 位置 | 处理 |
|---|---|
| `PolicyEngine.precheck` | 包名/名称黑名单、youlong、批量冻结卸载、目标保护、策略篡改等 BLOCK → ASK |
| `PolicyEngine.evaluate` | stdin 脚本/命令的 BLOCK → ASK |
| `ProcessManager.precheck` | 收到 ASK 后弹 `askPolicyConfirmation`，用户允许才继续 |
| `ProcessManager.applyScriptFilePolicy` | 加密/二进制、youlong、嵌套引用、危险 sh 文件等硬拦截 → ASK |
| `ProcessManager.guard` | `CommandInterceptor` 的 Block / NeedConfirm → ASK（不再 Block） |
| `PolicyEngine.blacklistReason` | 白名单应用在 `transactRemote`/`getSystemService`/`getApplications` 不再因黑名单被拒 |

细节：

- 白名单应用遇到 `youlong`、加密脚本、批量冻结/卸载、安装应用等高危命令时，
  弹窗会显示 **极高风险**，由用户决定「允许一次 / 拒绝」。
- 用户允许后命令正常执行（`skipGuard` 避免同一次执行重复弹窗）。
- 管理器自身仍然完全放行，不受白名单逻辑影响。
- 白名单不会把本来就直接放行的只读命令变成每条都弹窗；
  只有「原本会被拦截或需要确认」的指令会进入询问流程。

---

## 三、每个执行命令的弹窗显示风险评估

所有执行确认/策略确认/PTY 确认的 `analysis` 都统一加上：

```
风险评估：<0–100>/100（低/中/高/极高）；<具体原因>
```

覆盖：

- `PolicyEngine.ask()`：所有策略 ASK（能力申请、评分过高、编码执行等）；
- `PolicyEngine.wrapCommandWhitelist()`：白名单 APP 的 BLOCK → ASK；
- `ProcessManager.riskPrefix()`：`CommandInterceptor` 的 Block/NeedConfirm；
- `ProcessManager.precheck`：白名单 precheck ASK；
- `ProcessManager.applyScriptFilePolicy`：脚本文件 ASK / 白名单高风险文件 ASK；
- PTY 能力申请；
- 弹窗 UI 原有「危险程度」和评分行保留，并显示上述风险说明。

---

## 四、真机实测（vivo V2133A / Android 14）

| 测试 | 结果 |
|---|---|
| 首页 → 白名单管理（同步外部白名单） | `command_whitelist.txt` 写入 5 个默认包名 |
| 白名单应用执行 `sh /sdcard/yl_test_a.sh`（内容 `echo youlong`） | **不再拦截**，弹「白名单脚本」确认框；显示「危险程度：极高」「风险评估：100/100（极高）」「行为链评分：100」 |
| 在上述弹窗点「允许一次」 | 命令正常执行（日志 `SPAWNED`） |
| 白名单应用执行安全脚本 `echo safe` | 弹「指令白名单放行确认」；显示「危险程度：低」「风险评估：0/100（低）」 |
| 未加入白名单的应用执行同类脚本 | 仍是原来的直接拦截（上一轮已验证） |

测试用的临时客户端 `com.youlong.testclient` 已卸载，测试脚本已删除，
临时模块已从工程移除；设备保留 9.2.9 正式包。

---

## 五、构建与产物

| 项 | 结果 |
|---|---|
| 命令 | `gradle.bat -p D:\anquan\anquan :app:assembleRelease --console=plain` |
| 结果 | ✅ **BUILD SUCCESSFUL in 4m 15s** |
| 产物 | `build_out/_app/outputs/apk/release/app-release-20261025-105333.apk`，**40,105,953 B** |
| 复制件 | `游龙安全护盾-9.2.9-release-20261025-whitelist.apk` |
| SHA-256 | `AE919D302D223F49F0E01C075015421EA308343B6BB399F9BF7BAFCEC9519A84` |
| 版本 / 签名 | `9.2.9 / 1234567954`；V3 证书 `d3d868c9…`（覆盖安装） |
| APK 内已确认 | `command_whitelist.txt`、`指令白名单放行确认`、`风险评估：`、`白名单脚本`、`脚本文件包含危险命令`；旧证书 `ca89a097…` 不存在 |
| 源码一致性 | ✅ 源码与 APK 一致 |

---

## 六、升级后必须做

安装后**重启一次内置特权服务**（管理器首页停止 → 启动，或重启手机）。
本轮改了服务端 `PolicyEngine`/`ProcessManager`/`CommandWhitelist` 和
`PolicyStore` 写入白名单，旧进程不重启不会生效。

---

## 七、已知限制 / 注意

1. **白名单同步依赖内置特权连接**：未连接时页面会尝试写入失败，
   下次打开白名单页/保存时会再试；管理器首页显示「内置特权服务已连接」后再操作最稳。
2. **只转换「原本会被拦截/需要确认」的指令**：本来规则就放行的只读命令不会每条都弹窗。
   如果希望白名单应用**所有命令**都弹窗，需要再改（成本是大量重复确认）。
3. 白名单应用的高危脚本弹窗仍需用户手动允许/拒绝；「始终允许」只对具体能力/指令生效，
   不会自动放行所有命令。
4. 外部白名单的「内置默认白名单」会一起同步进指令白名单；用户移除自定义应用时，
   外部页面保存会同步移除。
5. 上一轮审计出的 user service / rish / Shizuku / 策略目录可写等侧门本轮未处理。
