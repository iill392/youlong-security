# 第 37 轮 —— 关闭 TCP ADB 8056（PC USB 助手）

2026-10-25。

## 一、结论

无 root、纯手机端**无法**在不杀 Stellar 的前提下清掉 `service.adb.tcp.port`：

- `setprop service.adb.tcp.port -1` 被 SELinux 拒绝；
- `adb tcpip 0` 端口非法；
- `adb_enabled=0` 停 adbd 会把 shell 启动的 Stellar 一起杀掉；
- `adb usb` 能清 TCP（8056→0），但 adbd 重启也会把 Stellar 带走。

**可行方案（PC）**：

1. 电脑执行 `adb usb`：TCP 变 0，adbd 重启，旧 Stellar 退出；
2. 不依赖 TCP ADB，通过 **USB adb** 重新启动 Stellar（`nohup setsid`）；
3. 验证 `service.adb.tcp.port=0` 且 `stellar_server` 有 PID；
4. 这样 TCP 入口关闭，Stellar 恢复，独立 ADB App 连不上 8056。

## 二、助手

文件：

```text
tools/close_tcp8056_restart_stellar.bat
tools/close_tcp8056_restart_stellar.ps1
```

双击 `.bat` 即可（需要电脑已装 adb，手机已连接并授权 USB 调试）。

流程：

```text
adb usb                     -> TCP 0，adbd 重启
settings adb_wifi_enabled=0
pm enable + 启动游龙
解析 APK/lib 路径
pkill stellar_server
nohup setsid libstellar.so --apk=... >/dev/null 2>&1 &
验证 TCP=0 / stellar_server PID
```

## 三、真机验证

| 项 | 结果 |
|---|---|
| `adb usb` 后 TCP | 8056 → **0** |
| 旧 Stellar | 被 adbd 重启带走 |
| USB 重启 Stellar | PID 5479 在线 ✅ |
| 游龙 App | 32207 在线，Binder attach 正常 |
| 自保 watchdog | PID 4609 启动 ✅ |
| 无线调试 | 0 |
| 独立 ADB 测试 App | 找不到 8056，连接失败 ✅ |
| TCP 0 后 Binder/内置特权 | 正常 ✅ |

日志：

```text
/data/local/tmp/youlong_selfdefense.log
/data/local/tmp/youlong_selfdefense.pid
```

## 四、当前设备状态

```text
service.adb.tcp.port = 0
adb_enabled = 1
adb_wifi_enabled = 0
stellar_server = 5479
com.youlong.hd = 32207
watchdog = 4609
```

独立 ADB 客户端（TCP 8056）已经连不上了。

## 五、限制

1. **需要电脑**，不能纯手机完成；
2. 每次**重启手机/重启 adbd**后要再运行一次；
3. 不要再用 App 的“无线调试/TCP 启动”，它会重新 `tcpip` 打开端口；
4. 只能清 **legacy TCP ADB**；**无线调试 TLS 端口**仍可能被打开（本助手会关 `adb_wifi_enabled=0`）；
5. Shizuku/Binder、root 不受此影响，仍走各自通道。

## 六、APK

| 项 | 值 |
|---|---|
| APK | `app-release-20261025-191008.apk` → `游龙安全护盾-9.2.9-release-20261025-selfdefense3.apk` |
| 大小 | 40,167,329 B |
| SHA-256 | `DD4531A8F5FD40183C3D166D16C1F3037CB3C281E73FB67249BD6A1E82B2ED5E` |
| 说明 | 无 root 下唯一可用的 TCP 关闭路径：PC USB 助手 |
