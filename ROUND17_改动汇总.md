# 第 17 轮改动汇总（2026-10-17）

两项需求：① 不开「实时守护」但开启「自动拦截」时，自动拦截也要生效；② 排查并修复开启守护后调音量延迟、按一下跳一大截的问题。

---

## ① 自动拦截独立于实时守护

### 行为

- `auto_block_on`（自动拦截开关，默认开启）现在是**独立的运行理由**：
  - 应用启动 / 开机 / 保活闹钟触发时，只要自动拦截开着，就拉起 `ProtectService`；
  - 双进程哨兵 `ForegroundService(:guard)`、保活闹钟、开机广播、服务自愈判断都把它算进去；
  - 通知栏会如实显示「**自动拦截运行中 · 未开启实时守护**」，不再假装「守护中」。
- 关闭「实时守护」但自动拦截开着时：
  - 不再停止服务 / 不取消保活闹钟（自动拦截继续跑）；
  - 只关闭实时守护专属能力：黑名单拦截、摇一摇/音量键逃生、权限降级巡检等；
  - 通知栏切换为「自动拦截中」；页面 toast 提示「实时守护已关闭，自动拦截仍在运行」。
- 关闭自动拦截且其他开关全关时：才真正停止主服务 + 哨兵 + 保活闹钟。

### 关键改动

- `ProtectService`：`onCreate` / `onStartCommand` / `anyTriggerOn` / `onDestroy` 自愈判断都加入 `auto_block_on`；
  黑名单扫描、无障碍前台监听、Shizuku 前台兜底、新装应用黑名单提示全部加 `protect_on` 门槛
  （自动拦截独立运行时不会误弹黑名单拦截窗）。
- `ForegroundService`：`anyTriggerOn` 加入自动拦截；通知文案区分「守护中 / 自动拦截中」。
- `KeepAliveAlarm` / `KeepAliveReceiver` / `BootReceiver`：自动拦截也算保活理由。
- `MainActivity`：
  - 打开应用时若自动拦截开启 → 直接拉起 `ProtectService`；
  - `setAutoBlockEnabled(true)` 拉起主服务 + 哨兵 + 保活闹钟；关闭时若无其他开关才停止；
  - `stopProtect` 按自动拦截状态决定「全停」还是「只关实时守护」。
- `index.html`：关闭守护后的 toast 改为按自动拦截状态提示。

---

## ② 调音量延迟 / 按一下跳一大截

### 根因排查（按影响从大到小）

1. **无障碍按键过滤默认常开**（最重要）
   `res/xml/accessibility_service_config.xml` 里写死了 `flagRequestFilterKeyEvents`，
   `AdSkipService` 还再次强行加上。这个 flag 会让系统把**所有按键先交给本应用的无障碍服务、
   等 onKeyEvent 返回后才继续处理**。主线程一忙，音量键就被拖慢；用户因延迟多按住一会儿，
   系统触发长按连发 → 一次按键跳很多格。
2. **onKeyEvent 同步做业务**
   旧代码在 `onKeyEvent` 里直接调 `ProtectService.countVolumePress()`（读 SharedPreferences、
   访问列表），返回前系统一直在等，进一步放大延迟。
3. **120ms 主线程轮询**
   `ProtectService` 每 120ms 在主线程对 5 个音频流做 `AudioManager.getStreamVolume` binder 调用，
   周期性占住主线程（无障碍按键分发也在同一主线程上等待）。
4. **黑名单扫描空跑**
   `scanBlacklistedApps()` 每 4 秒对全部已安装应用逐个 `isSys()`（PackageManager 查询），
   即使用户没有黑名单也要跑 300+ 次 binder 调用，长时间占用主线程。
5. **应用清单重建在主线程**
   病毒库扫描每 10 轮在主线程 `getInstalledApplications + 读 300+ 应用名`，每次几百毫秒。
6. **摇一摇传感器常驻**
   即使摇一摇关闭也注册 50Hz 加速度传感器，持续唤醒主线程。

### 修复

| 位置 | 修复 |
| --- | --- |
| `accessibility_service_config.xml` | 去掉默认 `flagRequestFilterKeyEvents`（保留 `canRequestFilterKeyEvents`） |
| `AdSkipService` | 新增 `setKeyFilterEnabled()`：只有「音量按键触发」开启时才在运行时加回过滤；`onServiceConnected` 按开关恢复；`onKeyEvent` 只 `mHandler.post(...)` 后立刻返回；`onVolumeChanged` 去掉同步业务与日志 |
| `ProtectService` | 120ms 主线程轮询 → **200ms 独立 HandlerThread**；无音量触发且无金龙引擎时降到 1 秒空转；恢复时重置基线；计数回主线程 |
| `ProtectService.scanBlacklistedApps` | 黑名单为空直接 return；只对黑名单包做判断；系统应用改用 `ApplicationInfo.flags`，不再逐个 PM 查询 |
| `ProtectService` 病毒库 | 应用清单改为后台线程构建（每 2 分钟或安装变化时），主线程只用缓存；`GoldenDragon.sync` 也挪到后台线程 |
| `ProtectService` 摇一摇 | 传感器按开关动态注册/注销（`applyShakeSensorState`），关掉摇一摇立即注销 |
| `MainActivity` | 开关变化时向服务发 `ACTION_REFRESH`，立即应用传感器/按键过滤/通知状态；`stopProtect` 关闭音量触发时同步关闭按键过滤 |
| 金龙引擎 | 无按键过滤时用「单步变化 / 700ms 同向连发」推断物理按键，避免用户调音量被误判成软件篡改 |

> 注：`onKeyEvent` 异步化后，即使音量按键触发开启，按键处理也不再被本应用的业务逻辑阻塞。

---

## 构建与验证

- `gradlew.bat :app:assembleRelease` 通过。
- 产物：`build_out/_app/outputs/apk/release/app-release-<时间戳>.apk`（根目录另存一份）。
- 已验证：
  - 编译通过；
  - 无障碍配置 XML 已无 `flagRequestFilterKeyEvents`；
  - 内联 JS `node --check` 通过。
- 真机音量体验需要安装后实测：如果仍有延迟，可抓 `adb logcat -s ProtectService AdSkipService`，
  观察是否还有 `按键过滤 → 开`（只有开音量按键触发时才应出现）。
