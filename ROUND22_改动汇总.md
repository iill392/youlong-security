# 第 22 轮改动汇总（2026-10-18）

本轮：新增「指令拦截白名单」。

---

## 一、功能规则

管理器「设置 → 指令拦截白名单」里可以按应用打开/关闭白名单。

对**已授权应用**执行 shell 指令时：

| 应用状态 | 含 `youlong` 的指令 | 疑似加密/编码指令 | 其他指令 |
| --- | --- | --- | --- |
| 普通已授权应用 | 直接拦截 | 直接拦截 | 弹窗询问 + 危险程度分析 |
| 指令白名单应用 | **仍然直接拦截** | **不拦截，改为弹窗询问** | 弹窗询问 |
| `com.youlong.tool` | 直接拦截 | 直接拦截（不在白名单 UI 内） | 直接放行，不询问 |
| 管理器自身 | 放行 | 放行 | 放行 |

白名单应用的确认弹窗改为：

- 标题：`指令白名单放行确认`
- 正文：`白名单应用「应用名」要执行指令，是否放行？`
- 仍保留危险程度分析（极高/高/中/低）与完整命令预览；
- 如果是疑似加密命令，会额外提示「白名单已跳过自动阻止，但仍需你确认」。

## 二、实现位置

- 管理器 UI：
  - [CommandWhitelistActivity.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/authorization/CommandWhitelistActivity.kt>)
    —— 第三方应用列表 + 开关，白色主题；
  - [SettingsScreen.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/ui/features/settings/SettingsScreen.kt>)
    —— 设置页新增「指令拦截白名单」入口；
  - manifest 声明 `CommandWhitelistActivity`。
- 名单同步：
  - 管理器把包名写入 `/data/local/tmp/stellar_guard/command_whitelist.txt`（通过内置特权 shell）；
  - 服务端 [CommandWhitelist.kt](<E:/11/anquan/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandWhitelist.kt>)
    读取并缓存 1 秒。
- 判定：
  - [CommandInterceptor.kt](<E:/11/anquan/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandInterceptor.kt>)
    增加 `whitelisted` 标记；加密命令只在非白名单时自动阻止；
  - [CommandGuard.kt](<E:/11/anquan/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandGuard.kt>)
    把白名单标记通过请求文件/Intent 传给弹窗；
  - [CommandGuardActivity.kt](<E:/11/anquan/anquan/embedded/manager/src/main/kotlin/roro/stellar/manager/authorization/CommandGuardActivity.kt>)
    白名单应用显示专用确认文案。

## 三、版本与产物

- `versionName`：**9.2.3**
- `versionCode`：**1234567946**
- APK：[游龙安全护盾-9.2.3-release-20261017.apk](<E:/11/anquan/anquan/游龙安全护盾-9.2.3-release-20261017.apk>)
- SHA-256：`9F5203D0E9B3684F5F4AC9F16C6DF10469F9527C76DD43329E89F0ED5E845C36`

编译通过；HTML/管理器未改动的页面保持上一版。设备仍不在线，未做真机白名单同步与弹窗文案验证。
