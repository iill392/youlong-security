# 第 33 轮改动汇总（2026-10-25）—— ADB 防护（无线/TCP ADB 关闭 + 维护/恢复）

本轮按要求实现「病毒自带 ADB 客户端」的防护：不试图识别病毒，而是**关闭 ADB 入口**，
并保留维护/恢复通道，避免把用户自己也挡在门外。

---

## 一、实现内容

### 1. `AdbGuard`（管理器模块）

`embedded/manager/.../adb/AdbGuard.kt`

- 默认开启，状态存 `shield_prefs`：
  - `adb_guard_enabled`（默认 true）
  - `adb_guard_strict`（默认 false）
  - `adb_guard_maintenance_until`
- `enforce()` 每 5 秒执行：
  1. **无线调试开启** → `Settings.Global.putInt("adb_wifi_enabled", 0)` 关闭；
  2. **TCP ADB 端口 > 0**：
     - 实测 `setprop service.adb.tcp.port -1` 在 shell 下被 SELinux 拒绝；
     - 无 root 时改为 `Settings.Global.ADB_ENABLED=0`（停止 adbd），TCP 监听随之消失；
  3. **严格模式** → 关闭 USB 调试（`adb_enabled=0`）；
  4. **内置特权未连接** → 暂停防护，给用户恢复/配对的机会；
  5. **维护模式** → 暂停防护，时间到自动恢复。
- 提供 `Status` 给管理器页面（USB/无线/TCP/Stellar 连接/维护剩余）。

### 2. `AdbGuardService`（主进程常驻）

`app/.../AdbGuardService.java`

- 放在**主进程**而不是 `:guard`：Stellar Binder 只投递到主进程，`:guard` 里
  `pingBinder()` 为 false，防护会一直暂停。
- 前台服务（`specialUse`），通知栏「ADB 防护运行中」，每 5 秒调用 `AdbGuard.enforce`。
- 启动入口：
  - `MainActivity.onCreate`；
  - `ProtectService.onCreate`（`startForegroundService`）。

### 3. 管理器 UI

- 设置页新增「ADB 防护」入口（`AdbGuardActivity`）。
- 页面显示：
  - 内置特权连接状态、USB 调试、无线调试、TCP ADB 端口、防护/维护状态；
  - 「ADB 防护」开关；
  - 「严格模式」开关（连 USB 调试一起关，恢复需要手动重开）；
  - 「维护模式（10 分钟）」：暂停防护并打开 ADB，便于恢复/调试，到期自动关闭；
  - 「立即关闭无线/TCP ADB」；
  - 「打开首页重新激活内置特权」。
- 说明文字明确：内置特权断开时防护自动暂停，用户可在内置特权页用无线调试/配对码恢复，
  恢复后防护自动继续。

### 4. 恢复/维护流程

- **Stellar 断开**：`AdbGuard` 检测到 `Stellar.pingBinder()==false` 直接暂停，
  不会把 ADB 关死，用户可以：
  - 打开内置特权页，用已授权 key 重连；或
  - 打开无线调试/配对码，重新拉起 Stellar。
- **维护模式**：用户主动开启 10 分钟，期间不关闭 ADB；倒计时结束自动恢复防护。
- **默认保留 USB 调试**（App 无法通过 USB 连接 adbd），只关闭无线/TCP；
  严格模式才关闭 USB。
- 若 TCP ADB 端口存在（如 `adb tcpip` 残留），无 root 时通过
  `adb_enabled=0` 停 adbd，PC 会断开；需要时用维护模式重新打开。

---

## 二、真机验证（vivo V2133A / Android 14）

| 测试 | 结果 |
|---|---|
| 安装后启动首页，`AdbGuardService` | 前台服务运行（`dumpsys activity services` 可见，通知「ADB 防护运行中」） |
| 内置特权连接后，手动 `settings put global adb_wifi_enabled 1` | 5 秒内被防护改回 `0` ✅ |
| 当前 `service.adb.tcp.port` | 实测设备存在 `9242` 残留；无 root 下 `setprop` 被拒绝；已用 PC `adb usb` 清零 |
| Stellar 被杀掉后 | 首页显示「内置特权服务未启动」，防护按设计暂停（便于恢复） |
| 重新启动 Stellar 后 | 防护恢复，无线调试再次被关闭 ✅ |

> 说明：TCP ADB 的自动关闭会走 `adb_enabled=0`（停 adbd），会断开 PC 的 adb；
> 本次没有在真机上执行这一条（执行后当前 adb 会话会断），只验证了无线调试闭环。

---

## 三、构建与产物

| 项 | 结果 |
|---|---|
| 命令 | `gradle.bat -p D:\anquan\anquan :app:assembleRelease --console=plain` |
| 结果 | ✅ **BUILD SUCCESSFUL in 5m** |
| 产物 | `build_out/_app/outputs/apk/release/app-release-20261025-120338.apk`，**40,137,699 B** |
| 复制件 | `游龙安全护盾-9.2.9-release-20261025-adbguard.apk` |
| SHA-256 | `19898A4879317FB1100D34A5A754CD60B0257AB9DEA4532B134C9E3C90E92C4C` |
| 版本 / 签名 | `9.2.9 / 1234567954`；V3 证书 `d3d868c9…`（覆盖安装） |
| APK 内已确认 | `ADB 防护`、`adb_guard_channel`、`adb_guard_enabled`、`service.adb.tcp.port`、`ADB 防护运行中`、`关闭无线调试` |
| 源码一致性 | ✅ 源码与 APK 一致 |
| 设备状态 | 9.2.9 已安装；Stellar 运行；`adb_wifi_enabled=0`、`service.adb.tcp.port=0`、`adb_enabled=1` |

---

## 四、升级与使用

1. 安装 APK；
2. 打开一次首页，`AdbGuardService` 自动启动；
3. 管理器设置 → **ADB 防护**：
   - 默认保持「ADB 防护」开启；
   - 需要无线调试/重新激活时点「维护模式（10 分钟）」；
   - 内置特权断开时防护自动暂停，恢复后自动继续。
4. 严格模式会关闭 USB 调试，重启/恢复需要手动重新打开，非必要不建议常开。

---

## 五、已知限制

1. **无法按 App 区分 ADB key**：ADB 只认已授权 key，不认包名；本方案是关闭入口，
   不是识别病毒。用户如果亲手批准了病毒的 key，仍可能在 ADB 打开期间拿到 shell。
2. **无 root 时无法 `setprop service.adb.tcp.port -1`**：TCP 端口的关闭依赖
   `adb_enabled=0` 停 adbd，会断开 PC adb；这是当前设备的实测限制。
3. **自动拒绝「允许 USB 调试」弹窗**本轮未实现；目前依赖关闭无线/TCP 入口。
4. **Stellar 断开时防护暂停**：这是为了保留恢复能力，代价是断开期间 ADB 可能保持原状。
5. **root/Device Owner 的 UID 防火墙、`DISALLOW_DEBUGGING_FEATURES`** 未实现；
   有 root/Device Owner 时可进一步做「只允许我们 UID 连 adbd」。
6. 如果 App 被强制停止/未启动，`AdbGuardService` 不运行；ProtectService/首页启动会拉起。
