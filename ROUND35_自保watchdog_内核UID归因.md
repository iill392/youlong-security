# 第 35 轮 —— 自保 watchdog + 异常冻结检测（内核 socket UID 归因）

2026-10-25。目标：不依赖恶意软件自己公开日志，检测“谁冻结/强停/卸载了我们”，
能恢复自己、冻结高置信攻击者并弹窗。

---

## 一、检测机制（不靠日志）

### 1. 主证据：`/proc/net/tcp` 的 socket UID

任何 App 想通过“自带无线调试内核/ADB 客户端”执行 shell，都必须连接系统 adbd 的 TCP 端口
（当前 `8056`）。内核在 `/proc/net/tcp`、`/proc/net/tcp6` 里记录这条连接的 **UID**：

- 不依赖恶意 App 公开日志；
- 不依赖它配合；
- App 自己改不了；
- 用 UID 反查包名（`pm list packages -U`）。

watchdog 每 2 秒扫描，记录 `时间|UID|包名` 历史到
`/data/local/tmp/youlong_adb_clients.hist`。

### 2. 异常状态检测

每 2 秒检查：

- 包是否被 `pm disable-user`（`pm list packages -d`）；
- 组件是否被禁用（`dumpsys package` 的 `disabledComponents=`）；
- 无障碍服务是否被挤出 `enabled_accessibility_services`；
- 进程是否被 force-stop（`logcat -b events` 的 `am_force_stop`/`am_kill` 仅作辅助）。

### 3. 证据归因

异常前 120 秒内：

- 设置/安装器在前台 → **系统/用户操作**，只恢复，不冻结、不弹窗；
- 内核 socket 历史里有第三方 App 连过 adbd → **高置信独立 ADB 客户端**；
- 前台/Shizuku 日志命中黑名单 → 高置信；
- 前台第三方 App 与 Shizuku 日志时间吻合 → 中置信，只弹窗；
- 无证据 → 只弹窗提示，不冻结。

### 4. 响应

- 先恢复自己：`pm enable` 包、组件、无障碍；
- 高置信：`am force-stop` + `pm disable-user` 冻结攻击者；
- 中置信/未知：只弹窗，不冻结；
- 已知黑名单包（`mio.TerminalDeity.Ultra` 等）：force-stop + uninstall；
- 通过 `am start MainActivity --es self_defense_*` 拉起自保弹窗，显示嫌疑包/置信度/依据。

---

## 二、实现

- `app/src/main/assets/self_defense.sh` / `res/raw/self_defense_script`：watchdog 脚本；
- `SelfDefense.java`：
  - 从 raw 复制脚本到外部目录（world-readable）；
  - 通过 `Stellar.newProcess(["sh","-c","nohup setsid sh 脚本 &"])` 启动，**独立会话**；
  - 避免成为 App 的受管子进程；App 被 disable 后脚本仍存活；
- `AdbGuardService` 启动时/每 30 秒 ensure；
- `MainActivity`/`onNewIntent` 显示自保弹窗；
- `mkdir` 原子锁 + `STARTED` 防止重复 watchdog；
- 状态文件记录**恢复后**状态，避免下一次漏检。

---

## 三、真机实测（vivo V2133A / Android 14）

| 测试 | 结果 |
|---|---|
| watchdog 启动 | PID 9191，单实例；独立 sh 进程 |
| App 被 disable 后 watchdog | 存活，未随 App 被杀 |
| 测试 App 连 adbd 8056 | 历史记录 `10291|com.redteam.lab` |
| `pm disable-user com.youlong.hd` | 检测到，包自动恢复 ✅ |
| 内核 socket 归因 | `attacker=com.redteam.lab conf=high` ✅ |
| 冻结攻击者 | `com.redteam.lab` 被 `pm disable-user` 冻结 ✅ |
| 弹窗 | 显示“自保检测 / 嫌疑应用 / 置信度 / 依据” ✅ |
| 设置界面操作（top=Settings） | 判定 system，恢复我们；测试 App 未被冻结 ✅ |
| 无障碍被删 | 脚本恢复 `enabled_accessibility_services` ✅ |
| 组件禁用 | 本机有限制：shell 不能改本 App 组件状态，未能真机验证；脚本保留恢复逻辑 |

日志文件：

```text
/data/local/tmp/youlong_selfdefense.log
/data/local/tmp/youlong_adb_clients.hist
/data/local/tmp/youlong_selfdefense_evidence.txt
/data/local/tmp/youlong_selfdefense.state
```

---

## 四、APK

| 项 | 值 |
|---|---|
| APK | `app-release-20261025-165453.apk` → `游龙安全护盾-9.2.9-release-20261025-selfdefense.apk` |
| 大小 | 40,163,917 B |
| SHA-256 | `27F0D00BD904E6E4D1ED7A12AED9B3AC39734705740340B41695726F2192EC65` |
| 版本/签名 | 9.2.9 / 1234567954；V3 `d3d868c9…` |
| 源码 | 与 APK 一致 |

---

## 五、限制

1. **Shizuku/Binder** 不走 adbd TCP，内核 socket 看不到；日志/前台仅辅助，不能保证归因。
   后续需检测/冻结 Shizuku 服务。
2. 攻击者若通过 **root** 或另一个系统 UID 代理，socket 只能看到代理，无法归因到主 App。
3. 2 秒轮询可能漏掉极短连接；可缩短到 0.5 秒，但要权衡耗电。
4. **App 被整体卸载**后脚本无法装回；需要启用现有 DeviceAdmin 防卸载（下一步）。
5. 组件恢复在本机受 shell 权限限制，部分组件可能无法由脚本重新 enable。
6. 自动卸载只限黑名单精确包名，不做 Shizuku/Termux 泛化卸载。

---

## 六、下一步

1. 启用 DeviceAdmin，阻止卸载；
2. Shizuku 服务检测/击杀；
3. 缩短 socket 轮询/记录最近连接；
4. root/Device Owner 的系统级 shell 审计。
