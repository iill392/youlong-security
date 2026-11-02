# 第 25 轮改动汇总（2026-10-24）—— 病毒特征库（virus-db）对接

本轮按 `yl-tool-data/virus-db-README.md` 把新增的 `virus-db.json` /
`virus-db-rules.json` 接入产品，分两处落地：

- **宿主 App**：拉取/解析/持久化/回滚特征库，做 **APK 内容级检测**（签名证书 /
  整包哈希 / DEX 哈希 / 资产哈希 / 高特异 token / 结构指纹 / 包名形态），
  并把手机会话防御需要的快照推给内置特权；
- **内置特权服务端**：加载快照，在 `newProcess` 入口做**调用方证书拦截**，
  在 stdin 脚本上跑**行为规则**（token/regex + 权重）。

> 现有包名/名称列表（`safe1/safe2/anquan*/mian*/mingcheng*`）**完全保留**，
> 按 README §7 与本库并存，不替换。

---

## 一、数据源

| 项 | 值 |
|---|---|
| 地址 | `https://yl-tool-data.pages.dev/virus-db.json`、`/virus-db-rules.json`（与现有列表同源，无需 Token） |
| 当前版本 | `2026.10.24.1`（schema `youlong/virus-db/1`） |
| 家族 | `TerminalDeityUltra`（终末神明Ultra）：2 张证书、1 个包名、1 个 APK 样本、9 个 DEX 哈希、2 个资产哈希、7 高 + 1 低 token、12 条行为规则 |
| 更新 | ETag + `If-None-Match`（304 省流量）；`version` 相同且正文未变则跳过解析；解析失败回滚 `*_prev` |

---

## 二、宿主 App 改动

| 文件 | 改动 |
|---|---|
| `app/.../VirusContentDb.java`（**新增**，约 1800 行） | 拉取/解析/持久化/回滚、内存索引、`inspectApk()` 内容检测、`snapshotJson()` / `pushSnapshot()`、状态 JSON |
| `app/.../VirusScanner.java` | 手动扫描在原有包名/名称判定之后追加内容检测（BLOCK→100% 病毒、WARN→可能病毒），**8 秒总预算 + `contentTruncated`**；`status/scan` 增加 `contentReady/contentVersion/contentChecked/contentTruncated` |
| `app/.../StellarUtils.java` | 新增 `writeFileViaStdin(path, content)`：路径白名单校验、内容 base64、`&&` 串联保证退出码可信、10s 超时 |
| `app/.../VirusDb.java` | `refreshNow()` 末尾追加 `VirusContentDb.refreshNow(ctx)`（try/catch），旧列表逻辑零改动 |

### 2.1 内存索引

`certSet`（证书 sha256→家族/action）、`pkgSet`、`apkSet`（整包 sha256）、
`dexSet`（DEX 文件名 + sha256）、`assetSet`（路径 + sha256）、`highTokens/lowTokens`、
`structures`、`packagePattern`、`rules`（token/regex 编译）、`allowlistCerts`。

### 2.2 `inspectApk` 判定顺序（README §4.2/§4.4）

1. **allowlist 证书** → CLEAN（开发/内部测试证书放行）
2. **签名证书**：API 28+ `GET_SIGNING_CERTIFICATES`，更老回退 `GET_SIGNATURES`；
   action=block → **BLOCK**，action=warn → 记 WARN 继续找更强信号
3. **整包 sha256**（>150MB 跳过该步）
4. **DEX 哈希**（解压后哈希，重签名不变）
5. **关键资产哈希**（路径存在 + 内容哈希一致）
6. **高特异 token**：扫描 dex/资产等条目字节（64MB 预算、256KB 分块流式），
   ≥ `high_token_block_min`（默认 2）→ **BLOCK**，命中 1 个 → WARN
7. **弱信号**：结构指纹（dex 数 / frameworks 数 / arsclib / p12）、克隆包名形态、
   通用 token；无强信号且 token=0 时弱信号 ≥2 → WARN
8. 判定结果按 `包名 + versionCode + lastUpdateTime + 特征库版本` 进程内缓存

> 内容级 BLOCK 在扫描页显示为「100% 病毒」，仍走原有「点击卸载」流程，
> **不会**因为内容检测而被静默自动卸载。

### 2.3 服务端快照

特征库更新成功后，App 生成精简快照并写入：

