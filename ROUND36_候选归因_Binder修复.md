# 第 36 轮 —— 候选列表归因 + Binder 超时修复

2026-10-25。

## 一、Binder 连接超时修复

**现象**：内置特权显示 Binder 连接超时，`stellar_server` 仍在，App 主进程卡 `D`。

**原因**：自保 watchdog 每 2 秒无条件执行：

- `pm enable` 包/组件；
- `settings put secure ...`；
- 无障碍恢复。

高频 PackageManager/Settings 调用造成系统服务锁竞争，App 主进程被拖住，Binder 回调超时。

**修复**：

- 只有 `d=1`（包被禁）、组件被禁、无障碍缺失时才执行 `restore_self`；
- App 正常时循环只读状态，不再 `pm enable`；
- 单实例锁 + `STARTED` 防止多个 watchdog 同时跑；
- 实测 App 主进程恢复 `S`，`attachApplication` 正常，Binder 不再超时。

## 二、多 ADB 客户端归因（误伤控制）

之前规则：异常前 120 秒内任意第三方 App 连过 adbd → 直接冻结最后一个，多客户端时会指错。

**新规则**：

1. **主证据**：异常时 `/proc/net/tcp` / `/proc/net/tcp6` 里**仍活跃**的 adbd 客户端 UID；
2. 活跃连接为空时，用最近 **15 秒**的连接历史（不再用 120 秒）；
3. **候选列表**：
   - 唯一候选 → high，自动冻结；
   - 多个候选：
     - 其中一个命中黑名单 → 冻结黑名单；
     - 否则 `ambiguous`，**不自动冻结**，弹窗列出候选，让用户判断；
   - 系统设置/安装器在前台 → system，只恢复不冻结；
4. 冻结仍是可逆的 `force-stop + pm disable-user`，不会因多候选误卸载。

## 三、真机验证

| 测试 | 结果 |
|---|---|
| App 主进程 | `S`，`attachApplication` 正常，Binder 不再超时 ✅ |
| watchdog | 单 PID 21772，单实例 |
| 测试 App 连 adbd | `ACTIVE` 记录 `10291|com.redteam.lab` |
| 单候选 + `pm disable-user` 我们 | 高置信归因测试 App，冻结；我们自动恢复 ✅ |
| 设置界面操作（top=Settings） | system 分类，不冻结 ✅ |
| 无障碍被删 | 自动恢复 ✅ |
| 测试后测试 App | 已重新 enable，恢复正常 |

日志：

```text
/data/local/tmp/youlong_adb_clients.active
/data/local/tmp/youlong_adb_clients.hist
/data/local/tmp/youlong_selfdefense.log
/data/local/tmp/youlong_selfdefense_evidence.txt
```

## 四、APK

| 项 | 值 |
|---|---|
| APK | `app-release-20261025-171849.apk` → `游龙安全护盾-9.2.9-release-20261025-selfdefense2.apk` |
| 大小 | 40,168,556 B |
| SHA-256 | `26CC70A2857C474DCA05F4993176343142BBB9E12990232F2A0932952B6A793E` |
| 版本 | 9.2.9 / 1234567954 |
| 源码 | 与 APK 一致 |

## 五、仍有限制

- 多候选时无法唯一归因；这是无 root 下 adbd 不暴露“命令-客户端”映射的硬限制；
- Shizuku/Binder 不走 adbd TCP，socket 看不到；
- root/Device Owner 才能做精确 shell 审计；
- App 被整体卸载后脚本无法装回，DeviceAdmin 防卸载还未启用；
- 组件恢复受 shell 权限限制。
