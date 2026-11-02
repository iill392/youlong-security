# 游龙安全护盾（YouLong Security Shield）

**免 Root 的 Android 安全防护与应用风险治理工具**

> 当前版本：**V91 / 9.9.60**  
> 最新源码分支：`sync/v91`  
> 项目状态：持续维护中

---

## 一、这是什么

游龙安全护盾是一个独立的 Android 安全工具，采用 **WebView 前端 + 原生 Java/Kotlin + NDK/C++ + 内置特权内核** 的混合架构。

它不是 Stellar 或 Shizuku 的官方产品，而是在本工程中**源码级内置**了开源特权框架 [Stellar](https://github.com/roro2239/Stellar)（Shizuku 的深度定制分支）。因此不需要额外安装 Stellar 或 Shizuku App，也能在本应用内启动特权服务，以 Android `shell`（uid 2000）身份执行受控的系统操作。

应用包名：`com.youlong.hd`

---

## 二、主要能力

| 能力 | 说明 |
|---|---|
| 实时守护 | 前台服务常驻，按设定周期检查前台应用与风险状态 |
| 多档拦截 | 基础 / 日常 / 超级 / 极强 / 终结等多档策略，按风险等级处置 |
| 紧急逃生 | 连续按音量键或用力摇一摇，触发紧急救援流程 |
| 病毒扫描 | 本地特征匹配与风险应用扫描，支持自动处理策略 |
| 应用风险治理 | 读取已安装应用、风险权限、黑名单、白名单与冻结管理 |
| 无障碍辅助 | 监听前台窗口变化、音量键事件，用于守护与逃生 |
| 内置特权服务 | 免 Root 启动 Stellar 特权服务，执行受控 shell 操作 |
| 自保 watchdog | 自己被停用、组件被关、进程被停或特权服务丢失时尝试恢复 |
| 日志与自检 | 崩溃日志、运行轨迹、服务状态与诊断入口 |

### V91 的主要调整

- **V84**：Stellar / Shizuku 权限对全部应用预授权，减少“应用未请求权限就无法使用”的问题；
- **V85**：删除广告跳过扫描逻辑、性能模式与 FPS 监控旧代码，精简无障碍服务；
- **V86**：未授予“读取应用列表”权限时，在首页显示授权横幅，不再自动跳应用信息页；
- **V87**：自保只恢复自身，删除冻结嫌疑应用、归因和黑名单强停逻辑；
- **V88 / V90**：白名单管理改为后台加载；应用图标按可见行懒加载，兼顾速度和图标展示；
- **V91**：内置特权管理器底部导航隐藏“授权应用”入口，但保留页面、路由和授权逻辑。

> V91 起，Stellar / Shizuku 权限默认对应用强制放行，授权范围明显扩大。该行为适合受控设备或自用场景，不适合作为多用户公共设备上的默认安全配置。

---

## 三、技术架构

```
┌──────────────────────────── 单 APK（com.youlong.hd）────────────────────────────┐
│                                                                              │
│  WebView 前端（app/src/main/assets/index.html，JS Bridge）                    │
│        ↕                                                                     │
│  原生 Java 层（Activity / Service / 无障碍 / 覆盖层 / 守护 / 权限）             │
│        ↕                                                                     │
│  内置 Stellar 特权框架（manager / server / api / provider / aidl / native）    │
│        ↕ Binder                                                              │
│  stellar_server（shell uid 2000，由本应用启动的独立进程）                       │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

- **前端**：主界面为 WebView 单页应用，通过 `@JavascriptInterface` 与原生通信；
- **原生层**：负责守护服务、无障碍、悬浮窗、应用风险、白名单、自保、日志等；
- **特权层**：Stellar 服务端与客户端 API 源码级编译进 APK，不依赖外部 App；
- **原生管理器**：内置特权管理器使用 Jetpack Compose / Material 3。

---

## 四、目录结构

```text
anquan/
├─ app/                        宿主应用：Java、前端资源、服务、无障碍、守护逻辑
│  └─ src/main/assets/         前端页面与自保脚本
├─ embedded/
│  ├─ manager/                 内置特权管理器（Compose UI）
│  ├─ server/                  特权服务端
│  ├─ api/                     客户端 API
│  ├─ provider/                StellarProvider
│  ├─ aidl/                    Stellar AIDL
│  └─ shizuku-*/               Shizuku 兼容层
├─ docs/                       说明文档
├─ LICENSE                     Apache License 2.0（本项目自有部分）
├─ NOTICE                      第三方组件与修改声明
├─ COPYRIGHT.md                版权与来源说明
└─ THIRD_PARTY_NOTICES.md      第三方许可证清单
```

---

## 五、构建

### 环境

| 项目 | 版本 |
|---|---|
| JDK | 21 |
| Gradle | 8.14 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.0 |
| minSdk | 24（Android 7.0） |
| 编译 SDK | 37 |

### 命令

```bash
# Windows
gradlew.bat :app:assembleRelease

# Linux / macOS
./gradlew :app:assembleRelease
```

构建产物：

```text
app/build/outputs/apk/release/
```

发布签名、签名口令和密钥文件不写入仓库，请使用自己的签名配置。

---

## 六、权限与风险说明

应用可能请求以下能力：

- **无障碍服务**：识别前台窗口、接收音量键事件；部分厂商 ROM 会自动关闭无障碍服务；
- **悬浮窗**：在锁机或风险应用界面上方显示救援层；
- **使用情况访问**：辅助识别前台应用；
- **读取应用列表**：读取已安装应用、风险权限和黑名单；
- **通知权限**：显示守护状态与必要提示；
- **内置特权服务**：以 shell 身份执行受控操作，能力较强。

注意事项：

1. 无障碍、悬浮窗、使用情况访问在不同厂商 ROM 上行为差异很大；
2. 部分 ROM 会在重启、清理后台或安全检测后自动关闭无障碍；
3. V91 起默认对应用预授权 Stellar / Shizuku，请只在可信设备上使用；
4. 不要把本应用安装在不受你控制的设备上。

---

## 七、许可证与第三方项目

本项目**自有代码、界面与文档**采用 **Apache License 2.0**。

内置特权框架来源：

- **Stellar / Stellar-API**  
  https://github.com/roro2239/Stellar  
  Copyright (C) roro2239 / RoRo Studio  
  修改部分采用 Mozilla Public License 2.0

- **Shizuku / Shizuku-API**  
  https://github.com/RikkaApps/Shizuku  
  Copyright (C) RikkaApps  
  Apache License 2.0

本项目不是 Stellar 或 Shizuku 的官方产品，与上述作者/组织不存在隶属、合作、代理或背书关系。“Stellar”“Shizuku”等名称、标识、商标归各自权利人所有。

完整许可证、修改文件说明与源码获取方式见：

- `LICENSE`
- `NOTICE`
- `COPYRIGHT.md`
- `THIRD_PARTY_NOTICES.md`
- `LICENSES/`

---

## 八、仓库分支说明

远端仓库的 `main` 与当前 V91 本地历史没有共同祖先，因此没有强推覆盖 `main`。

当前最新源码快照位于：

```text
分支：sync/v91
标签：V91-clean
```

该分支是**干净源码快照**，已排除历史中的大体积调试产物（如 `classes_dex_dump.txt`），可直接阅读、构建和发起 PR。

---

## 九、免责声明

本项目用于个人设备的安全防护与应用风险治理研究。使用本软件即表示你理解：

1. 特权操作、应用冻结、强制停止等能力存在误操作风险；
2. 因 ROM 差异、系统限制、用户配置或第三方应用行为造成的后果，由使用者自行承担；
3. 请勿将本软件用于未经授权的设备、他人设备或非法用途；
4. 项目不提供任何形式的官方担保或线下支持。

---

**Copyright (C) 2026 游龙**  
自有代码适用 Apache License 2.0；第三方组件版权归各自作者所有。
