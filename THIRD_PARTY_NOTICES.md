# 第三方开源组件清单 / Third-Party Notices

## 版权与独立性声明 / Copyright & Independence

- **游龙安全护盾**自有代码、界面与文档：Copyright (C) 2026 游龙，Apache License 2.0。
- 本产品的内置特权框架**基于 Stellar 修改而来**。Stellar / Stellar-API：
  Copyright (C) roro2239 / RoRo Studio，修改部分适用 MPL-2.0。
- 经 Stellar 内含的 Shizuku / Shizuku-API：Copyright (C) RikkaApps，
  适用 Apache License 2.0。
- 本产品是独立修改版，**不是** Stellar 或 Shizuku 官方产品，与上述作者/组织
  不存在隶属、合作、代理或背书关系；“Stellar”“Shizuku”等名称、标识、商标
  归各自权利人所有，仅用于描述代码来源、兼容性与许可归属。
- 本产品未使用 Stellar/Shizuku 作为应用名称，未使用其官方 applicationId；
  `roro.stellar.*` / `com.stellar.*` / `moe.shizuku.*` 包名仅因 AIDL、JNI、
  协议兼容而保留，不代表官方版本或作者身份。
- 完整版权声明见 `COPYRIGHT.md` / `版权声明.md`；对应源码包随版本发布，
  文件名形如 `游龙安全护盾-V72-9.9.41-完整源码.zip`，包含 `embedded/` 与
  `app/src/main/cpp/stellar/` 等 MPL-2.0 覆盖源码。

本文件列出「游龙安全护盾」APK 中实际包含的全部第三方组件及其许可证。

> 生成方式：遍历所有 `build.gradle` 的 `implementation` / `api` 依赖，
> 再从 Gradle 缓存的 POM 中读取各组件**自己声明的**许可证，避免凭印象填写。
> 另有若干**源码级融合**的组件（Stellar、经 Stellar 内含的 Shizuku、AOSP adb、
> BoringSSL、kyant backdrop），它们在 `build.gradle` 中不可见，单独列在第 2 节。

---

## ⚠️ 结论先行（2026 年变更）

**本产品自 2026 年起包含 MPL-2.0（Mozilla Public License 2.0）许可的代码。**

