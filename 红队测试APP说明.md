# 红队测试 App 说明（独立 ADB + Stellar）

> 仅用于测试自己的设备。这个 App 自带 ADB key 和 ADB 协议实现，**不依赖游龙内置特权/Shizuku**；
> 另一部分作为普通 Stellar 客户端，用来测试游龙的脚本拦截。
> APK：`红队测试-独立ADB-Stellar-1.0.apk`

## 一、两种模式

### A. 独立 ADB 模式（自带 key，不走 Stellar）

- 自带 RSA 2048 key，按 ADB 协议直接连 `127.0.0.1:<service.adb.tcp.port>`；
- 首次连接手机会弹出「允许 USB 调试吗？」，只授权这个测试 App；
- 连接成功后以 `shell` 身份执行命令（`uid=2000(shell)`）；
- 支持从 `getprop`/常见端口自动找 legacy TCP ADB（当前设备是 `8056`）；
- 不支持 Android 11+ 无线调试的 TLS/配对端口（那种端口会返回 `A_STLS`，测试工具会提示）。

### B. Stellar 模式（普通客户端）

- 声明 `StellarProvider`，走游龙内置特权 Binder；
- 调用 `newProcess` 执行构造好的脚本，验证游龙的脚本内容拦截；
- 首次授权后可能因 `allowedMap` 未刷新报 `Permission Denial: ... requires permission stellar`，
  这是测试 App 进程的问题：**重启一次测试 App 再点第 4 项**（或点第 6 项）。

## 二、界面按钮

1. **连接独立 ADB（自带 key）**
2. **ADB 只读探测 + 绕过演示**
   - `id`、`getprop service.adb.tcp.port`、`settings get global adb_wifi_enabled/adb_enabled`、
     `pm path com.youlong.hd`、`ls -l /data/local/tmp/stellar_guard` 等；
   - `echo youlong`、`echo "pm ..."`（都是安全文本，不执行真正的卸载/冻结/删除）。
3. **ADB 防护测试**
   - 通过独立 ADB 执行 `settings put global adb_wifi_enabled 1`；
   - 等 8 秒读回 `adb_wifi_enabled`，看游龙 ADB 防护是否自动关回 0。
4. **Stellar 脚本拦截测试**
   - 写 3 个脚本到测试 App 外部目录：
     - `youlong.sh`：`echo youlong`
     - `danger_dry.sh`：`echo "pm disable-user --user 0 -a"` 等危险文本（只 echo）
     - `encrypted.bin`：随机二进制
   - 分别用 `sh`、`env sh`、`sh -c 'sh 文件'`、`sh -c '. 文件'` 调 `newProcess`；
   - 显示每个用例的 BLOCK/ERROR 和原因。
5. **保存/复制报告**
6. **重启测试 App（首次 Stellar 授权后）**

## 三、真机实测结果（vivo V2133A / Android 14）

### 独立 ADB

| 项 | 结果 |
|---|---|
| 连接 `127.0.0.1:8056` | 首次系统授权后成功 |
| `id` | `uid=2000(shell) gid=2000(shell)...` |
| `service.adb.tcp.port` | `8056` |
| `adb_wifi_enabled` / `adb_enabled` | `0` / `1` |
| `pm path com.youlong.hd` | 能读取 |
| `ls /data/local/tmp/stellar_guard` | 能读取策略目录 |
| `echo youlong`（独立 ADB） | 正常执行，**游龙 Stellar 策略看不到** |
| 危险文本 `echo "pm disable-user ..."` | 正常执行（只 echo，不真操作） |

**结论**：测试 App 不依赖 Stellar 就能拿到 shell；游龙的脚本策略只覆盖 Stellar `newProcess`，
独立 ADB 通道不在其内。这正是本轮 ADB 防护要关闭的入口。

### Stellar 模式（重启一次测试 App 后）

| 用例 | 结果 |
|---|---|
| `sh youlong.sh` | BLOCK：脚本包含危险命令：含 youlong 关键字 |
| `sh danger_dry.sh` | BLOCK：批量冻结应用 |
| `sh encrypted.bin` | BLOCK：脚本文件疑似加密/二进制，无法审计 |
| `env sh youlong.sh` | BLOCK：含 youlong 关键字 |
| `sh -c 'sh youlong.sh'` | BLOCK：含 youlong 关键字 |
| `sh -c '. youlong.sh'` | BLOCK（同上） |

**结论**：游龙对 Stellar 通道的脚本文件内容审查、加密识别、危险命令识别有效。

### ADB 防护

- 手动/测试 App 用独立 ADB 执行 `settings put global adb_wifi_enabled 1`；
- 5 秒内游龙 `AdbGuardService` 把 `adb_wifi_enabled` 改回 `0`（真机已验证）；
- 当前设备 `service.adb.tcp.port=8056` 默认保留（避免把启动/恢复通道掐断），
  管理器「ADB 防护」页可手动关闭 USB/TCP ADB。

## 四、安装/使用

1. 安装 `红队测试-独立ADB-Stellar-1.0.apk`（debug 签名，仅测试用）；
2. 打开测试 App，点第 1 项，手机弹「允许 USB 调试」时点「一律允许 / 允许」；
3. 点第 2 项看独立 ADB 探测/绕过结果；
4. 点第 3 项看 ADB 防护；
5. 点第 4 项测试 Stellar；如报 `requires permission stellar`，重启测试 App 再点一次；
6. 点第 5 项保存/复制报告到：
   `/storage/emulated/0/Android/data/com.redteam.lab/files/redteam_report.txt`

## 五、限制

1. 仅支持 **legacy TCP ADB**（`adb tcpip` / `service.adb.tcp.port`）；
   不支持 Android 11+ 无线调试的 TLS+配对端口。
2. 首次 ADB 授权必须由用户在系统弹窗点允许；测试 App 不能绕过系统授权。
3. 首次 Stellar 授权后需要重启测试 App 一次（服务端 allowedMap 刷新问题）。
4. 测试脚本里的危险命令全部是 `echo` 文本/随机数据，不会真的卸载、冻结或删除。
5. 仅供自己的设备做安全测试，不要分发到他人设备。
