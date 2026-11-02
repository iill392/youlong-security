# 第 10 轮改动汇总（2026-10-01）

## 1. 进入应用即要求「读取应用」+「通知」权限，否则无法开启安全守护

### 原生侧（`MainActivity.java`）

新增 4 个 JS 桥接方法 + 6 个外层实现方法。

| 桥接方法 | 作用 |
| --- | --- |
| `getEntryRequirements()` | 返回 `{notification, applist, ok, missing[]}` |
| `requestNotificationPermission()` | 请求通知权限（Android 13+ 弹窗，12L 及以下直接返回） |
| `openAppListPermissionSettings()` | 打开本应用「应用信息」页，供用户检查读取应用权限 |
| `getEnvGoodHint()` | 绿色环境提示的判定（见第 2 项） |

**为什么"读取应用"不能只读权限 flag**：它靠 `QUERY_ALL_PACKAGES`，这是
**normal 权限**，安装时即授予，没有运行时弹窗；只看 flag 会永远显示"已授予"。
所以 `hasAppListAccessOuter()` 是**真的去查一次已安装应用列表**
（`getInstalledApplications(0)`，少于 5 个视为被系统过滤 → 判定不可用）。

**作用域修复**：这几个判定最初被加进了内部类 `JavaScriptInterface`，
但 `onRequestPermissionsResult()`（Activity 回调）在外层类里用不到它们。
已把判定提到外层（`buildEntryRequirementsJson()` / `hasNotificationPermissionOuter()` /
`hasAppListAccessOuter()` / `isInstalledPkg()` / `suBinaryExistsOuter()` /
`deviceIsRooted()` / `deviceHasDhizuku()` / `buildEnvGoodHintJson()`），
内部类里的同名方法改为**委托调用**，保证只有一份实现。

**权限结果回灌**：`onRequestPermissionsResult()` 里判断
`REQ_NOTIFICATION_PERMISSION`（0x7A21），把最新结果通过
`evaluateJavascript("window.__dshOnPermResult({...})")` 推给网页，卡片立即刷新。

### 网页侧（`index.html`）

- 新增卡片 `#entryPermCard`（橙色描边），缺权限时显示，列出缺哪几项，
  带三个按钮：`授予通知权限` / `检查读取应用权限` / `我已授权，重新检查`；
- `init()` 里进页面就调一次 `refreshEntryRequirements()`，
  并在缺通知权限时**主动申请一次**；
- **开启开关拦截**：`toggle.onchange` 里先重新查一次权限，
  缺任一项就把开关弹回、保持关闭，弹 Toast 提示缺哪些权限，并让引导卡保持可见。

### 真机验证

```
runtime permissions:
  android.permission.POST_NOTIFICATIONS: granted=true   ← 通知权限已授予
QUERY_ALL_PACKAGES 声明数: 2                              ← 读取应用权限在清单里
dumpsys package … | grep -c QUERY_ALL_PACKAGES → 2
```

两项都就绪 → 门槛卡正确地**自动隐藏**，实时守护可以正常开启
（截图里开关是打开状态、显示「已守护 9m」）。缺权限时的拦截路径已接线并编译进包，
需要真正拒绝一次权限才能看到卡片。

## 2. 无 root 且无 Dhizuku → 安全守护开关上方显示绿色描边提示（可关闭）

- 原生 `getEnvGoodHint()`：`无 root && 无 Dhizuku` → `{"show":true,...}`；
  root 判定 = 常见 root 管理器包名（Magisk / KernelSU / APatch / SuperSU）
  + 常见 su 二进制路径（只读探测，**不执行 su**，不会弹 root 授权框）。
- 网页新增卡片 `#envGoodCard`，**绿色描边**（`border:1.5px solid #34C759`），
  位置就在 `#toggleCard`（开启实时守护）**正上方**。
- 文案：`当前没有 root、也没有安装 Dhizuku，日常模式2.0 可以发挥 100% 拦截能力，误伤率基本没有。`
- 关闭入口：`知道了，不再提示` → 写入 `localStorage`（key `env_good_dismissed_v1`），
  永久隐藏（需求明确要求"可以关闭"，与红色环境异常卡的"不可关闭"相反）。

### 真机验证（截图）

```
✅ 环境良好
当前没有 root、也没有安装 Dhizuku，日常模式2.0 可以发挥 100% 拦截能力，误伤率基本没有。
知道了，不再提示          ← 绿色描边，位于「开启实时守护」正上方
```

---

## 构建与安装

```
JAVA_HOME=D:\scoop\apps\temurin21-jdk\21.0.12-8.0
gradlew :app:assembleRelease  → BUILD SUCCESSFUL in 5m 1s
app-release-20261001-113549.apk  37.9 MB
pm install -r -d  → Success  (lastUpdateTime=2026-10-01 11:42:31)
```

包内确认（`classes3.dex` 检索到）：
`getEntryRequirements`、`requestNotificationPermission`、`getEnvGoodHint`、
`buildEntryRequirementsJson`。

## 本轮踩到的两个坑

1. **内部类作用域**：把方法加进 `JavaScriptInterface` 后，外层
   `onRequestPermissionsResult()` 调用不到 → `找不到符号 方法 getEntryRequirements()`。
   已把判定提到外层、内部类委托。
2. **`ApplicationInfo` 未导入**：外层新增的 `hasAppListAccessOuter()` 用了
   `List<ApplicationInfo>`，而该文件没 import 它 → `找不到符号 类 ApplicationInfo`。
   已改用全限定名 `java.util.List<android.content.pm.ApplicationInfo>`。

（另：本轮构建还清理了 `MainActivity.java:201` 与 `:1530` 两个编译错误，
均为上述两处引起。）
