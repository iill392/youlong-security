# 第 19 轮改动汇总（2026-10-17，含第 20 轮命令弹窗补充）

本轮修 3 组问题：日常模式只能触发一次、白名单仍会触发紧急逃生、内置特权的命令/权限规则。

---

## 一、日常模式「触发一次后再也触发不了」

**原因**：`showDailyOverlay()` 只要 `dailyOverlayShowing == true` 就直接忽略新触发；
一旦覆盖层因为系统回收、切后台、卸载流程摘挂等原因「看不见了但标记还在」，
日常模式就会永久失效，只能重开应用。

**修复**（`ProtectService.java`）：

- 新增 `dailyOverlayLastShownAt` 记录覆盖层最近一次成功显示时间；
- 旧覆盖层已脱离窗口 → 复位标记后重新弹出（原有逻辑保留）；
- 旧覆盖层还挂着、但已超过 1.5 秒去抖窗口 → **关闭旧覆盖层并重新走一遍流程**，
  不再永久忽略；
- 1.5 秒内的重复触发（音量/摇动/多层回调）仍然去重，避免连续弹两次；
- `closeDailyOverlay()` 会把时间戳清零，用户关闭后立刻就能再次触发。

---

## 二、白名单相关问题

### 1. 白名单选择列表不再显示系统应用

`WhitelistActivity.loadApps()` 增加过滤：

```java
if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
        && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
    continue;
}
```

纯系统应用（设置、系统界面、包安装器等）不再出现在「加入白名单」列表里；
系统应用的更新包（如 Chrome）仍按用户应用保留。

### 2. 白名单应用内不再触发紧急逃生

`ProtectService.triggerVolumeRescue()` 把**白名单判断提到日常模式分支之前**：

- 前台应用在白名单中 → 直接跳过，不再弹救援覆盖层；
- Toast 提示「当前应用在白名单中，紧急逃生已跳过」；
- 不再区分拦截模式（日常/超级/终结都遵守）；
- 摇一摇的 `SHAKE_TRIGGER` 分支同样加上白名单判断。

日常模式的批量 force-stop / 卸载列表本来就会通过 `isProtectedFinalPkg()`
排除白名单应用，这次只是把「触发入口」也一起堵上。

---

## 三、内置特权：命令拦截 / 权限 / 弹窗图标

### 1. 恶意命令自动阻止（`CommandInterceptor.kt`）

对已授权应用（管理器自身不受限）：

- 命令中**包含 `youlong`**（不区分大小写）→ 直接 `Block`，不执行；
- 命令**疑似加密/编码**→ 直接 `Block`。识别范围：
  - `base64/base32/base16 -d/--decode`
  - `openssl enc/aes/des/rsautl`、`gpg --decrypt`、`ccrypt -d`
  - `xxd -r`
  - `\xNN\xNN...` 十六进制转义串
  - `eval` + 命令替换
  - 超长 Base64 块（≥48 字符）或 64 位以上十六进制块
- 阻止时照旧弹「游龙安全护盾已阻止此威胁」告警框（顶部为本应用图标），
  并向调用方抛 `SecurityException`。

### 2. 每条命令都要用户确认（`com.youlong.tool` 除外）

`CommandInterceptor.inspect()` 规则重排：

1. 管理器自身 → 放行；
2. `youlong` / 加密命令 → 直接阻止；
3. `com.youlong.tool` → 放行，不弹窗；
4. **其它所有已授权应用 → 每条命令都 `NeedConfirm`**，弹窗询问是否执行。

### 2.1 危险程度分析（2026-10 第 20 轮补充）

命令进入确认框之前先做危险程度分析，分级写入弹窗：

| 等级 | 典型命令 | 弹窗说明 |
| --- | --- | --- |
| 极高 | 删系统数据、`reboot`、`mkfs`、`dd of=/dev/block`、`settings put secure/global`、动系统关键组件、批量卸载多个应用 | 可能导致系统不可用 / 批量数据丢失 |
| 高 | 卸载、停用、冻结、清数据、`am force-stop`、设备管理员/策略、停止所有应用 | 相关数据可能无法恢复 |
| 中 | 安装应用、`pm grant/revoke`、`input/sendevent`、`content` 写入、`curl/wget`、普通文件写 | 请确认来源可信 |
| 低 | `pm list`、`dumpsys`、`getprop`、`id` 等只读查询 | 一般不会修改系统 |

确认弹窗顶部显示「危险程度：高/中/低」+ 具体分析；拦截弹窗也显示「极高」与分析。

### 2.2 拦截命令一定有弹窗

- `CommandGuard.showBlockedAlert()` 现在会**等待用户看到并点掉弹窗**（最多 8 秒）后再返回，
  保证拦截时弹窗一定有机会展示；
- 去掉了 `FLAG_ACTIVITY_NEW_DOCUMENT`，只用 `NEW_TASK`，避免弹窗被放到调用方任务后面；
- 弹窗标题统一为「游龙安全护盾已阻止此威胁」，并显示本应用图标、命令内容和危险程度。

### 2.3 答复文件写入修复

握手目录是 shell 创建的 `0711` 目录，普通 app UID **没有创建文件的权限**。
之前 `CommandGuardActivity.reply()` 直接写 `.resp` 会 `EACCES`，用户点「允许」也传不回服务端，
只能等超时拒绝。现在：

- 服务端 `writeRequest()` 预先创建 `$id.resp` 并 `chmod 0666`；
- 管理器只需写这个已存在的文件（仍然不能列目录或伪造其它请求的答复）；
- 管理器侧保留一条兜底：直接写失败时用内置特权 shell 回写。

### 3. 其它软件请求 Shizuku/Stellar 权限一律自动授权

- `PermissionRequester.requestPermission()`：非管理器（`com.youlong.hd`）的请求
  → 直接 `FLAG_GRANTED` + 回传 `allowed=true`，不再弹确认框；
- `ShizukuCallbackFactory` 的 Shizuku 兼容层请求同样自动授权并回传成功。

即：除管理器自身外，其它软件（含游龙工具）需要 Shizuku 权限时直接通过。

### 4. 弹窗顶部固定为本应用图标

- `CommandGuardActivity`：从「固定护盾图标」改为读取**调用方应用图标**
  （`PackageManager.getApplicationIcon(packageName)`），告警框、确认框都用它；
  读取失败才退回盾牌占位。
- `RequestPermissionActivity`：权限弹窗顶部改为申请权限应用的图标
  （`ApplicationInfo.loadIcon()` → `ImageBitmap`），读不到时退回 Security 图标。

---

## 四、构建与验证

- `:server:compileReleaseKotlin` / `:manager:compileReleaseKotlin` / `:app:assembleRelease` 全部通过。
- 最终 APK：[游龙安全护盾-9.2.1-release-20261017.apk](<E:/11/anquan/anquan/游龙安全护盾-9.2.1-release-20261017.apk>)
  - `versionCode 1234567944（9.2.1）`
  - SHA-256：`AAB03DD63CD524B41C87E00285C1590A6FE8072F137D31538F008B783E343F98`
- 已在 DEX 中确认新逻辑字符串存在（含 youlong / 加密 / 自动授权 / 停止所有应用等）。
- 设备不在线，未做真机验证；命令确认弹窗、自动授权需要装机后由授权应用触发确认。
