# 开源协议合规审计报告

- 审计对象：游龙安全护盾（`com.youlong.hd`）v9.0.1
- 内置开源内核：**Stellar**（https://github.com/roro2239/Stellar，Shizuku 分支）
- 审计日期：2026-10-01
- 审计方式：**全部结论均由脚本/工具实测得出**，不是凭印象

---

## 一、涉及的开源许可

| 组件 | 许可 | 覆盖范围 |
| --- | --- | --- |
| Stellar | **MPL-2.0**（修改部分） | `embedded/{manager,server,aidl,shared,api,provider,userservice,shizuku-aidl,shizuku-api}` + `app/src/main/cpp/stellar/` |
| Shizuku（经 Stellar 内含） | Apache-2.0 | Stellar 内部的 Shizuku 兼容层与相关 AIDL/服务端代码 |
| 宿主自有代码 | Apache-2.0 | `app/` 下除 Stellar 派生部分外的全部代码 |
| BoringSSL | OpenSSL + ISC + BSD | `io.github.vvb2060.ndk:boringssl:20250114`（prefab，包内自带声明） |
| libcxx | Apache-2.0 with LLVM exception | `org.lsposed.libcxx:libcxx:27.0.12077973`（prefab） |

---

## 二、逐项核验结果

### ✅ 1. MPL-2.0 第 3.4 条：被修改文件必须带显著修改声明

审计脚本 `tools/license_audit2.js` 扫描 `embedded/` 下全部 194 个 `src` 源文件，
按「品牌名 / 包名 / 资源 ID / 新增类名」等指纹识别出所有**被改动过的上游文件**，
再检查其文件头是否含修改声明：

```
src 下扫描: 194 个文件
=== 缺 MPL 声明的文件 (0) ===
=== 已带声明 (44) ===
```

**审计过程中发现并修复了 11 处真实缺口**（这些文件被改过但没有声明）：

| 文件 | 改了什么 |
| --- | --- |
| `manager/.../ui/features/manager/StarterScreen.kt` | 界面品牌名、剪贴板标签 |
| `manager/.../adb/AdbPairingService.kt` | 9 处通知小图标 |
| `manager/.../startup/notification/BootStartNotifications.kt` | 通知小图标 |
| `manager/.../startup/service/SelfStarterService.kt` | 通知字符串资源 ID |
| `manager/res/values-ar/strings.xml` | 品牌名 + 资源 ID + 删 app_name |
| `manager/res/values-es/strings.xml` | 同上 |
| `manager/res/values-fr/strings.xml` | 同上 |
| `manager/res/values-ja/strings.xml` | 同上 |
| `manager/res/values-pt-rBR/strings.xml` | 同上 |
| `manager/res/values-ru/strings.xml` | 同上 |
| `manager/res/values-zh-rHK/strings.xml` | 同上 |
| `manager/res/values-zh-rTW/strings.xml` | 同上 |

（另为 4 个**本工程新增**文件补了来源说明头：`CommandGuard.kt`、`BrandColors.kt`、
`OpenSourceLicensesActivity.kt`。这些不是上游文件，本不受 3.4 条约束，
加说明是为了让接收者能分清「哪些是上游代码、哪些是我们新写的」。）

### ✅ 2. MPL-2.0 第 3.1 / 3.2 条：MPL 源码须以 Source Code Form 提供

- 源码**直接内置在工程里**（不是 Maven 依赖）：
  - `embedded/` —— 149 个 `.kt` 文件 + AIDL + 资源
  - `app/src/main/cpp/stellar/` —— 16 个原生源文件
- 每处改动都带修改声明（见第 1 项）。
- ⚠️ **但源码只在开发工程里，APK 内不含源码**（实测：APK 内源码文件数 = 1，
  且那 1 个还是被加密进 assets 的网页资源，不是 Stellar 源码）。

> **这是本次审计发现的唯一未闭合项**：第 3.1/3.2 条要求「以 Source Code Form
> 提供」给**接收者**。如果本产品只以 APK 形式对外分发，接收者拿不到源码，
> 就不满足该条。需要在分发渠道（应用内「关于」、官网、仓库 release 页面等）
> 提供 Stellar 修改版的源码下载入口。**建议由你决定放哪里，我再落地。**

### ✅ 3. MPL-2.0 第 3.3 条（Larger Work）：传染性限于文件级

MPL 代码与自有代码是**文件级并置**，没有把 MPL 代码并入自有文件。
因此本产品整体不必开源，自有部分继续按 Apache-2.0 发布。已在 `NOTICE` 中声明。

### ✅ 4. MPL-2.0 第 3.4 条：不得删除许可证与版权声明