```
/data/local/tmp/stellar_guard/virus_db.json   （0600）
```

字段：`schema/version/updated_at/decision_policy/allowlist_certs/certs[]/packages[]/rules[]`
（只含服务端需要的内容，不含文件哈希，体积约 2–4KB）。同一版本只推一次；
内置特权未连接时静默跳过，下次刷新补推。

### 2.4 定时拉取（2026-10-24 追加，用户需求）

**只要应用在主进程运行，就每 3 分钟拉取一次** `virus-db.json` + `virus-db-rules.json`：

- 启动位置：`YouLongApp.onCreate()`（Application 主进程）→ `VirusContentDb.startPeriodicRefresh()`
- `:guard` 哨兵进程不启动（Application 在每个进程都会初始化；用 `/proc/self/cmdline`
  判断进程名，避免双份请求）
- 实现：daemon `ScheduledExecutorService`，首次延迟 3 分钟（启动阶段已由
  `VirusDb.refreshNow` 顺带拉过一次），之后固定延迟 3 分钟；单次异常只记日志，
  下个周期继续
- 走 `refreshNow()`：与手动/启动刷新共用 `FETCH_LOCK` 串行；ETag 未变时服务器只回
  304；版本未变不重复解析、不重复推快照
- 幂等：`AtomicBoolean` 保证只启动一次
- 状态可查：`VirusContentDb.statusJson()` 新增 `periodic / pollIntervalMs /
  pollCount / lastPoll`；运行日志每轮打印「定时拉取病毒特征库（第 N 次）」
- 进程退出后 daemon 线程自动结束；Doze/厂商省电策略可能推迟定时器（属于系统行为）

### 2.5 内置特权管理器：底部导航「病毒库」独立页面（2026-10-24 追加，用户需求）

位置：**内置特权管理器底部导航第 3 项「病毒库」**（启动 / 授权应用 / 病毒库 / 设置）。

- 顶部 Hero 卡：特征库版本大号显示 + 状态（已就绪 / 尚未同步 / 同步中… / 上次同步失败 / 内置特权未连接）
- 详情卡：版本、上次同步时间、上次检查时间、自动同步周期（每 3 分钟）、状态、上次失败原因
- **「立即检查更新」按钮**：强制触发一次特征库同步（忽略 30 秒节流），同步中按钮置灰；
  完成后版本 / 时间每秒自动刷新（Toast：已开始同步 / 正在同步中…）
- 页面底部说明：手动同步会立即拉取最新特征库，并把快照同步给内置特权服务
- 数据流（manager 不反向依赖 app）：
  ```
  embedded/manager/.../virusdb/VirusDbAccess.kt    ← 状态数据 + StatusProvider/SyncTrigger
        ▲ 注入（YouLongApp.onCreate）
  app VirusContentDb.syncStatus(ctx) / requestSync(ctx, force)
  ```
- 文件：新增 `ui/features/virusdb/VirusDbScreen.kt`、`virusdb/VirusDbAccess.kt`；
  `NavigationRoutes.kt` 增加 `MainScreen.VirusDb`（图标 Security）；
  `MainActivity.kt` 增加路由；`strings.xml` 增加 `nav_virus_db` 与页面文案。
- 原「设置 → 病毒库同步」卡片已移除（`VirusDbSyncCard.kt` 删除），功能全部迁到新页面。

---

## 三、内置特权服务端改动

| 文件 | 改动 |
|---|---|
| `embedded/server/.../process/policy/VirusSnapshot.kt`（**新增**） | Gson 解析快照（mtime 缓存、损坏静默降级）、证书索引、规则编译（token/regex）、`matchCert()` / `matchScript()` |
| `embedded/server/.../process/policy/PolicyEngine.kt` | `precheck`：证书命中病毒库（非 allowlist、action≠warn）→ 直接拒；argv 命中规则 → 直接拒。`evaluate`：先跑病毒库规则，`block` 规则命中或累计 ≥60 → 直接拒；否则把规则分数/原因并入行为链 |

### 决策语义

- 证书命中（`action=block`）→ **BLOCK**，不弹审批（防社工）；
- `action=warn` 的证书（如该样本的 debug 发布证书）→ 不在会话入口直接拦，
  仍由后续行为链/目标保护判断；
- 脚本命中 `action=block` 规则，或规则累计权重 ≥60（README §4.3 `denySession`）
  → **BLOCK**；
