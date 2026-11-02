# 特权内核迁移说明：Shizuku → Stellar

本文档记录「游龙安全护盾」内置特权内核从上游 **Shizuku** 整体替换为
**[Stellar](https://github.com/roro2239/Stellar)** 的完整过程、决策依据与注意事项。

---

## 1. 迁移目标与结论

| 项目 | 迁移前 | 迁移后 |
|---|---|---|
| 内置特权内核 | RikkaApps/Shizuku 13.6.0（源码级融合） | roro2239/Stellar（源码级融合） |
| 内核许可证 | Apache-2.0 | **MPL-2.0**（修改部分）+ Apache-2.0（内含的 Shizuku 部分） |
| 管理器界面 | `moe.shizuku.manager`（降级为 library） | `roro.stellar.manager`（降级为 library） |
| 客户端 API | `rikka.shizuku.Shizuku` | `roro.stellar.Stellar` |
| 服务端类 | `rikka.shizuku.server.ShizukuService` | `roro.stellar.server.StellarService` |
| 原生启动器 | `libshizuku.so` | `libstellar.so` + `libchid.so` |
| 架构 | **本应用即管理器，不依赖任何外部应用** | **不变**（这是选择「源码内置」而非「依赖 JitPack 制品」的原因） |

> 迁移后依然「本应用自带特权服务端与管理器」，用户不需要安装任何外部
> Shizuku / Stellar 应用。

---

## 2. ⚠️ 工程位置已变更（必读）

**工程目录从 `E:\安全护盾\anquan` 迁到了 `E:\anquan`。**

原因：Windows 版 `aidl.exe` 会把 `.aidl` 源文件的**绝对路径**按系统 ANSI 代码页
（GBK）写进依赖文件 `*.d`；AGP 用 `Files.readAllLines()` 回读该文件，而该方法在
JDK 18+ 上**固定按 UTF-8 解码**，遇到路径里的中文（"安全护盾" = `B0 B2 C8 AB BB
A4 B6 DC`）就抛：

```
Execution failed for task ':aidl:compileDebugAidl'.
  > java.nio.charset.MalformedInputException: Input length = 1
    at com.android.builder.internal.incremental.DependencyData.processDependencyData
    at com.android.build.gradle.tasks.AidlCompile$DepFileProcessor.processFile
```

已逐一验证**没有**任何 Gradle / JVM 参数可以绕过：

* `-Dfile.encoding=GBK` 无效 —— 实测 `file.encoding=GBK`、`defaultCharset=GBK`
  时 `Files.readAllLines()` 仍抛异常（该方法已被 JDK 钉死在 UTF-8）；
* 只把产物目录改到 ASCII 路径无效 —— `.d` 里记录的是**源码**路径；
* 手工把 AIDL 源与产物都放到纯 ASCII 路径直接调用 `aidl.exe` 是**通过**的。

**因此工程路径必须保持纯 ASCII。请不要把工程移回含中文的目录。**

---

## 3. 工具链变更

| 项 | 迁移前 | 迁移后 | 原因 |
|---|---|---|---|
| Gradle | 8.4 | **8.14** | 上游 Stellar 用的版本；AGP 8.13 要求 ≥ 8.13 |
| AGP | 8.2.2 | **8.13.2** | 上游 Stellar 用的版本 |
| Kotlin | 2.0.0 | **2.2.0** | 上游 Stellar 用的版本 |
| Compose 编译器插件 | 2.0.0 | **2.1.21** | 与 Kotlin 2.2.0 配套 |
| KSP | 无 | **2.2.0-2.0.2** | Stellar manager 用 Room 存授权/命令记录 |
| compileSdk / targetSdk | 34 | **37** | Stellar 用到 API 37 的 `ACCESS_LOCAL_NETWORK`（mDNS 无线配对）、`USE_LOOPBACK_INTERFACE` |
| Java / jvmTarget | 11 | **21** | Stellar 全模块 `jvmToolchain(21)` |
| NDK | 27.0.12077973 | **29.0.13113456** | 上游 Stellar manager 指定的版本 |
| Compose BOM | 2024.09.00 | **2026.01.01** | 与 Stellar 的 Material3 / Compose 代码匹配 |

> AGP 8.13.2 官方只测到 compileSdk 36.1；37 会打一条警告，已在
> `gradle.properties` 里用 `android.suppressUnsupportedCompileSdk=37` 压掉。

---

## 4. 目录结构变化

```
embedded/
├── manager/        Stellar 管理器（上游是 application，本工程改为 library）
├── server/         Stellar 特权服务端
├── aidl/           Stellar AIDL（com.stellar.server.*）
├── shared/         共享常量（roro.stellar.StellarApiConstants）
├── api/            客户端 SDK（roro.stellar.Stellar）
├── provider/       StellarProvider（接收服务端 Binder）
├── userservice/    UserService 框架
├── shizuku-aidl/   Shizuku 兼容层 AIDL（moe.shizuku.server.*）
├── shizuku-api/    Shizuku 兼容层客户端
├── LICENSES/       MPL-2.0.txt、Apache-2.0.txt
└── （各模块的 *.versions.toml 版本目录）

app/src/main/cpp/
├── CMakeLists.txt      入口（add_subdirectory(stellar)）
├── stellar/            Stellar 原生层（starter/chid/adb_pairing/rish）
├── NativeCrypto.cpp    本应用自有：加密核心
└── GuardSentinel.cpp   本应用自有：哨兵守护

removed_shizuku_native/  已移除的 Shizuku 原生启动器与 ADB 配对（留档备查）
embedded_shizuku_removed/ 已移除的 Shizuku 源码树（留档备查）
```

**被移除的模块**：`:rish`、`:starter`、`:server-shared`、`:common`
（前三个是 Shizuku 专有模块；Stellar 已移除 rish 独立 APK，starter 职责并入
manager，server-shared 并入 server）。

---

## 5. 应用级标识符改写

上游 Stellar 的管理器是独立 APK（`applicationId = roro.stellar.manager`）。
本工程把管理器内置进 `com.youlong.hd`，因此这些**应用级**标识符随之改写：

| 标识符 | 上游值 | 本工程值 |
|---|---|---|
| 管理器包名 / applicationId | `roro.stellar.manager` | `com.youlong.hd` |
| Provider authority | `${applicationId}.stellar` | `com.youlong.hd.stellar` |
| 请求权限 action | `roro.stellar.manager.intent.action.REQUEST_PERMISSION` | `com.youlong.hd.intent.action.REQUEST_PERMISSION` |
| binder extra key | `roro.stellar.manager.intent.extra.BINDER` | `com.youlong.hd.intent.extra.BINDER` |
| client binder extra key | `roro.stellar.manager.intent.extra.CLIENT_BINDER` | `com.youlong.hd.intent.extra.CLIENT_BINDER` |
| 日志回传 authority | `roro.stellar.manager.stellar` | `com.youlong.hd.stellar` |
| 原生启动器 PACKAGE_NAME | `roro.stellar.manager` | `com.youlong.hd` |
| 开机脚本 `pm path` | `roro.stellar.manager` | `com.youlong.hd` |

**Java/Kotlin 包名保持上游原名不变**（`roro.stellar.*`、`com.stellar.*`、
`moe.shizuku.*`）。原因：原生启动器用 `CLASSPATH` 加载
`roro.stellar.server.StellarService`，`adb_pairing.cpp` 用
`FindClass("roro/stellar/manager/adb/PairingContext")` 注册 native 方法，
宿主用类名字符串启动管理器界面 —— 改包名会破坏这些硬编码绑定。

> 逐文件的改动清单见根目录 `NOTICE` 第 1 节 (D)。

---

## 6. 宿主代码迁移

| 文件 | 改动 |
|---|---|
| `ShizukuUtils.java` → **`StellarUtils.java`**（重命名） | `rikka.shizuku.Shizuku` → `roro.stellar.Stellar`；`checkSelfPermission()` 由返回 `int` 改为返回 `boolean`；删除 `isPreV11()` 判断；`newShizukuProcess` → `newPrivilegedProcess`（不再需要反射，`Stellar.newProcess` 是 public API） |
| `ProtectService.java` | 55 处 `ShizukuUtils.*` → `StellarUtils.*`；20+20 处方法重命名；断连监听改用 `Stellar.INSTANCE.add/removeBinderDeadListener` |
| `MainActivity.java` | 96 处；`initShizuku()`→`initStellar()`、`runShizukuCommand`→`runStellarCommand`、`ShizukuBridge`→`StellarBridge`、`openShizukuManager()`→`openStellarManager()` 等；管理器 Activity 类名改为 `roro.stellar.manager.MainActivity` |
| `ShieldWarnActivity.java` | 15 处 `ShizukuUtils.*` → `StellarUtils.*` |
| `YouLongApp.java` | 删除 `ShizukuProvider.disableAutomaticSuiInitialization()`（Stellar 已移除 Sui 支持）；新增对 `StellarApplication.attachApplication()` / `init()` / `BootStartNotifications.createChannel()` 的显式调用 |
| `app/proguard-rules.pro` | keep 规则由 `rikka.shizuku.**` 改为 `roro.stellar.**` / `com.stellar.**` / `roro.stellar.manager.adb.**` 等 |
| `app/src/main/AndroidManifest.xml` | Provider 由 `rikka.shizuku.ShizukuProvider` 改为 `roro.stellar.StellarProvider`，authority 改为 `${applicationId}.stellar`，新增 `roro.stellar.permissions` meta-data |

### 6.1 WebView 桥接名称（有意保留旧名）

`StrX.BRIDGE_SHIZUKU` 解密后仍是字符串 **`"Shizuku"`**。这是
`webView.addJavascriptInterface(bridge, "Shizuku")` 注入到页面里的对象名，
页面里大量使用 `Shizuku.getServiceStatus()` / `Shizuku.isShizukuReady()` /
`Shizuku.requestPermission()`。改名需要同时改动加密后的 `index.html`，
风险高收益低，**故有意保留**。

同理，Java 侧 `StellarBridge.getServiceStatus()` 仍返回中文字符串
`"Shizuku已连接"` / `"Shizuku待授权"` / `"Shizuku未启动"`，因为页面会逐字比对
这些值。**这些是两端的协议常量，不是对外展示的品牌文案。**

用户可见的界面文案（引导页标题、按钮、说明）已全部改为「内置特权服务」
与 Stellar 的出处说明，见 `index.html`。

---

## 7. 许可证合规（用户明确要求）

这是本产品**首次**引入弱 copyleft（MPL-2.0）代码，已落实以下动作：

| 文件 | 作用 |
|---|---|
| `LICENSES/MPL-2.0.txt` | MPL-2.0 全文（Stellar 修改部分） |
| `LICENSES/Apache-2.0.txt` | Apache-2.0 全文（自有代码 + 经 Stellar 内含的 Shizuku） |
| `NOTICE` | 逐条署名 + **全部修改的逐文件清单** + MPL-2.0 合规要点（打包进 APK） |
| `THIRD_PARTY_NOTICES.md` | 完整依赖清单与许可对照表；含 Stellar-API 的 GPL 标注冲突说明 |
| `app/src/main/res/raw/open_source_licenses.txt` | 应用内可查看的版本：Apache-2.0 全文 + MPL-2.0 全文 + NOTICE（三部分） |

要点：

* MPL-2.0 覆盖的源码**随仓库分发**（`embedded/` 与 `app/src/main/cpp/stellar/`），
  满足第 3.1 / 3.2 条；
* 未删改任何版权与许可声明；每一处改动都在被改动文件顶部有显著中文修改说明
  （满足第 3.4 条 / Apache-2.0 第 4(b) 条）；
* MPL-2.0 是文件级 copyleft，**不要求本产品整体开源**；
* 本产品**不含**任何 GPL / LGPL / AGPL 代码。

> ⚠️ **务必不要**把依赖改成 `com.github.roro2239:Stellar-API:<版本>`：
> 该仓库的 `publish.gradle` 把 POM 的 license 写成 **GPL-3.0**（而其 README
> 与源码声明的是 MPL-2.0，且仓库里根本没有 LICENSE 文件）。
> 本工程全程以源码形式取自上游仓库，从而避开了这条不确定的 GPL 声明。
> 详见 `THIRD_PARTY_NOTICES.md` 第 5 节。

---

## 8. 构建

```bash
# 工程必须位于纯 ASCII 路径（当前为 E:\anquan）
cd E:\anquan

# 配置检查
.\gradlew.bat projects

# 构建 APK（产物在 build_out/_app/outputs/apk/）
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

本机构建前提：

* JDK 21（`JAVA_HOME` 指向 Temurin 21）
* Android SDK：platform `android-37.0`、build-tools `37.0.0`
* NDK `29.0.13113456`（`D:\Android\sdk\ndk\29.0.13113456`）
* 网络可访问 `dl.google.com`、`repo1.maven.org`、`jitpack.io`
  （JitPack 用于 libsu / Capsule / compose-markdown）
* Node.js + `scripts/node_modules/javascript-obfuscator`（assets 混淆前置任务）

### 构建产物目录

所有子模块的 `buildDir` 被根 `build.gradle` 统一重定向到 `<工程根>/build_out/_<模块名>`：

* 绕开 Windows Defender 对工程树内产物的锁定；
* **必须与源码同盘**：KSP（`:manager` 的 Room）会校验生成文件与源码同根，
  指向别的盘符会报 `this and base files have different roots`。

### prefab 解压目录

`:app:extractPrefabPackages` 把 libcxx / boringssl 解压到 **`app/.prefab/`**。

* ⚠️ 该目录**不能放在 `buildDir` 里**：`gradlew clean :app:assembleDebug`
  会死锁并报
  `Unable to make progress running work. There are items queued for execution
  but none of them can be started`
  —— 因为 `:app:clean` 要删 `buildDir`，而该任务又把 `buildDir/prefab` 声明为
  自己的输出，Gradle 检测到环后两个任务都排不进去。
* 该目录已加入 `.gitignore`（可由 Maven 重新生成，不入库）。

---

## 9. ⚠️ 库模块资源**顶掉**宿主资源（真机上能看到的三处异常）

这是本次迁移里**唯一一个会直接打到用户脸上**的坑，且非常隐蔽：
Android 资源合并时，**库模块的资源默认优先于主模块**（`values/` 同名资源），
所以把上游 Stellar 管理器当库编进来后，它那套「作为一个独立 App」的资源
把宿主的应用身份整个盖掉了。实测 `aapt2 dump badging` 可见三处：

| 现象 | 真机表现 | 来源 | 处理 |
|---|---|---|---|
| `launchable-activity: roro.stellar.manager.MainActivity` | 点桌面图标进的是 **Stellar 管理器界面**，不是本应用的 index 主页 | `:manager` 的 `MainActivity` 自带 `MAIN + LAUNCHER` intent-filter，并入宿主后抢走了桌面入口 | 删除该 intent-filter（`embedded/manager/src/main/AndroidManifest.xml`）；并在宿主 `MainActivity` 上**显式补上 `LAUNCHER`**，让桌面图标指向 index 主页 |
| `application-label-ar/-es/-fr/-ja/-pt/-ru/-zh-HK/-zh-TW: 'Stellar'` | 这些语言下桌面图标与应用信息显示英文 **"Stellar"** | `:manager` 在 8 个 `values-*` 里各自定义了 `app_name="Stellar"`，宿主只在默认 `values/` 里有中文名 | 删除那 8 条；默认 `values/` 那条**改值**为「游龙安全护盾」而不是删除（它是 fallback 链末端，删了会让 en/de/it 等语言取不到名字） |
| `icon: res/*.xml`（Stellar 图标代替宿主图标） | 桌面图标变成 Stellar 的图标 | `:manager` 的 `mipmap-*/ic_launcher*.webp` 与 `drawable/ic_launcher_foreground.xml` 覆盖宿主的 `mipmap-*/ic_launcher.jpg` | 删除管理器全部 `ic_launcher*` 资源与空 `mipmap-*` 目录 |

**教训**：把一个「原本是独立 App」的模块降级为库编进来时，**必须逐项核对
清单里的 `intent-filter`、以及 `values/`、`mipmap/`、`drawable/` 里的应用身份资源**
（应用名、图标、启动入口），否则它会静默接管整个应用。

> 检查手段：`aapt2 dump badging <apk>` 看 `application:` / `launchable-activity:` /
> `application-label*:`；`aapt2 dump resources <apk>` 看图标落在哪个文件。

---

## 10. 本次迁移中踩到的 4 个环境级坑（都已解决，务必留意）

这 4 个问题都不是本工程代码的缺陷，而是「AGP / 工具链 + 中文 Windows 环境」的
固有缺陷；记录下来以免日后重踩。

### 10.1 AIDL 编译：`MalformedInputException`

* 现象：`:aidl:compileDebugAidl` / `:shizuku-aidl:compileDebugAidl` 失败
* 根因：路径含中文 → `aidl.exe` 按 ANSI 代码页（GBK）写 `*.d` →
  AGP 用 `Files.readAllLines()`（JDK 18+ 固定 UTF-8）回读
* 解决：**工程移到纯 ASCII 路径**（`E:\anquan`）
* ⚠️ 无解的部分：`-Dfile.encoding=GBK` **无效**（已用最小 Java 程序实测）

### 10.2 prefab：`prefab_command.bat` 执行失败

* 现象：`:app:configureCMakeDebug` 失败，
  `系统找不到指定的路径。` / `'-path' 不是内部或外部命令`
* 根因：AGP 生成 `.bat` 交给 `cmd.exe` 执行，批处理里是 Gradle 缓存的绝对路径，
  其中含 **Windows 用户名**（本机为中文）→ `cmd.exe` 按 ANSI 代码页读文件 → 乱码
* 解决：`buildFeatures.prefab = false`，改用 `:app:extractPrefabPackages`
  任务直接解压两个 AAR，再以 `-D` 把路径传给 CMake
  （见 `app/build.gradle` 与 `app/src/main/cpp/stellar/CMakeLists.txt` 顶部）

### 10.3 清单与资源的 XML 注释

* 现象：`Manifest merger failed` / `Resource and asset merger: 前言中不允许有内容`
* 根因有两个：
  1. XML 注释里出现了 `--`（我写的中文分隔线），XML 规范禁止；
  2. `res/values-zh-rCN/values-zh-rCN.xml` 文件头带了 **3 个 BOM**（历史遗留）
* 解决：分隔线改用 `=`；BOM 已剥离

### 10.4 KSP 跨盘

* 现象：`:manager:kspDebugKotlin` 失败，
  `this and base files have different roots`
* 根因：曾把 `buildDir` 指到 `D:`，而源码在 `E:`
* 解决：`buildDir` 改回**同盘相对路径** `<工程根>/build_out/_<模块名>`

---

## 11. 已知遗留与后续可做项

1. **WebView 桥接名 `"Shizuku"` 未改**（见 6.1），如需彻底改名要同步修改
   加密后的 `index.html`。
2. **代码注释与日志文案中仍有部分 "Shizuku" 字样**（如
   `ProtectService` 内部注释、少数 Toast）。这些是用户可见提示，建议后续
   统一润色为「特权服务」，但不影响功能。
3. **已归档并移除**：`embedded_shizuku_removed/`、`removed_shizuku_native/`、
   `embedded_backup/`、`backup_before_restore/` 四个目录共 9097 个文件已打包为
   **`shizuku_removed_2026.zip`（20.7 MB）** 并删除原目录。
   同时清理了历史产物目录 `build-out/`、`build/`（合计约 2.8 GB）。
   工程体积从 3.56 GB 降到 **0.78 GB**。
   > 如需回溯迁移前的 Shizuku 源码/原生层，从该 zip 里取即可。
4. **`app/src/main/assets_ds/`** 是 assets 相关历史备份，与本次迁移无关。
5. **`Shizuku-13.6.0.zip`、`anquan.zip`、`1.zip`** 等**工程外层**（`E:\安全护盾\`）
   的历史压缩包未动，可按需清理。
6. **真机验证尚未进行（重要）**：debug 与 release 均已构建通过，且已核对
   R8 未改名任何被原生层按名加载的类；但下列运行时行为仍需在 Android 11+
   真机上实测：
   * `libstellar.so` 能否以 shell/root 身份拉起 `roro.stellar.server.StellarService`；
   * 管理器界面（`roro.stellar.manager.MainActivity`）能否正常打开并完成授权；
   * ADB 无线调试配对（`libadb.so` + BoringSSL）；
   * 降权激活（`libchid.so`）、开机自启、双进程互守。
7. **`:manager` 的应用内自更新**（`UpdateUtils` / `ApkDownloader`）为上游功能，
   会去 GitHub 查询 Stellar 的 release。内置场景下无意义，建议后续在
   设置页隐藏该入口（其 FileProvider 声明已因清单冲突而删除，见 `NOTICE`）。

---

## 12. 验证记录

| 项目 | 结果 |
|---|---|
| `:aidl` / `:shared` / `:api` / `:provider` / `:userservice` / `:shizuku-aidl` / `:shizuku-api` 编译 | ✅ 通过 |
| `:server` / `:manager` 编译（含 Room / KSP / Compose / refine） | ✅ 通过 |
| `:app:assembleDebug` | ✅ 通过 |
| `:app:assembleRelease`（R8 混淆 + 资源压缩 + assets 加密） | ✅ 通过 |
| 原生层：`libstellar.so` / `libchid.so` / `libadb.so` / `librish.so`（arm64-v8a + armeabi-v7a） | ✅ 全部打入 APK |
| R8 未改名原生层按名加载的类 | ✅ 已核对 9 个关键类全部 KEPT (unrenamed) |
| 许可证文本随 APK 分发 | ✅ `res/raw/open_source_licenses.txt`（表内 `raw/open_source_licenses`，实际文件 `res/f6.txt`，43,855 字节） |
| 应用内可查看 MPL-2.0 全文 | ✅ 已含 |
| 产物 | debug 72.0 MB / release 38.0 MB |

---

## 13. ⚠️ 两个 provider 抢同一 authority →「无法打开内置特权」

这是迁移后**真机报错「无法打开内置特权 / 特权服务未启动」的根因**。

### 现象

应用启动后所有需要特权的功能都提示服务未启动，`Stellar.INSTANCE.pingBinder()`
恒为 false，即使手动把服务端拉起来也一样。

### 根因

`app/src/main/AndroidManifest.xml` 里照搬 Stellar「客户端接入指南」声明了：

```xml
<provider android:name="roro.stellar.StellarProvider"
          android:authorities="${applicationId}.stellar" ... />
```

而内置管理器的清单里**也已经有一个同 authority 的 provider**：

```xml
<!-- embedded/manager/src/main/AndroidManifest.xml -->
<provider android:name=".StellarManagerProvider"
          android:authorities="${applicationId}.stellar" ... />
```

两者都解析成 `com.youlong.hd.stellar`。**Android 对同一 authority 只认一个
provider**，于是服务端投递 Binder 时：

```
ActivityManagerApis.getContentProviderExternal("com.youlong.hd.stellar", ...)
→ call("sendBinder", extras)
```

找到的那个 provider 若不是能处理 `sendBinder` 的实现，`call` 直接失败 →
Binder 永远到不了 `Stellar.INSTANCE.binder` → 应用侧表现为「服务未启动」。

### 正确做法

**只保留管理器那一个 provider。** 它本身就是完整超集：

```kotlin
class StellarManagerProvider : StellarProvider()   // ← 继承
```

它的 `call()` 先处理管理器专属方法（`loadConfig` / `saveConfig` / `getLogs` …），
其余一律 `super.call(...)` 回落到 `StellarProvider` 的
`sendBinder` / `getBinder` / `sendUserService`。也就是说管理器的 provider
**同时承担了客户端 provider 的全部职责**，再声明一个是多余且有害的。

> `roro.stellar.StellarProvider` 这个**类**仍要打包进 APK（被继承），
> 只是不该在清单里再注册一份。

### 连带修正

* 删掉 `MainActivity.initStellar()` 里的
  `StellarProvider.Companion.enableMultiProcessSupport(false)`
  —— 宿主既然不再声明 provider，调它的静态方法就是张冠李戴；
  且 `StellarProvider.attachInfo()` 自己会设 `isProviderProcess = true`。
* 删掉 `import roro.stellar.StellarProvider`。
* 更新两处过期文案（原先写「本构建未启用内置管理器界面」，实际已内置）。

### 自查手段

```bash
aapt2 dump xmltree --file AndroidManifest.xml <apk> | grep -A2 'E: provider'
```

同一 authority 出现两次就是这个问题。

---

## 14. 真机验证记录（vivo V2133A / Android 14 / 无 root）

用 adb 直接验证了**最关键、最容易出问题的一环**（原生启动器 → app_process →
加载服务端类），无需 root：

```bash
# 从新 APK 取出 arm64 的 libstellar.so，推到设备并赋可执行权限
adb push libstellar.so /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/libstellar.so
# 用 --apk= 指向正确的 APK 启动（设备上装的旧包没有 Stellar 类）
adb shell "/data/local/tmp/libstellar.so --apk=/data/local/tmp/newst.apk"
```

输出与结果：

```
检查权限: ADB (uid=2000)
检查现有服务
未发现现有服务
启动服务进程
stellar_server 进程号为 21452
stellar_starter 正常退出（退出码 0）

$ adb shell "ps -A | grep stellar"
shell  21452  1  ...  SyS_epoll_wait  S  stellar_server     ← 服务端常驻成功
```

结论：`libstellar.so` → `/system/bin/app_process` →
`roro.stellar.server.StellarService` 这条链路**在真机上完全正常**，
迁移在原生层没有引入回归。

> 注意：如果直接跑 `libstellar.so` 不加 `--apk=`，它会退回到
> `pm path <PACKAGE_NAME>` 去定位 APK，拿到的是**当前已安装的那个包**。
> 设备上装的若是迁移前的旧包（不含 Stellar 类），服务端会起来后立刻退出。
> 这不是缺陷 —— starter 已把失败如实报为进程号后退出，真正的判定依据是
> 服务端进程是否存活。

---

## 15. 真机端到端验收记录（vivo V2133A / Android 14 / 无 root / 2026-10-01）

在真机上完整跑通了一轮验收，**全部通过**。

| # | 测试项 | 结果 |
|---|---|---|
| 1 | 安装新包（签名一致，覆盖安装不丢数据） | ✅ `Success`，`targetSdk=37`（旧包是 34） |
| 2 | 桌面入口 | ✅ `com.youlong.hd/.MainActivity`（index 主页），**不再是** Stellar 管理器 |
| 3 | 应用名 / 图标 | ✅ `label='游龙安全护盾' icon='res/pp.jpg'`（宿主自己的图标） |
| 4 | Provider 唯一性 | ✅ 系统里 `.stellar` 只剩 `roro.stellar.manager.StellarManagerProvider` |
| 5 | 原生组件解压与可执行位 | ✅ `nativeLibraryDir/arm64/` 下 `libstellar/libchid/libadb/librish` 均为 `-rwxr-xr-x` |
| 6 | 启动器启动服务端 | ✅ `libstellar.so` → ADB(uid=2000) → `stellar_server` 常驻 |
| 7 | 管理器界面 | ✅ `roro.stellar.manager.MainActivity` 正常打开、无闪退 |
| 8 | **Binder 投递到应用** | ✅ `BinderSender: sendBinder：向管理器发送 Binder：com.youlong.hd` |
| 9 | 管理器被识别（`isManager`） | ✅ `ManagerGrantHelper: Granting WRITE_SECURE_SETTINGS to manager...` |
| 10 | 无需用户手动授权 | ✅ 不再弹 `RequestPermissionActivity`（管理器自动授予 `stellar`） |
| 11 | **提权真实生效** | ✅ `android.permission.WRITE_SECURE_SETTINGS: granted=true` |
| 12 | 应用无崩溃 | ✅ `logcat -b crash` 为空 |

第 11 项是最有力的证据：`WRITE_SECURE_SETTINGS` 是签名级系统权限，普通应用无法
自行获取，**只有以 shell/root 身份运行的进程才能授予**。它变为 `granted=true`
说明「内置启动器 → 特权服务端 → 提权 → 修改系统状态」这条完整链路是通的。

### 复现命令（无需 root，只用 adb）

```bash
ADB=/d/Android/sdk/platform-tools/adb.exe
APK=$(adb shell pm path com.youlong.hd | sed 's/package://')
LIB=$(adb shell dumpsys package com.youlong.hd | grep legacyNativeLibraryDir | sed 's/.*=//')

# 1) 确认原生组件可执行
adb shell "ls -l $LIB/arm64/libstellar.so"

# 2) 启动服务端（应用内也是走这条命令）
adb shell "$LIB/arm64/libstellar.so --apk=$APK"

# 3) 看服务端是否常驻
adb shell "ps -A | grep stellar"

# 4) 看 Binder 是否投递到应用 + 提权是否生效
adb logcat -d | grep -E 'BinderSender|ManagerGrant|ClientManager'
adb shell "dumpsys package com.youlong.hd | grep WRITE_SECURE_SETTINGS"
```

### 一个容易误判的点

单独跑 `libstellar.so` 时若不加 `--apk=`，它会退回到
`pm path <PACKAGE_NAME>` 去定位 APK，拿到的是**当前已安装的那个包**。
设备上若还装着迁移前的旧包（不含 `roro.stellar.server.StellarService`），
服务端进程会「起来后立刻退出」——starter 仍会打印进程号并返回 0。
**判定依据永远是「`stellar_server` 进程是否存活」，不是 starter 的退出码。**

---

## 16. ⚠️ 第二个真机根因：JS 桥接方法名与 Java 改名不同步

修掉 provider 冲突后，真机**仍然**弹「当前环境无法打开内置特权服务」。
这一句是我在 `index.html` 里定位到的（第 662 行），来自：

```javascript
function openStellarManager(){
  if(typeof Android!=='undefined'&&Android.openShizukuManager){ ... }   // ← 旧名
  ...
  showToast('当前环境无法打开内置特权服务');
}
```

**根因**：迁移时 Java 侧把三处桥接方法改了名，但 `index.html` 里的调用点没跟着改：

| `index.html` 里的调用 | Java 实际方法 | 结果 |
|---|---|---|
| `Android.openShizukuManager` | `openStellarManager` | ❌ 打不开管理器 |
| `Android.isShizukuReady` | `isStellarReady` | ❌ 特权状态恒为「未就绪」 |
| `Android.openShizukuRepo` | `openStellarRepo` | ❌ 打不开源码仓库 |

⚠️ **为什么特别容易漏**：`index.html` 是**加密打包**的
（`app/build.gradle` 的 `encryptAssets` 任务先 JS 混淆再 AES-256-GCM 加密，
产物名伪装成 `assets/index.java`）。改 Java 方法名时根本不会注意到这个 HTML，
静态检查也搜不到明文。

### 修法

1. 三个调用点全部对齐当前 Java 方法名；
2. **并加了一层容错**，避免以后再静默失效：

```javascript
var BRIDGE_OPEN_MANAGER = ['openStellarManager','openShizukuManager'];
var BRIDGE_IS_READY     = ['isStellarReady','isShizukuReady'];
var BRIDGE_OPEN_REPO    = ['openStellarRepo','openShizukuRepo'];
function _hasBridge(obj,names){ ... }
function _bridgeCall(obj,names,args){ ... }
```

按候选名依次尝试，任一个存在即可工作。
**约定：以后新增/改名 Java 桥接方法，把新名字加进对应候选数组的最前面。**

### 真机验证（决定性）

```bash
# 首页点「极强拦截模式」→ 弹出引导弹窗 → 点「去启动」
adb shell input tap 540 2094      # 极强拦截卡片
adb shell input tap 540 1440      # 弹窗里的「去启动」
adb shell "dumpsys activity activities | grep ResumedActivity"
```

点击前：

```
topResumedActivity=... com.youlong.hd/.MainActivity      ← 停在首页，只弹一句错误 Toast
```

点击后（修复生效）：

```
topResumedActivity=... com.youlong.hd/roro.stellar.manager.MainActivity   ✅
```

内置管理器界面正常打开（服务状态 / 无线调试 / 有线 ADB / Root 启动 三个入口齐全）。

## 17. 新增功能：命令安全拦截（2026-10，第五轮）

### 17.1 要解决的问题

内置 Stellar 让本应用自己就是特权内核的管理器。反过来讲：**任何获得
`stellar` 权限的第三方应用，都能借 Stellar 的 shell 身份执行任意命令**，
包括卸载 / 冻结 / 批量停止其它应用，甚至针对本产品自身与配套应用。

设备上安装的「授权应用」里已经出现 `ShizukuRunner`（`com.shizuku.uninstaller`）、
`MT 管理器` 这类会下发卸载命令的工具，所以这不是理论风险。

### 17.2 拦截点：为什么选 `ProcessManager`

全工程只有两个方法会真正 fork 远程进程：

```
embedded/server/.../service/process/ProcessManager.kt
    newProcess(uid, pid, cmd, env, dir)      // 常规 shell
    newPtyProcess(uid, pid, cmd, env, dir)   // PTY（rish 走的通道）
```

它们被下面两条路径**共同**汇聚：

| 调用来源 | 位置 |
| --- | --- |
| Stellar API（`Stellar.newProcess`） | `communication/StellarCommunicationBridge.handleNewProcess` |
| Shizuku 兼容层（`Shizuku.newProcess`） | `shizuku/ShizukuServiceIntercept.newProcess` → `ShizukuCallbackFactory` 的 `callback.newProcess` |

所以在这两个方法里拦一次，就覆盖了全部远程命令执行入口。
**PTY 通道也必须拦**——`rish` 用的是 `newPtyProcess`，
只拦 `newProcess` 会留下一个明显的绕过口子。

### 17.3 三条规则

规则实现在**新增文件**
`embedded/server/.../service/process/CommandInterceptor.kt`：

| 规则 | 触发条件 | 处理 |
| --- | --- | --- |
| ① 保护自家应用 | 命令中出现 `com.youlong.hd` 或 `com.youlong.tool`，且不是纯只读命令 | **直接拒绝** + 弹窗告警 |
| ② 批量停止所有应用 | `am kill-all` / `pkill` / `killall` / `pm ... -a\|--all` / 「先 `pm list packages` 再循环 `am force-stop`」 | **直接拒绝** + 弹窗告警 |
| ③ 卸载 / 冻结 | `pm uninstall` / `disable-user` / `disable` / `suspend` / `hide` / `clear` / `cmd package uninstall\|suspend\|disable` | **弹窗询问用户**，用户拒绝或 30 秒超时即拒绝 |

三处关键设计：

* **只读豁免**：出现写操作特征词就取消豁免，否则连
  `pm list packages | grep com.youlong.hd`（列举已安装应用时的正常写法）
  都会被规则 ① 误伤。
* **管理器自己不受限制**：`isManager` 判定按 uid → 包名解析，
  命中 `MANAGER_APPLICATION_ID`（`com.youlong.hd`）直接放行。
  否则「日常模式」的批量卸载、病毒扫描的处置命令全部会被自己拦住。
  回归用例：`pm list packages | grep com.youlong.hd` = 放行，
  `am force-stop com.youlong.hd` = 拦截（详见 §19 的规则回归）。
* **大小写敏感**：shell 命令是大小写敏感的，`PM UNINSTALL` 不会真的执行，
  因此规则不忽略大小写（避免把无害字符串误判为危险命令）。

### 17.4 与服务端的握手（为什么不用 Binder）

用户确认的结果要回传给正在等待的服务端线程。这里**刻意没有新增 AIDL 方法**：

```
服务端(uid 2000 shell)                      管理器(com.youlong.hd)
  │ 写 /data/local/tmp/stellar_guard/<id>.req
  │─── Intent(COMMAND_GUARD_ACTION) ───────▶ CommandGuardActivity
  │                                          读 .req，显示弹窗
  │◀── 写 <id>.resp（ALLOW/DENY/ACK）───────┘
  │ 轮询到 .resp → 放行 / 抛 SecurityException
```

理由：Stellar 的 AIDL 是上游定义的接口，加方法等于改协议定义与兼容面；
文件握手完全不碰上游接口。握手目录权限 0711、`/data/local/tmp` 本身也是 0711，
普通应用连目录项都看不到。

### 17.5 新增/改动文件一览

新增（非上游文件，不受 MPL 覆盖，但已在 `NOTICE` 留痕）：

* `embedded/server/.../service/process/CommandInterceptor.kt`
* `embedded/server/.../service/process/CommandGuard.kt`
* `embedded/manager/.../authorization/CommandGuardActivity.kt`

改动上游文件（均已按 MPL-2.0 第 3.4 条在文件头写修改声明）：

* `embedded/server/.../service/process/ProcessManager.kt`
* `embedded/server/.../ServerConstants.kt`（新增 `COMMAND_GUARD_ACTION`）
* `embedded/manager/src/main/AndroidManifest.xml`（声明 `CommandGuardActivity`）
* `embedded/manager/src/main/res/values{,-zh-rCN}/strings.xml`（12 条 `command_guard_*`）

---

## 18. 内置管理器界面品牌化（2026-10，第五轮）

### 18.1 默认配色由「跟随壁纸」改为「护盾品牌色」

上游 `Theme.kt` 用的是不带参数的 `lightColorScheme()` / `darkColorScheme()`，
且 `dynamicColor` 默认 `true`，所以界面外观完全取决于系统壁纸。

本工程改为：

* 新增 `ui/theme/BrandColors.kt`，色值取自**宿主网页 UI 的实际用色**
  （`index.html` 内联样式统计：`#007AFF` 主蓝、`#FF3B30` 警示红、
  `#34C759` 成功绿、`#FF9500` 提醒橙、`#8E8E93` 中性灰）。
  注意 `primary` 用的是加深后的 `#0060DF` 而不是 `#007AFF`：
  后者配纯白文字只有约 3.0:1 对比度，达不到 Material3 的 4.5:1 要求。
* `Theme.kt` 默认走品牌色板；`dynamicColor` 默认 `false`。
* 新增 `ColorMode`（`BRAND` / `DYNAMIC`）+ `StellarSettings.COLOR_MODE`，
  在「设置 → 个性化」里二选一，**保留**了上游的动态取色能力（默认不启用）。

### 18.2 视觉改动清单

| 文件 | 改动 |
| --- | --- |
| `ui/components/GuardVisuals.kt`（新增） | 品牌顶栏 `BrandLargeTopAppBar`、首页渐变「护盾卡」`ShieldHeroCard`、盾牌标识 `ShieldMark`、状态胶囊 `ShieldStatusTag`、品牌分节头 `BrandSectionHeader` |
| `ui/features/home/HomeScreen.kt` | 顶栏改品牌顶栏；主状态卡改 `ShieldHeroCard`（渐变底 + 状态点 + 版本/模式两行） |
| `ui/features/apps/AppsScreen.kt` | 顶栏改品牌顶栏（搜索/多选放 `trailing`）；授权状态由纯文字改为「状态点 + 胶囊」 |
| `ui/features/settings/SettingsScreen.kt` | 顶栏改品牌顶栏；「个性化」新增「配色来源」选择器 |
| `ui/features/terminal/TerminalScreen.kt` | 顶栏改品牌顶栏；命令输出区改深色「终端面板」（标题条 + 三个窗口圆点 + 等宽正文） |
| `ui/navigation/components/BottomNavigationManager.kt` | 底栏/侧栏显式品牌配色、选中指示器、顶部分割线、选中项加粗 |
| `ui/theme/Theme.kt` | 品牌色板 + 透明导航栏 + 导航栏图标明暗同步 |
| `ui/theme/ThemePreferences.kt` / `StellarSettings.kt` | `ColorMode` 持久化 |

**只改了内置管理器（`embedded/manager`），宿主 WebView 界面（`index.html`）一行未动。**

---

## 19. 第五轮验证记录（2026-10-01）

### 19.1 构建

```
:app:assembleRelease :app:assembleDebug   →  BUILD SUCCESSFUL in 9m 48s
  build_out/_app/outputs/apk/release/app-release-20261001-091002.apk   38.0 MB
  build_out/_app/outputs/apk/debug/app-debug-20261001-091002.apk       72.1 MB
```

### 19.2 R8 未误裁 / 未改名（关键）

被原生层按字符串加载的类一个字都不能变。核对 `mapping.txt` 与 `usage.txt`：

```
roro.stellar.server.service.process.CommandGuard         -> 同名（未改名）
roro.stellar.server.service.process.CommandInterceptor   -> 同名
roro.stellar.server.service.process.InterceptDecision    -> 同名
roro.stellar.manager.authorization.CommandGuardActivity  -> 同名
usage.txt 中检索 CommandInterceptor/CommandGuard/ShieldHeroCard/BrandColors/
GuardVisuals/BrandLargeTopAppBar/ShieldStatusTag/BrandSectionHeader → 无匹配（无裁剪）
```

release APK 内 dex 检索同样命中：
`classes.dex` → `CommandInterceptor, CommandGuard, GuardVisualsKt`；
`classes3.dex` → `InterceptDecision, ShieldHeroCard, BrandColorsKt,
command_guard_blocked_title, color_mode_brand`。

### 19.3 安装与界面验证（vivo V2133A / Android 14 / 无 root）

```
adb install -r 游龙安全护盾-9.0.1-release.apk   →  Success
```

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 桌面图标仍然隐藏 | `cmd package resolve-activity -c LAUNCHER com.youlong.hd` | `No activity found` ✅ |
| 拦截对话框已注册 | `cmd package resolve-activity -a com.youlong.hd.intent.action.COMMAND_GUARD com.youlong.hd` | `com.youlong.hd/roro.stellar.manager.authorization.CommandGuardActivity` ✅ |
| 内置管理器可打开 | `am start -n com.youlong.hd/roro.stellar.manager.MainActivity` | 前台为 `roro.stellar.manager.MainActivity` ✅ |
| 无崩溃 | `adb logcat -b crash` | 无 `com.youlong.hd` 相关记录 ✅ |
| 品牌配色生效 | 首页截图 | 状态卡为浅蓝渐变、按钮为品牌蓝（不是系统动态色） ✅ |
| 品牌顶栏生效 | 首页截图 | 「ADB护盾版」大标题左侧有盾牌标识 ✅ |
| 状态胶囊生效 | 授权应用页 | 「允许」= 浅蓝胶囊、「询问」= 中性灰胶囊（原来是无样式纯文字） ✅ |
| 底栏品牌化生效 | 首页截图 | 选中项为浅蓝指示器 + 品牌蓝图标/文字 ✅ |
| 「配色来源」已上线 | 设置 → 个性化 | 出现「配色来源 / 护盾品牌色 / 跟随壁纸」 ✅ |

`uiautomator dump` 关键文本（设置页 / 个性化展开后）：

```
text="个性化" / text="应用主题" / text="浅色" / text="深色" / text="跟随系统"
text="配色来源" / text="护盾品牌色" / text="跟随壁纸" / text="默认启动页"
```

> 截图里任务顶部那条青色带「游龙安全护盾」是 **vivo 系统给任务画的外观条**
> （取本应用图标配色），不是管理器界面的一部分；
> 管理器自己的界面从盾牌顶栏开始，配色为品牌蓝。
>
> 补充（第六轮排查结论，有实测证据）：
> 该青色带 `#0777AA` 恰好是宿主 `colors.xml` 里的 `ic_launcher_background`
> / `colMain_1` / `petrol`；它位于 y=96..264（状态栏之下），
> 在我们的视图树（`dumpsys activity top`）和 Compose 里都搜不到对应文本，
> 且 `dumpsys window` 显示它属于另一个窗口层 —— 我们自己的三个 Activity
> 只有 `roro.stellar.manager.MainActivity` 是 `isOnScreen=true`（且 `mHasSurface=true`），
> 宿主与 `com.youlong.tool` 都是 `mHasSurface=false`。
> 结论：这是 **vivo 系统/桌面**按应用图标底色与 `app_name` 画的顶部标识，
> 应用侧无法通过代码移除；第六轮已把**我们自己界面里**所有重复的
> App 名与 Stellar 图标清掉，界面内不再出现该文字。

### 19.4 拦截规则的回归验证

无法在设备上直接造一个「第三方授权应用」来端到端触发（那需要一个额外的
测试 APK，且要用户手动授权），因此改用**从源码抽取正则、离线跑判定**的方式，
保证验证的是线上同一份字面量：

```
powershell -File E:\anquan\tools\verify_command_rules.ps1
  → TOTAL: PASS=23  FAIL=0
```

覆盖的关键用例（节选）：

| 命令 | 期望 | 实际 |
| --- | --- | --- |
| `pm list packages -3` | 放行 | ALLOW |
| `pm list packages \| grep com.youlong.hd` | 放行（只读豁免） | ALLOW |
| `dumpsys package com.youlong.hd` | 放行（只读豁免） | ALLOW |
| `am force-stop com.youlong.hd` | 拦截 | BLOCK-SELF |
| `pm uninstall com.youlong.tool` | 拦截 | BLOCK-SELF |
| `pm disable-user --user 0 com.youlong.hd` | 拦截 | BLOCK-SELF |
| `am   force-stop   com.youlong.hd`（多空格） | 拦截 | BLOCK-SELF |
| `pm list packages -3 \| while read p; do am force-stop $p; done` | 拦截 | BLOCK-STOPALL |
| `am kill-all` / `pkill -f x` / `killall -9 x` | 拦截 | BLOCK-STOPALL |
| `pm disable-user --user 0 -a` | 拦截 | BLOCK-STOPALL |
| `pm uninstall --user 0 com.example.evil` | 询问 | CONFIRM |
| `pm disable-user/suspend/hide/clear ...` | 询问 | CONFIRM |
| `am force-stop com.example.evil`（单个第三方） | 放行 | ALLOW |
| `settings put global adb_enabled 1` | 放行 | ALLOW |

### 19.5 ⚠️ 仍然存在的绕过面（必须知道）

1. **Shizuku 兼容层的 `transactRemote` 没有设防。**
   Shizuku 兼容层开着时（设置里默认开），第三方应用可以不走 shell，
   而是直接 `transactRemote` 调用 `ActivityManager.forceStopPackage` 之类的
   系统服务事务，绕过 §17 的 shell 命令拦截。
   现实中的冻结类应用（冰箱 / 空调狗 / 黑阈）主要走 `newProcess`，
   所以本次按用户要求覆盖的是 shell 命令面；若要堵这条，需要按事务码
   或目标服务另做一层过滤（会动到上游的 `ShizukuServiceIntercept`）。
2. **确认框的答复文件理论上可被「已经具备 shell 权限的应用」抢先写入。**
   前提是该应用已经能写 `/data/local/tmp`（即已经拿到 shell），
   属于权限提升之后的次级风险；普通第三方应用因 0711 权限连目录都进不去。
3. **规则是字符串匹配**，不做 shell 语法解析。把危险命令拆成多段、
   用变量拼接、或 base64 解码后再执行，可以绕过判定。
   本轮按需求实现的是「明显危险命令」拦截，不是沙箱。

---

## 20. 第七轮：日常模式拦截时同步收窄无障碍服务（2026-10）

### 20.1 需求与落点

需求：**日常模式只要触发拦截**，就在那个「循环停止第三方应用」的过程中，
顺带把无障碍服务收窄到只保留本应用自己的服务。

落点（`app/src/main/java/com/youlong/hd/ProtectService.java`）：

| 位置 | 说明 |
| --- | --- |
| `showDailyThreatStage()` 里 `dailyForceStopLoop = true;` 之后 | 立刻起 `daily-a11y-harden` 线程先跑一次，不等循环第一轮 |
| 同一个 threat 阶段的 `while (dailyForceStopLoop && ...)` 循环体内 | 每轮再跑一次（1500ms 一轮，幂等，可覆盖「被停的应用又重新注册无障碍」） |
| 新增方法 `runDailyAccessibilityHarden()` | 拼脚本 → 走 `StellarUtils.runCommand`（有 Stellar 时）或 `execShell`（降级） |

触发链路：`ProtectService` 检测到威胁 → `isDailyMode` 分支（第 807 行附近）
→ `showDailyOverlay` → `showDailyThreatStage` → 循环开始。

### 20.2 脚本（用户给定 + 本工程加固）

用户给的原始脚本：

```sh
T=com.youlong.hd; C=$(settings get secure enabled_accessibility_services); \
N=$(echo "$C" | tr ':' '\n' | grep "^$T/" | tr '\n' ':' | sed 's/:$//'); \
settings put secure enabled_accessibility_services "$N"; \
settings put secure accessibility_enabled 1; \
echo "当前启用：$(settings get secure enabled_accessibility_services)"
```

本工程的三处加固（语义完全一致）：

1. `T=<getPackageName()>`，不写死 `com.youlong.hd`；
2. 两处 `settings` 套 `timeout 5`，防止个别 ROM 上 `settings` 卡住拖死循环；
3. 整体走 `StellarUtils.runCommand(script, 12000)`，异常只记日志。

### 20.3 ⚠️ 踩到并修掉的一个真坑：`grep -F -x` 不成立

最初的加固版把过滤写成 `grep -F -x "$T/*"`，想「字面匹配 + 整行相等」。
**这是错的**：

* `-F`（fixed strings）下 `*` 是**字面星号**，不是 glob，也不会被 shell 展开
  （因为它在双引号里）；
* 于是模式等于 `com.youlong.hd/*`，要求那一行**以星号结尾**，
  而真实内容是 `com.youlong.hd/com.youlong.hd.AdSkipService` —— 永远匹配不到；
* 后果：`N` 恒为空串，`settings put` 会把**无障碍服务列表整个清空**，
  连本应用自己的 `AdSkipService` 也会被关掉。

真机实测（`adb shell`）：

```
C=[com.youlong.hd/com.youlong.hd.AdSkipService:com.evil.ransom/...:com.other.app/...]
svc=[com.youlong.hd/com.youlong.hd.AdSkipService]
svc=[com.evil.ransom/com.evil.ransom.A11y]
svc=[com.other.app/com.other.app.Svc]
grep 无匹配          <-- grep -F -x "$T/*" 的失败
```

已改回用户原语义的 `grep "^$T/"`（正则、前缀匹配）。修正后用
**与 App 里完全同一份表达式**跑了 7 组用例，全部通过：

```
sh tools/verify_daily_a11y.sh
PASS  自身服务 + 两个第三方            got=[com.youlong.hd/com.youlong.hd.AdSkipService]
PASS  settings 返回 null（未启用过）    got=[]
PASS  只有第三方服务                    got=[]
PASS  自身服务在中间                    got=[com.youlong.hd/com.youlong.hd.AdSkipService]
PASS  自身有多个服务（多进程）          got=[com.youlong.hd/...AdSkipService:com.youlong.hd/...OtherSvc]
PASS  前缀相似但不属于本应用            got=[com.youlong.hd/com.youlong.hd.AdSkipService]
PASS  空串输入                          got=[]
TOTAL: PASS=7 FAIL=0
```

（其中「前缀相似」那条专门盯 `com.youlong.hd2/...` 不能被误保留，
因为 `grep "^$T/"` 里 `$T` 的点号是正则元字符 —— 这里靠结尾的 `/`
把它挡住了。）

### 20.5 ⚠️ 一个与实现无关、但会影响手工验证的坑

用 `adb shell` 手工跑这段脚本时，**必须把 `\n` 写成真实换行**：

```
adb shell 'T=com.youlong.hd; C="a:b"; N=$(echo "$C" | tr ":" "\n" | ...)'
                                                  ^^^^^^
```

PowerShell / cmd 传给 adb 时不会把 `\n` 变成换行，于是
`tr ":" "\n"` 实际是「把 `:` 逐个换成字符 `n`」——输出会变成
`com.youlo:g.hd/...`（冒号被换成了 n），过滤结果自然为空。
这是**验证命令的转义问题，不是脚本的问题**；App 里由 Java 字符串
`"\\n"` 交给 shell，是真实的换行符，行为与上面的 7 组用例一致。

### 20.6 验证状态

* 代码已进 release APK：在 `classes3.dex` 里检索到
  `runDailyAccessibilityHarden` / `daily-a11y-harden` /
  `enabled_accessibility_services`，说明未被 R8 裁掉；
* `:app:assembleRelease` **BUILD SUCCESSFUL**，
  最终产物 `build_out/_app/outputs/apk/release/app-release-20261001-101317.apk`
  （37.9 MB），**已用 `pm install` 装到设备**
  （`lastUpdateTime=2026-10-01 10:18:12`）；
* 脚本逻辑 7/7 用例通过（见 20.4）；
* ⚠️ **端到端仍未跑完**：验证过程中设备与 adb 反复断开
  （`offline` → `no devices/emulators found`），
  没能完成「切到日常模式 → 触发拦截 → 检查无障碍列表」这一步。
  设备稳定后需要：
  1. `adb shell settings get secure enabled_accessibility_services`
     —— 触发拦截前先记下原值；
  2. 切到日常模式 → 触发一次拦截（覆盖层弹出）；
  3. 再查一次，确认只剩 `com.youlong.hd/...`；
  4. `adb logcat | grep "日常模式：无障碍服务已收窄"` 应能看到每轮日志。

---

## 21. 第六轮：界面去品牌化 / 删除更新模块 / 开源许可入口（2026-10）

### 21.1 七项改动一览

| # | 需求 | 落点 |
| --- | --- | --- |
| 1 | 去掉最上面那条「游龙安全护盾」 | 见 21.2 —— 实测结论：那是 vivo 系统画的，应用侧移不掉；本轮已清掉**我们界面里**所有重复的 App 名与图标 |
| 2 | 品牌名「ADB护盾版」→「游龙安全ADB」 | 10 个 `strings.xml` + 3 个 Kotlin 文件（206 + 4 处） |
| 3 | 删除更新模块 | `SettingsScreen` 的 UpdateCard / NewVersionDialog / UpdateSourceDialog + 7 个状态变量 + `util/update/` 三个文件 |
| 4 | 「项目声明」文案改掉 | `project_declaration_content` 改为逐项署名 Shizuku(Apache-2.0) / Stellar(MPL-2.0)；指向 Stellar 仓库的按钮文字由「ADB护盾版」改回「Stellar」 |
| 5 | 去除 Stellar 图标 | `GuardVisuals` / `RequestPermissionActivity` / `AdbPairingService`(9 处) / `BootStartNotifications` / `CommandShortcutManager`；新增 `ic_notification_shield.xml` |
| 6 | 去掉导航栏里的「命令」 | `MainScreen.Terminal` 枚举项 + `MainActivity` 路由 + `StartPage.TERMINAL` + 删除 `ui/features/terminal/` 目录 |
| 7 | 应用内声明开源协议 | 新增 `OpenSourceLicensesActivity` + 「设置 → 项目声明 → 开源许可」入口 + `:manager` 模块的 `res/raw/open_source_licenses.txt` |

### 21.2 那条青色带到底是什么（有实测证据）

需求 1 的排查过程与结论：

```
像素采样（1080x2376）：
  y=0..96    #F6F7FB  <- 状态栏区域（浅色）
  y=96..264  #0777AA  <- 青色带
  y=264..    #F6F7FB  <- 管理器自己的界面

#0777AA 恰好是宿主 colors.xml 里的 ic_launcher_background / colMain_1 / petrol

dumpsys activity top         -> 我们的视图树里搜不到「游龙」/「护盾」/「ADB」任何文本
dumpsys SurfaceFlinger --list -> 该区域只有 549e3c7 .../roro.stellar.manager.MainActivity#4088
dumpsys window windows        -> 三个 Activity 里只有 manager.MainActivity 是
                                 isOnScreen=true / mHasSurface=true；
                                 宿主 MainActivity 与 com.youlong.tool 都是
                                 mHasSurface=false / mObscured=true
```

结论：这是 **vivo（Funtouch / OriginOS）系统/桌面**给前台任务画的顶部标识条，
取的是**应用图标底色**（`ic_launcher_background` = `#0777AA`）与 `app_name`。
它不属于我们的窗口层，**应用侧无法用代码移除**。

本轮能做的、也做了的是：把我们自己界面里所有重复出现 App 名与 Stellar 图标的地方
全部清掉（品牌顶栏不再带图标）。

> 如果希望连那条系统带一起消失，唯一可行方向是改
> `app/src/main/res/values/colors.xml` 里的 `ic_launcher_background`
> （图标底色）—— 但那会同时改变**桌面图标**的配色，属外观取舍，
> 需要你确认后我再动。

### 21.3 验证记录（真机，vivo V2133A / Android 14 / 无 root）

```
adb install -r 游龙安全护盾-9.0.1-release.apk
产物: app-release-20261001-094458.apk 37.9 MB
      app-debug-20261001-094458.apk   71.9 MB
```

| 项目 | 结果 |
| --- | --- |
| 无崩溃 | `adb logcat -b crash` 无 `com.youlong.hd` 记录 ✅ |
| 顶栏标题 | `uiautomator dump` 出现 `游龙安全ADB`，不再出现 `ADB护盾版` ✅ |
| 底栏只剩 3 项 | 文本为 `启动 / 授权应用 / 设置`，`命令` 已消失 ✅ |
| 首页护盾卡 | 截图为 Material 盾牌图标（不再是 Stellar 图标）✅ |
| 通知图标 | `AdbPairingService` 9 处 + `BootStartNotifications` 1 处已改为 `ic_notification_shield` ✅（构建期校验，运行时通知未逐一触发） |
| 授权应用页 | 状态胶囊（允许/询问/拒绝）正常 ✅ |
| 设置页 | `个性化 / 开机启动 / Shizuku 兼容层 / 无线调试提权 su / 降权激活 / 进程守护` —— 不再有「检查更新」✅ |

### 21.4 许可证文本现在有两份副本（务必同步）

```
app/src/main/res/raw/open_source_licenses.txt                <- 宿主 MainActivity 的入口读它
embedded/manager/src/main/res/raw/open_source_licenses.txt   <- 管理器 OpenSourceLicensesActivity 读它
```

两份必须**逐字一致**（当前都是 48,476 字节）。原因是资源 ID 是**按模块**在编译期生成的：
`:manager` 编译时看不到 `:app` 的 `R.raw`，所以两个入口各自需要一份。
各自的 `res/raw/keep.xml` 用 `tools:keep` 防止被 `shrinkResources` 裁掉。

---

### ~~排错提示：首页有个免责声明弹窗会挡住点击~~（已失效，2026-10 第 12 轮删除）

> ⚠️ 本条已作废。原来每次进入应用都会弹一个「提示 / 目前**格机脚本 sh 文件**防不了，
> 建议大家日常尽量不要开……概不负责」的遮罩弹窗（`showDisclaimer()`，
> `uiautomator` 里是 `disclaimerBtn`），用户要求去掉，已在第 12 轮
> **整块删除**（函数体 + `init()` 里的调用）。
> 现在进入应用不会再弹任何遮挡层，自动化脚本也不需要先点掉它了。
> 详见 `ROUND12_改动汇总.md`。

（以下为历史记录，仅供参考）

自动化测试时如果点击「没反应」，先确认屏幕上没有那个
「提示 … 我知道了」的遮罩弹窗（首次启动会出现）。
先 `input tap` 点掉它，再去点其它元素。
`uiautomator dump` 会显示 `disclaimerBtn`（文本「我知道了」），
它的 `bounds` 就是可点击区域。