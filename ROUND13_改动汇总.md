# 第 13 轮改动汇总（2026-10-01）

本轮把三件事一起做完：**root 误判修复** + **root 判定诊断桥接** + **状态栏/导航栏显式预留（Android 15/16 遮挡）**。

---

## 1. 修 root 误判（没 root 的用户被判定有 root）

### 原来的问题（两个不可靠兜底）

| 位置 | 原逻辑 | 为什么会误判 |
| --- | --- | --- |
| `suBinaryExists()` | 只看 `/system/bin/su`、`/system/xbin/su`、`/sbin/su` … **文件是否存在** | 部分 ROM / 厂商工程模式 / 残留文件会有同名文件，但设备并没有 root |
| `deviceIsRooted()` 包名清单 | 含 `com.koushikdutta.superuser` | 这个包名并不专属 root 管理工具，容易误命中 |

而且**同一套判定存在三份**（外层 `MainActivity`、内部类 `JavaScriptInterface`、
`StellarBridge`），各改各的，很难保持一致。

### 改法：改成"实证"判定

```
判据只有一条：su -c id 真的跑起来，并且输出里出现 uid=0
  · Magisk / KernelSU / APatch 已授权 → 返回 uid=0     → 判有 root ✔
  · 没有 root（su 不存在）/ 用户拒绝 / 超时 → 判没有 root ✔
```

具体：

- 新增 `suBinaryPresent()`：只用来决定"**要不要去试一次 su**"，**不再作为 root 结论**；
- 新增 `suGrantsRoot()`：`ProcessBuilder("su","-c","id")`，3 秒超时，
  输出含 `uid=0` 才算 root；装已知 root 管理器或存在 su 文件时才尝试（减少无谓尝试）；
- `deviceIsRooted()` 改为只调 `suGrantsRoot()`；
- 删掉 `com.koushikdutta.superuser` 这个易误命中的包名；
- **三套实现统一**：内部类 `getEnvironmentWarning()` 改为委托外层
  `deviceIsRooted()` / `deviceHasDhizuku()`，并删掉内部类里重复的
  `isPackageInstalled()` / `suBinaryExists()`。

### 真机验证

```
安装: Success   versionName=9.1.0   lastUpdateTime=2026-10-01 13:59:06
进入应用后页面检索:
  当前系统环境异常    未出现 ✅
  73.2%              未出现 ✅
  96.2%              未出现 ✅
```

### ⚠️ 未能验到的路径

本想造一个假的 `/system/xbin/su` 来复现"有 su 文件但没 root"的旧误判，
但 `/system` 是只读分区（`can't create /system/xbin/su: No such file or directory`），
所以**这条路径没能在真机上实测**。逻辑上它现在由 `su -c id` 兜住：
只要 su 跑不起来或拿不到 uid=0，就不会判有 root。

---

## 2. 新增 root 判定诊断桥接（排错用）

`MainActivity` 新增 `buildRootDiagnosticsJson()` + `JavaScriptInterface.getRootDiagnostics()`：

```json
{
  "rooted": false,
  "dhizuku": false,
  "packages": { "com.topjohnwu.magisk": false, "me.weishu.kernelsu": false, ... },
  "suPaths":  { "/system/bin/su": false, "/system/xbin/su": false, ... },
  "suIdOutput": "(unavailable: IOException)"
}
```

**用途**：万一还有用户反馈误判，让他在网页控制台执行 `Android.getRootDiagnostics()`，
把 JSON 发回来就能立刻定位是哪一条命中（哪个包装了 / 哪个 su 路径存在 / su 实际返回什么），
不用再靠猜。

> 说明：上一轮这个桥接**没注册上**（补丁锚点因文件里存在混合行尾而匹配失败，
> 脚本自己报了"匹配 0 次"），本轮已补上并核对：
> `getRootDiagnostics` 在 `classes3.dex` 里可检索到。

---

## 3. 状态栏/导航栏显式预留（Android 15/16 上被遮挡）

Android 15（API 35）起，targetSdk ≥ 35 的应用被**强制 edge-to-edge**：
系统不再自动预留状态栏/导航栏区域，不自己消费 `WindowInsets` 就会被压住。

### 改动

**布局** `activity_main.xml`：

- 根布局新增 `android:id="@+id/rootLayout"`（供 inset 监听挂载）；
- `statusBarSpacer` 增加 `android:minHeight="24dp"` 作兜底；
- **新增 `navBarSpacer`**（底部导航栏/手势条预留条）；
- `mainContent` 的底部约束改为 `toTopOf navBarSpacer`。

**代码** `MainActivity.java` 新增：

| 方法 | 作用 |
| --- | --- |
| `setupWindowInsets()` | 在根布局挂 `ViewCompat.setOnApplyWindowInsetsListener`，读 `statusBars.top` / `navigationBars.bottom`（取不到退回 `systemBars.bottom`）/ `systemBars.left,right` |
| `applyInsetsToViews(fullscreen)` | 把值写到两根预留条的高度 + `mainContent` 的左右内边距；全屏时全部归零 |
| `setFullscreenInsets(fullscreen)` | 由 `onShowCustomView` / `onHideCustomView` 调用（视频全屏要铺满） |

**为什么用占位 View 而不是给根布局加 padding**：根布局加 padding 会把
`fullscreenContainer`（视频全屏层）也一起缩进去，而全屏视频必须铺满整屏。
用占位条可以只挤开"正常界面"。

**缓存最近一次 inset 值**（`lastStatusBarInset` / `lastNavBarInset` / `lastSideInset`），
供全屏切换时还原。

### ⚠️ 验证状态

- 代码已编入包：`classes3.dex` 里可检索到 `setupWindowInsets`、`navBarSpacer`；
- **但那台设备是 Android 14**，验不到 15/16 的强制 edge-to-edge 行为，
  而且设备上也没法把系统升级/模拟到 Android 16。
  这一项**需要在你的 Android 16 机器上看效果**。

---

## 本轮踩到的坑：文件混合行尾导致补丁静默失效

`MainActivity.java` 里出现了**混合行尾**（4016 个 CRLF + 8 个裸 LF），
我按 CRLF 拼的锚点自然匹配不上，于是补丁"跳过"了 —— 上一轮
`getRootDiagnostics` 就是这么丢的（好在脚本会打印"匹配 0 次"，没完全静默）。

本轮做法：

1. 先把整个文件**归一成 CRLF**（先全转 LF 再全转 CRLF），消除混合；
2. 对缩进不整齐的那一行，改用**行索引插入**（读成数组 → splice → 写回），
   彻底绕开字符串锚点的脆弱性；
3. 复查：裸 LF = 0。

---

## 构建与安装

```
JAVA_HOME=D:\scoop\apps\temurin21-jdk\21.0.12-8.0
gradlew :app:assembleRelease  → BUILD SUCCESSFUL in 5m 32s
游龙安全护盾-9.1.0-release.apk  37.9 MB  (app-release-20261001-135303.apk)
pm install -r -d  → Success   (versionName=9.1.0, lastUpdateTime=2026-10-01 13:59:06)
```

dex 校验（`classes3.dex` 命中）：
`getRootDiagnostics`、`buildRootDiagnosticsJson`、`suGrantsRoot`、
`setupWindowInsets`、`navBarSpacer`
