# 版权声明 / Copyright Notice

**游龙安全护盾 (YouLong Security Shield)**

Copyright (C) 2026 游龙. 保留所有权利（第三方组件除外）。
Copyright (C) 2026 YouLong. All rights reserved, except for third-party components.

---

## 1. 本产品与上游项目的关系

本产品是一个独立发布、独立维护的 Android 应用，**基于 Stellar 修改而来**。
“壳子原本就是 Stellar”这一事实，本产品明确承认并保留，不作隐瞒：

| 项目 | 上游 | 许可证 | 版权归属 |
|---|---|---|---|
| Stellar / Stellar-API | https://github.com/roro2239/Stellar | Mozilla Public License 2.0（修改部分） | Copyright (C) roro2239 / RoRo Studio |
| Shizuku / Shizuku-API | https://github.com/RikkaApps/Shizuku | Apache License 2.0 | Copyright (C) RikkaApps |
| AOSP adb 协议实现 | https://android.googlesource.com/platform/packages/modules/adb | Apache License 2.0 | Copyright (C) The Android Open Source Project |
| 游龙安全护盾自有代码、界面、文档 | 本产品 | Apache License 2.0（自有部分） | Copyright (C) 2026 游龙 |

**重要声明：**

1. 本产品**不是** Stellar 官方产品，也**不是** Shizuku 官方产品；
2. 本产品与 roro2239 / RoRo Studio、RikkaApps 之间**不存在隶属、合作、代理或背书关系**；
3. “Stellar”“Shizuku”等名称、标识、商标归各自权利人所有；本产品仅在描述代码来源、兼容性与许可归属时使用这些名称；
4. 本产品未使用 Stellar 或 Shizuku 作为应用名称，也未使用其官方 applicationId；
5. Java/Kotlin 包名 `roro.stellar.*`、`com.stellar.*`、`moe.shizuku.*` 仅因 AIDL、JNI 与协议兼容而保留，不代表本产品是官方版本；
6. 本产品不主张上游项目的作者身份、商标权或官方地位。

---

## 2. 许可证适用

- 本产品**自有代码、界面与文档**：Apache License 2.0，全文见 `LICENSE` / `LICENSES/Apache-2.0.txt`；
- **Stellar 修改部分**：Mozilla Public License 2.0，全文见 `LICENSES/MPL-2.0.txt`；
- **Stellar 内含的 Shizuku 原始代码**：Apache License 2.0，版权归 RikkaApps；
- 其他第三方组件：见 `THIRD_PARTY_NOTICES.md` 与 `NOTICE`。

MPL-2.0 是**文件级**弱 copyleft：义务仅覆盖 MPL 所覆盖的具体文件，不要求本产品整体开源。本产品未删除或修改任何原始版权、许可声明；对 Stellar 文件的修改均在被修改文件顶部保留显著的中文修改说明，并在 `NOTICE` 中逐项汇总。

---

## 3. 源代码获取方式

MPL-2.0 第 3.1 / 3.2 条要求以可执行形式分发时向接收者提供 Source Code Form 的获取方式。本产品按以下方式履行：

1. 随对应版本发布**完整源码包**，文件名形如：

   ```text
   游龙安全护盾-V72-9.9.41-完整源码.zip
   ```

2. 源码包包含：

   - `embedded/`：Stellar 管理器、服务端、API、Provider、Shizuku 兼容层等 Kotlin/AIDL 源码；
   - `app/src/main/cpp/stellar/`：Stellar 原生层源码；
   - `LICENSES/`、`LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md`、本文件；
   - 构建所需 Gradle 配置与说明。

3. 如果只获得了 APK，可向 APK 的发布者索取与该 APK 版本对应的源码包；
4. 源码包与 APK 的版本号必须对应；本文件对应版本为 **V72 / 9.9.41 / 1234567990**。

---

## 4. Shizuku 兼容层现状说明

早期版本曾尝试在启动内置特权时结束第三方 Shizuku 服务进程（`shizuku_server` / `shizuku_daemon`）。**该行为自 V63 起已彻底移除。**

当前版本：

- 不结束、不冻结、不强制停止第三方 Shizuku 服务；
- 不修改第三方 Shizuku 的 APK 或数据；
- 不再通过击杀 Shizuku 进程来接管其客户端；
- 仅在本产品自身的权限与审计框架内提供 Shizuku 兼容接口；
- Shizuku 相关版权与许可证仍归 RikkaApps，适用 Apache-2.0。

早期 NOTICE 中与此不一致的描述，以本文件和当前版本 NOTICE 为准。

---

## 5. 免责声明

本产品按“现状”提供，不附带任何明示或默示担保，包括但不限于适销性、特定用途适用性和不侵权担保。使用本产品造成的任何直接或间接损失，由使用者自行承担；法律强制要求不得排除的责任除外。

---

## 6. 完整声明文件

| 文件 | 内容 |
|---|---|
| `LICENSE` | 本产品自有部分 Apache License 2.0 全文 |
| `LICENSES/Apache-2.0.txt` | Apache License 2.0 全文 |
| `LICENSES/MPL-2.0.txt` | Mozilla Public License 2.0 全文 |
| `NOTICE` | 上游署名、逐文件修改清单、源码获取方式 |
| `THIRD_PARTY_NOTICES.md` | 全部第三方组件、版本与许可证清单 |
| `版权声明.md` | 本文件 |

应用内查看入口：**内置特权 → 设置 → 项目声明 / 开源许可**。
