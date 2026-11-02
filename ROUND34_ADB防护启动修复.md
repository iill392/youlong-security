# 第 34 轮修正（2026-10-25）—— ADB 防护导致「启动后自己断」的修复

> 这是对第 33 轮 ADB 防护的问题修正。第 33 轮产物 `-adbguard.apk` 已被本次修复包覆盖。

## 一、问题原因

用户反馈「内置特权刚启动就自己断」。定位到两个问题：

1. **防护把启动通道掐了**
   - `AdbGuard.enforce` 检测到 `service.adb.tcp.port > 0` 时直接
     `Settings.Global.ADB_ENABLED=0`；
   - 内置特权启动过程正好使用 TCP ADB（`tcpip`），防护在启动中途停掉 adbd，
     启动/连接随之中断。

2. **防护循环阻塞主进程**
   - `AdbGuardService` 的循环跑在主线程，内部做 `pingBinder / Settings / getprop`；
   - App 主进程被卡成 `D`（不可中断），Stellar Binder 投递失败，
     界面显示「内置特权服务未启动」，安装/启动也被拖住。

## 二、修复

- `AdbGuardService` 改用 **HandlerThread 后台线程** 执行防护循环，不再阻塞主线程；
- **默认不再自动停 adbd**：
  - 只关闭无线调试（`adb_wifi_enabled=0`）；
  - TCP ADB 端口只在「ADB 防护」页由用户**手动**点「关闭 USB/TCP ADB（会断开电脑）」；
- 保留严格模式（用户主动开启才关闭 USB 调试）；
- 维护模式、Stellar 断开暂停、恢复后继续等逻辑不变。

## 三、真机验证（vivo V2133A / Android 14）

| 测试 | 结果 |
|---|---|
| 重启手机后解锁，覆盖安装修复包 | 成功 |
| 首页 → 内置特权 → 启动服务进程 | `stellar_server` 启动（PID 18530） |
| 启动后等待 30 秒以上 | 服务保持在线，未再自己断 ✅ |
| 首页状态 | 「内置特权服务已连接 — 高级拦截模式可用」✅ |
| App 主进程 | `S` 状态，不再卡 `D` ✅ |
| 内置特权连接后手动 `adb_wifi_enabled=1` | 5 秒内被改回 0 ✅ |
| 当前端口 | `tcp=8056`（默认不自动关，保留启动/恢复通道） |

## 四、构建与产物

| 项 | 结果 |
|---|---|
| 命令 | `gradle.bat -p D:\anquan\anquan :app:assembleRelease --console=plain` |
| 结果 | ✅ **BUILD SUCCESSFUL in 4m 8s** |
| 产物 | `build_out/_app/outputs/apk/release/app-release-20261025-121920.apk`，**40,142,700 B** |
| 复制件 | `游龙安全护盾-9.2.9-release-20261025-adbguard.apk` |
| SHA-256 | `5855D1F9C1B70A1DB9AEF30CD7E55C942219BAA7AF4ECD1B713686813FD17EBB` |
| 版本 / 签名 | `9.2.9 / 1234567954`；V3 证书 `d3d868c9…` |
| APK 内已确认 | `ADB 防护`、`adb_guard_channel`、`adb_guard_enabled`、`ADB 防护运行中`、`关闭 USB/TCP ADB`、`AdbGuardWorker` |
| 源码一致性 | ✅ 源码与 APK 一致 |
| 设备状态 | 修复包已安装；Stellar 在线；首页显示已连接；`adb_wifi_enabled=0`、`adb_enabled=1`、`service.adb.tcp.port=8056` |

## 五、使用说明

- 默认防护只关无线调试，**不会**再动 TCP ADB / USB 调试，所以启动、恢复不会再断；
- 如果确认不再需要电脑 ADB，可在 **管理器 → ADB 防护 → 关闭 USB/TCP ADB（会断开电脑）**；
- 需要恢复/重新激活时：
  - 用「维护模式（10 分钟）」临时打开 ADB；或
  - 内置特权断开时防护自动暂停，直接走内置特权页的无线调试/配对码恢复；
- 严格模式会关闭 USB 调试，恢复需要手动重开。

## 六、仍存在的限制

1. 无 root 时不能 `setprop service.adb.tcp.port -1`；为了不破坏启动/恢复，默认保留 TCP ADB，
   只在用户手动操作时关闭。
2. 仍无法按 App 区分 ADB key；没有实现自动拒绝未知 ADB 授权弹窗。
3. root/Device Owner 的 UID 防火墙未实现。
4. 如果 App 被强制停止，防护服务不运行。
