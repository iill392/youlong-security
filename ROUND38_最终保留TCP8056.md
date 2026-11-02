# 第 38 轮 —— 最终方案：保留 TCP 8056（不用 PC 助手）

2026-10-25。

## 一、决定

- **接受 TCP 8056 保持开放**；
- **不再使用 PC 助手**；
- 保留 Stellar / 自保 watchdog / 病毒库等能力；
- 去掉 App 中会关 TCP/USB、可能连带终止 Stellar 的按钮和严格模式。

## 二、代码/文件改动

1. 删除 `tools/close_tcp8056_restart_stellar.*`（PC 助手）；
2. `AdbGuardActivity`：
   - 删除「严格模式」开关；
   - 删除「关闭 USB/TCP ADB」按钮；
   - 文案改为：默认只关无线调试，TCP 8056 保留（已选择开放），不会自动/手动关 adbd；
3. `AdbGuard.enforce`：
   - 移除严格模式关闭 USB 的逻辑；
   - 只保留无线调试关闭；
   - 不再触碰 `adb_enabled` / TCP 端口；
4. 当前 ADB 防护不会杀 Stellar。

## 三、当前手机状态

```text
service.adb.tcp.port = 8056
adb_enabled = 1
adb_wifi_enabled = 0
stellar_server = 15509
com.youlong.hd = 28176
watchdog = 15610
Binder attach = 正常
```

## 四、这个方案的边界

**保留：**
- Stellar 命令/脚本拦截；
- 病毒库/卸载提醒；
- 自保 watchdog：被冻结/强停/无障碍被挤掉时恢复；
- 内核 socket UID 归因：独立 ADB App 攻击我们时，单候选可冻结，多候选只提示；
- 无线调试仍会被关（ADB 防护开启时）。

**放弃：**
- 不再阻止独立 ADB 软件连接 8056 执行 shell；
- 不再自动/手动关闭 TCP/USB ADB；
- 不再需要 PC 助手，重启后可用 TCP 启动 Stellar；
- 不再有“TCP=0 + Stellar在线”的手机端状态。

## 五、APK

| 项 | 值 |
|---|---|
| APK | `app-release-20261025-202259.apk` → `游龙安全护盾-9.2.9-release-20261025-final-opentcp.apk` |
| 大小 | 40,159,135 B |
| SHA-256 | `DE40CB17DE1D1822B581B3BFEC2EE040AA0E0F17576695B065E15207A4CD6DF1` |
| 版本 | 9.2.9 / 1234567954 |
| 源码 | 与 APK 一致 |

## 六、后续如果改主意

- 想彻底关 TCP：只能 PC `adb usb` + 助手，或 root/Device Owner；
- 想防 Shizuku/root：需要 root/Device Owner 或系统级 shell 审计；
- 想防卸载：启用 DeviceAdmin（当前组件已在，需用户激活）。