这一变化来自一次内核替换：内置的特权框架由上游 **Shizuku** 整体换成了
**[Stellar](https://github.com/roro2239/Stellar)** —— 一个以 Shizuku 为上游的
深度定制分支。Stellar 的**修改部分**采用 MPL-2.0，其**内含的 Shizuku 原始代码**
仍为 Apache-2.0。

需要明确的性质：

* MPL-2.0 是**文件级（弱）copyleft**，不是 GPL 那样的整体传染性许可。
  它的义务只落在「被 MPL 覆盖的那些文件」上，**不要求本产品整体开源**。
* 本产品对 MPL 覆盖代码履行的核心义务是：**随产品提供这些文件的源码**。
  本产品的 `embedded/`（Stellar 的 Kotlin 源码）与
  `app/src/main/cpp/stellar/`（Stellar 的原生源码）就是这些源码，随仓库分发。
* 本产品**未**包含任何 GPL / LGPL / AGPL 许可的代码。
* 未删改任何版权与许可声明；每一处改动都在被改动的文件顶部有显著的中文
  修改说明注释，并逐条汇总在根目录 `NOTICE` 中。

---

## 1. 二方库依赖（Maven 依赖，来自 `build.gradle`）

### 1.1 Apache License 2.0

| 组件 | 版本 | 说明 |
|---|---|---|
| `androidx.appcompat:appcompat` | 1.7.1 | AndroidX |
| `androidx.core:core` / `core-ktx` | 1.13.1 / 1.17.0 | AndroidX |
| `androidx.annotation:annotation` | 1.9.1 | AndroidX |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | AndroidX |
| `androidx.multidex:multidex` | 2.0.1 | AndroidX |
| `androidx.browser:browser` | 1.8.0 | AndroidX |
| `androidx.fragment:fragment-ktx` | 1.8.9 | AndroidX |
| `androidx.recyclerview:recyclerview` | 1.4.0 | AndroidX |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` / `-livedata-ktx` / `-viewmodel-compose` | 2.10.0 | AndroidX |
| `androidx.preference:preference-ktx` | 1.2.1 | AndroidX |
| `androidx.activity:activity-compose` | 1.12.3 | AndroidX |
| `androidx.navigation:navigation-compose` | 2.9.7 | AndroidX |
| `androidx.compose.*`（ui / ui-graphics / ui-tooling-preview / foundation / material3 / material-icons-extended / runtime-livedata） | BOM 2026.01.01 | AndroidX Compose |
| `androidx.room:room-runtime` / `room-ktx` / `room-compiler` | 2.7.1 | AndroidX Room（Stellar 管理器本地数据库） |
| `androidx.work:work-runtime-ktx` | 2.10.0 | AndroidX WorkManager |
| `androidx.core:core-splashscreen` | 1.0.1 | AndroidX |
| `com.google.android.material:material` | 1.13.0 | Material Components |
| `com.google.code.gson:gson` | 2.13.1 | Google |
| `org.conscrypt:conscrypt-android` | 2.5.2 | Google |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` / `-android` | 1.10.2 | JetBrains |
| `com.github.topjohnwu.libsu:core` | 6.0.0 | POM 未声明 license 字段；上游仓库为 Apache-2.0 |
| `com.squareup.okhttp3:okhttp`（及 okio） | 4.12.0 | Square（Stellar 的应用内更新检查） |
| `org.lsposed.hiddenapibypass:hiddenapibypass` | 6.1 | LSPosed |
| `org.lsposed.libcxx:libcxx` | 27.0.12077973 | LSPosed，NDK libc++ 预编译（prefab） |
| `io.github.vvb2060.ndk:boringssl` | 20250114 | vvb2060，BoringSSL 静态库（prefab） |
| `me.zhanghai.android.appiconloader:appiconloader` | 1.5.0 | Zhang Hai |
| `dev.rikka.rikkax.parcelablelist:parcelablelist` | 2.0.1 | Rikka |
| `dev.rikka.rikkax.core:core-ktx` | 1.4.1 | Rikka |
| `dev.rikka.rikkax.*`（material / recyclerview / insets / layoutinflater / widget / preference / lifecycle 等） | 见 `manager.versions.toml` | Rikka |

### 1.2 MIT License

| 组件 | 版本 | 来源 |
|---|---|---|
| `dev.rikka.hidden:compat` | 4.4.0 | https://github.com/RikkaW/HiddenApi/blob/master/LICENSE |
| `dev.rikka.hidden:stub` | 4.4.0 | https://github.com/RikkaW/HiddenApi/blob/master/LICENSE |
| `dev.rikka.tools.refine:runtime` | 4.4.0 | https://github.com/RikkaApps/HiddenApiRefinePlugin/blob/main/LICENSE |

> `dev.rikka.hidden:stub` 仅以 `compileOnly` 引入（只用于编译期类型检查，
> 不进 APK）；`compat` 与 `refine:runtime` 会打包进 APK。

### 1.3 Bouncy Castle Licence（MIT 系）

| 组件 | 版本 | 来源 |
|---|---|---|
| `org.bouncycastle:bcpkix-jdk18on` | 1.80 | https://www.bouncycastle.org/licence.html |

### 1.4 其他宽松许可（JitPack）

| 组件 | 版本 | 来源 |
|---|---|---|
| `com.github.Kyant0:Capsule` | 2.1.0 | https://github.com/Kyant0/Capsule |
| `com.github.jeziellago:compose-markdown` | 0.5.4 | https://github.com/jeziellago/compose-markdown |

---

## 2. 源码级融合的组件（`build.gradle` 中看不到）

| 组件 | 位置 | 许可证 | 版权 |
|---|---|---|---|
| **Stellar（修改部分）** | `embedded/{manager,server,aidl,shared,api,provider,userservice,shizuku-aidl,shizuku-api}`、`app/src/main/cpp/stellar/` | **MPL-2.0** | roro2239 / RoRo Studio |
| Shizuku（经 Stellar 内含） | 同上（Stellar 内部保留的 Shizuku 兼容层与 AIDL） | Apache-2.0 | RikkaApps |
| AOSP adb（协议实现思路，经 Stellar） | `app/src/main/cpp/stellar/adb_pairing.cpp` | Apache-2.0 | The Android Open Source Project |
| BoringSSL（头文件 + 静态库，prefab） | `io.github.vvb2060.ndk:boringssl:20250114` | OpenSSL License + ISC + BSD 系 | OpenSSL Project / Eric Young / Google |
| kyant backdrop（毛玻璃） | `app/src/main/kotlin/com/kyant/backdrop/` | Apache-2.0 | Kyant |
| NDK libc++ 静态库（prefab） | `org.lsposed.libcxx:libcxx:27.0.12077973` | Apache-2.0（LLVM 例外条款） | LLVM Project / The Android Open Source Project |

各文件头部的原始版权与许可声明**均原样保留、未作删改**。
新增/修改的内容均带有显著的中文修改说明注释（MPL-2.0 第 3.4 条 /
Apache-2.0 第 4(b) 条）。

Stellar 部分的**完整逐文件修改清单**见同目录下的 `NOTICE` 第 1 节。

---

## 3. 许可证合规动作对照表

### 3.1 MPL-2.0（Stellar 修改部分）

| 条款 | 要求 | 本产品如何满足 |
|---|---|---|
| 3.1 | 以源码形式分发时须继续适用 MPL-2.0，并告知接收者如何获取许可证副本 | MPL-2.0 覆盖的源码全部随仓库分发（`embedded/`、`app/src/main/cpp/stellar/`）；许可证全文见 `LICENSES/MPL-2.0.txt`，并打包进 APK（`res/raw/open_source_licenses.txt` 第二部分） |
| 3.2 | 以可执行形式分发时须同时提供源码的获取方式 | 同上；本产品以源码仓库 + APK 两种形式分发，源码获取方式在 `NOTICE` 第 8 节说明 |
| 3.4 | 不得删改许可与版权声明；被修改文件须带显著修改声明 | 原始声明逐字保留；每一处改动都在文件顶部有显著中文修改说明，并汇总在 `NOTICE` 第 1 节 (D) |
| 3.3 | Larger Work 可按自己的条款分发 | MPL 覆盖代码与本产品自有代码仅为文件级组合，本产品整体仍按自己的条款分发 |
| Exhibit B | 若上游标记为「与 Secondary License 不兼容」须声明 | 上游 Stellar **未**作此标记，本产品亦未添加 |

### 3.2 Apache-2.0（自有代码 + 经 Stellar 内含的 Shizuku）

| 条款 | 要求 | 本产品如何满足 |
|---|---|---|
| 4(a) | 向接收者提供许可证副本 | 根目录 `LICENSE`（= `LICENSES/Apache-2.0.txt`）；并打包进 APK（`res/raw/open_source_licenses.txt` 第一部分），用户可在应用内「高级设置 → 开源许可」查看、复制 |
| 4(b) | 被修改的文件需带显著修改声明 | 每个改动过的融合源文件顶部都有中文修改说明注释 |
| 4(c) | 保留所有版权、专利、商标、署名声明 | 融合源码文件头部的原始声明逐字保留 |
| 4(d) | 若上游存在 NOTICE 文件则须一并分发 | 上游 Shizuku / Shizuku-API / Stellar / AOSP adb 仓库中**均不存在** NOTICE 文件（已逐一核实）；本产品仍主动提供 `NOTICE` 以完整说明署名 |

---

## 4. 上游的额外使用限制（非许可证条款）

### 4.1 Shizuku（经 Stellar 内含，仅相关部分）

Shizuku 上游 README 明确禁止：

* 使用 `Shizuku` 作为应用名称
* 使用 `moe.shizuku.privileged.api` 作为 applicationId
* 声明任何 `moe.shizuku.manager.permission.*` 权限

**本产品三项均未违反**：应用名为「游龙安全护盾」，applicationId 为
`com.youlong.hd`，自定义权限为 `com.youlong.hd.permission.API_V23` 与
`com.youlong.hd.permission.MANAGER`，且未声明任何 `moe.shizuku.*` 权限。

### 4.2 Stellar

Stellar 上游 README 与 `AGENTS.md` **未声明任何**与上述类似的额外使用限制
（无应用名、applicationId 或权限命名的禁止性约定）。
本产品仍遵循同一套自律约束：应用名、applicationId、权限名均为本产品自有，
不使用 `roro.stellar.manager` 作为 applicationId，也不声明
`roro.stellar.*` 权限。

> Java/Kotlin **包名** `roro.stellar.*` / `com.stellar.*` / `moe.shizuku.server.*`
> 为兼容上游协议而保留不变（原生启动器与 JNI 按硬编码类名工作），
> 这不涉及商标使用，仅为标识代码出处。

---

## 5. Stellar-API 的许可证标注冲突（已知事项，需留意）

在为本次迁移调研时发现上游 `Stellar-API` 仓库存在一处**自相矛盾**的许可标注：

* 仓库 `README.md` 的「许可证」一节称修改部分为
  [Mozilla Public License 2.0](LICENSE)，原始 Shizuku 代码为 Apache-2.0；
* 但**仓库内不存在任何 LICENSE 文件**（README 指向的 `LICENSE` 是死链）；
* 而该仓库 `publish.gradle` 的 Maven POM 元数据却声明
  `GNU General Public License v3.0`。

**本产品如何规避该风险**：本次迁移**完全不使用** Stellar-API 的 JitPack 制品，
所有 `roro.stellar.*` 客户端代码均以**源码形式**取自上游仓库，并以仓库内
实际存在的许可证文件与文件头声明为准（即 MPL-2.0 / Apache-2.0）。
因此本产品不会因该 POM 的 GPL-3.0 标注而引入 GPL 义务。

> ⚠️ 提示：若日后有人把依赖改成
> `implementation 'com.github.roro2239:Stellar-API:<版本>'`，
> 就会把那条例外的 GPL-3.0 声明带进本产品，需要重新评估合规性。