实测：`embedded/` 下 149 个 `.kt` 文件中，**含版权头/SPDX 的文件数 = 0** ——
即上游 Stellar 源码本身就不带任何版权头，我们没有删除任何东西。

`LICENSES/MPL-2.0.txt` 与 `LICENSES/Apache-2.0.txt` 均为许可证原文：

```
Apache-2.0.txt   58D1E17FFE5109A7   11357 bytes
MPL-2.0.txt      9221C2F936159B84   17099 bytes
LICENSE          58D1E17FFE5109A7   11357 bytes   ← 与 Apache-2.0.txt 同哈希
```

### ✅ 5. Apache-2.0 第 4(a) 条：向接收者提供许可证副本

实测 release APK 内：

```
res/f6.txt  48476 bytes（原名 res/raw/open_source_licenses.txt）
  含 "Apache License"          = True
  含 "Mozilla Public License"  = True
  含 "游龙安全护盾"（NOTICE 部分）= True
```

并且**应用内有查看入口**（两个，读的是各自模块的资源副本）：
- 宿主网页：`Android.openSourceLicenses()`（高级设置里的「开源许可」）
- 内置管理器：「设置 → 项目声明 → 开源许可」（`OpenSourceLicensesActivity`）

两份文本必须逐字一致（都是 48,476 字节），各自的 `res/raw/keep.xml`
用 `tools:keep` 防止被 `shrinkResources` 裁掉。

### ✅ 6. Apache-2.0 第 4(b) 条：保留 NOTICE 与修改声明

`NOTICE`（34,566 字节）逐条列出全部改动，按轮次分节：
`(A)` 标识符改写 → `(D)` 无障碍/设备管理器移除 → `(E)` 第五轮 →
`(F)` 未修改部分 → `(G)` 第六轮 → `(H)` 第七轮 → `(I)` 第八轮。
实测 `(E)/(G)/(H)/(I)` 均已存在。

### ✅ 7. Shizuku 的三项商标限制（README 要求）

| 限制 | 实测 | 结论 |
| --- | --- | --- |
| 不得使用 `Shizuku` 作为应用名 | `application-label` 全部 91 个语言均为「游龙安全护盾」 | ✅ |
| 不得使用 `moe.shizuku.privileged.api` 作为 applicationId | `package: name='com.youlong.hd'` | ✅ |
| 不得声明 `moe.shizuku.manager.permission.*` | `aapt2 dump badging` 中 `moe.shizuku` 匹配数 = 0 | ✅ |

另：也未使用 Stellar 的名称与图标作为产品标识（图标已全部去除，
`Stellar` 字样只保留在对第三方项目的署名按钮与声明文案里）。

---

## 三、审计结论

**除第 2 项外，全部合规要求均已满足。**

| 项 | 状态 |
| --- | --- |
| 被修改文件带修改声明（MPL 3.4） | ✅ 44/44，本轮补齐 11 处缺口 |
| 许可证原文随 APK 分发（Apache 4(a)） | ✅ `res/f6.txt` + 应用内两个入口 |
| NOTICE 完整（Apache 4(b)） | ✅ 34,566 字节，八轮改动全列 |
| 不删版权声明（MPL 3.4） | ✅ 上游本就无版权头，未删任何内容 |
| 商标限制未违反 | ✅ 三项全部通过 |
| Source Code Form 提供（MPL 3.1/3.2） | ⚠️ **待办**：源码在工程里，但分发渠道需提供下载入口 |

### 关于 Stellar-API 的许可声明冲突（先前已提示，此处保留记录）

`Stellar-API` 仓库的 README 写 MPL-2.0，但 `publish.gradle` 生成的 POM 写 GPL-3.0，
且仓库内没有 LICENSE 文件。本工程**完全避开了这个歧义**：
只使用 `Stellar` 主仓库的源码（Apache-2.0 + MPL-2.0，带 LICENSE），
**没有引入任何 Stellar-API 的发布产物**。
若将来要引入，建议先向上游确认许可。

---

## 四、复现方式

```powershell
# 1) 修改声明完整性（会列出所有缺声明的文件）
node E:\anquan\tools\license_audit.js
node E:\anquan\tools\license_audit2.js

# 2) 许可证文本是否随 APK 分发
#    解包后在 res/*.txt 里搜 "Apache License" / "Mozilla Public License"

# 3) 商标限制
& "D:\Android\sdk\build-tools\37.0.0\aapt2.exe" dump badging 游龙安全护盾-9.0.1-release.apk
#    看 package: name / application-label / 有无 moe.shizuku

# 4) 许可证原文哈希
Get-FileHash E:\anquan\LICENSES\*.txt -Algorithm SHA256
```
