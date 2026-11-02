# 第 39 轮 —— ADB 授权弹窗改为“只提示，不自动取消”

2026-10-25。

## 一、改动

`AdSkipService.handleAdbAuthDialog`：

- **删除**自动查找「取消/Cancel」并 `ACTION_CLICK` 的逻辑；
- 仅当：
  - ADB 防护开启；
  - 非维护模式；
  - Stellar 已连接；
  - 检测到 `UsbDebuggingActivity` 的「允许 USB 调试」弹窗；
- 显示 Toast：**「检测到 ADB 授权请求，请确认来源后再允许」**；
- 5 秒内不重复提示；
- **不自动点允许/取消，由用户决定**。

`AdbGuardActivity` 文案同步：ADB 授权弹窗只提示、不自动取消，避免误伤自己的电脑/产品 key。

## 二、为什么改

原来的无差别自动取消会误伤：

- 我们自己 PC 的 adb 授权；
- 产品自己的 ADB key 重新授权；
- 维护模式结束后的正常授权；
- 正常开发工具/用户主动授权。

既然已经选择保留 TCP 8056、不靠无障碍硬拦独立 ADB，这里改成只提示更合理。

## 三、当前手机状态

```text
service.adb.tcp.port = 8056
adb_enabled = 1
adb_wifi_enabled = 0
stellar_server = 15509
com.youlong.hd = 23665
watchdog = 15610
Binder attach = 正常
```

## 四、效果

- 出现 ADB 授权弹窗时：**只弹 Toast，不自动点取消**；
- 用户自己授权 PC/产品 key 时不会被无障碍挡；
- 恶意 App 的授权请求也不会被自动取消，需要用户自己判断；
- 保留无线调试关闭、自保 watchdog、Stellar 策略。

## 五、APK

| 项 | 值 |
|---|---|
| APK | `app-release-20261025-203351.apk` → `游龙安全护盾-9.2.9-release-20261025-authprompt.apk` |
| 大小 | 40,165,807 B |
| SHA-256 | `4D7B051F1325C9CB8FF21F7B39038E3FA89395798E02176A9CE5F85A0AE7CC78` |
| 版本 | 9.2.9 / 1234567954 |
| 源码 | 与 APK 一致 |

## 六、限制

- 现在**不会自动阻止**恶意 App 的 ADB 授权，用户点允许后独立 ADB 可以连；
- 想自动拒绝只能 root/Device Owner 或做按 key 指纹白名单；
- Toast 依赖无障碍服务开启。