- `policy_mode.txt=audit` 时上述判定降级为只记录（灰度开关，与本轮之前的策略共用）。

---

## 四、验证

### 4.1 服务端快照与规则（离线实测）

用真实 `virus-db.json` + `virus-db-rules.json` 生成快照并跑
`VirusSnapshot`（Kotlin 2.2.10 + Gson 2.13.1）：

| 用例 | 结果 |
|---|---|
| 快照加载 | version=`2026.10.24.1`，certs=2，rules=12 |
| 克隆证书 `ca89a097…` | 命中，`action=block`，family=`TerminalDeityUltra` |
| 发布证书 `6207a27d…` | 命中，`action=warn` |
| 未知证书 | 未命中 |
| 样本攻击脚本（`$PKG` 展开 + 组件循环 + nohup + `1314520`） | **block=true，score=130**，命中 `TDU-003/004/007` |
| 合法 `pm list packages -3` | 无命中 |
| 仅无障碍写入脚本 | 命中 warn 规则 `TDU-005`（score=25，不直接拒） |

### 4.2 编译与整包构建

| 项 | 结果 |
|---|---|
| 服务端（含新增 `VirusSnapshot.kt`）独立编译 | ✅ 0 error（android.jar 37 + Gson） |
| 宿主 4 文件离线 javac | ✅ 0 error（teammate 校验） |
| **`:app:assembleRelease`** | ✅ **BUILD SUCCESSFUL in 3m 56s**（249 tasks，含 3 分钟定时拉取 + 病毒库独立页面） |
| 产物 | `build_out/_app/outputs/apk/release/app-release-20261024-213631.apk`，40,073,988 B（38.22 MB） |
| 复制件 | `游龙安全护盾-9.2.9-release-20261024-virusdb.apk` |
| SHA-256 | `DFD2D6D38861CE4239CF3EC73B231A9EFA965CA435A505A9C976901C63A67BC5` |
| 版本 / 签名 | `9.2.9 / 1234567954`，V3 证书 `d3d868c9…`（与历史一致，可覆盖安装） |
| APK 内已确认 | `VirusDbScreen`、`VirusDbSyncStatus`、`nav_virus_db`、`virus_db_page_title`、`virus_db_check_now`、`virus_db_state_*` 等资源、`VirusContentDb-Poll` / `-ManualSync`、`last_success`、`youlong/virus-db/snapshot/1`、`StellarVirusDb` 均在包内；assets/native 结构不变 |

### 4.3 未完成

1. **真机端到端**：拉取/解析、原样本命中、重签名样本 DEX 命中、正常工具 CLEAN、
   Shizuku 会话规则拦截、更新回滚、allowlist / 允许一次——需真机按 README §8 跑。
2. 内容检测目前只在**手动扫描**触发；`PACKAGE_ADDED/REPLACED` 实时检测未接
   （README §4.2 建议的安装时检测留作下一轮；保护服务仍按原有包名/名称列表自动拦截）。
3. README「可选」的**特征库文件签名校验**（Ed25519/RSA + 内置公钥）未实现。
4. 服务端只使用快照里的证书与行为规则；**包名/整包/DEX/资产哈希**的判定在 App 侧
   （服务端没有等价 APK 扫描逻辑）。
5. 8 秒预算下真机一次手动扫描可能只能完成部分应用的内容检测（`contentTruncated=true`），
   其余应用仅走包名/名称快查；建议后续把内容检测做成后台增量任务（按安装时间/缓存续跑）。

---

## 五、配置与运维

| 文件 | 说明 |
|---|---|
| App SharedPreferences `virus_content_db` | 两个 raw JSON + etag + version + updated_at + 上一版备份 |
| 设备 `/data/local/tmp/stellar_guard/virus_db.json` | 服务端快照（0600，App 经内置特权写入） |
| 设备 `/data/local/tmp/stellar_guard/policy_mode.txt` | `enforce`（默认）/ `audit`（病毒库规则与其它策略一起降级为只记录） |
| 设备 `/data/local/tmp/stellar_guard/shell_audit.jsonl` | 会话审计（含规则命中后的判定记录） |

上线顺序建议：先 `audit` 观察 1–2 天（看审计里的 `病毒特征库规则命中` 是否误报）→
再改回 `enforce`；App 侧内容检测只影响扫描页展示与用户主动卸载，不会静默删应用。
