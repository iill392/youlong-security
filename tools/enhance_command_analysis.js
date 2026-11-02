// ==========================================================================
// 第 2 项：命令分析增强（防止恶意程序用内置特权干坏事）
//
// 需求：内置特权里授权的应用（**除自身**）要具备命令分析能力，防止恶意程序
//       执行恶意命令，例如「把所有应用卸载/冻结」「冻结设置等系统软件」；
//       **中风险的要询问用户是否执行**。
//
// 现状（已有，保持不变）：
//   · 针对自家应用（com.youlong.hd / com.youlong.tool）→ 直接拒绝
//   · 批量停止所有应用（am kill-all / pkill / killall / 枚举后批量）→ 直接拒绝
//   · 卸载 / 冻结 → 询问用户
//
// 本次新增的「命令分析」维度（都归入"询问用户"，不改成直接拒绝）：
//   A. 目标是**系统应用**（设置、系统界面、包安装器等关键组件）→ 询问
//   B. **批量**操作（一条命令里对 3 个及以上包卸载/冻结）→ 询问
//   C. 动**设备管理员 / 用户 / 设备策略**（dpm、pm set-user-restriction 等）→ 询问
//   并且弹窗里要说清风险点（指令原文 + 风险说明）。
// ==========================================================================
const fs = require('fs');
const P = 'E:/anquan/embedded/server/src/main/kotlin/roro/stellar/server/service/process/CommandInterceptor.kt';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;

function rep(from, to, label) {
  const n = t.split(from).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次，跳过'); return false; }
  t = t.replace(from, to);
  console.log('OK ' + label);
  return true;
}

// ---------------------------------------------------------------- 1) 决策类加 kind
rep(
`    /** 危险操作，需要用户在管理器界面确认后才执行。 */
    data class NeedConfirm(val reason: String) : InterceptDecision()`,
`    /**
     * 危险操作，需要用户在管理器界面确认后才执行。
     *
     * @param kind 风险类型，用于弹窗里给出针对性的风险说明：
     *             system=动系统应用、bulk=批量操作、admin=设备管理员/策略、
     *             normal=普通卸载/冻结
     */
    data class NeedConfirm(val reason: String, val kind: String = "normal") : InterceptDecision()`,
'1) NeedConfirm 增加 kind');

// ---------------------------------------------------------------- 2) 新增规则
rep(
`    /** 卸载 / 冻结 / 清数据：需要用户确认。 */
    private val NEED_CONFIRM_PATTERNS = listOf(`,
`    /**
     * 系统关键组件（包名）。命中即视为"高风险"，必须让用户确认。
     *
     * <p>这里只列**最容易被恶意软件拿来搞破坏**的那些：
     * 设置、系统界面、包安装器、电话、短信、权限控制器、设备管理员等。
     * 冻结/卸载它们会导致系统不可用（设置进不去、装不了应用、无法卸载病毒）。
     */
    private val CRITICAL_SYSTEM_PACKAGES = listOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.providers.settings",
        "com.android.shell",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.mms",
        "com.android.providers.contacts",
        "com.android.providers.media",
        "com.android.documentsui",
        "com.android.vending",
        "com.android.launcher3",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.wifi",
        "com.android.se",
        "com.android.networkstack",
        "com.android.inputmethod.latin",
        "com.android.keychain"
    )

    /**
     * 系统应用的**通用**特征（不全，用于兜底识别非关键系统应用）。
     * 真正的"是不是系统应用"判定由 {@link #isSystemPackage(String)} 在运行时用
     * `pm list packages -s` 查（结果缓存 60 秒），这里只是快速预筛。
     */
    private val SYSTEM_PACKAGE_PATTERN = Regex(
        """^(android|com\.android|com\.google\.android|com\.qualcomm|com\.mediatek|""" +
        """com\.miui|com\.xiaomi|com\.huawei|com\.honor|com\.oppo|com\.coloros|""" +
        """com\.vivo|com\.bbk|com\.oneplus|com\.oplus|com\.realme|com\.meizu|""" +
        """com\.smartisan|com\.sony|com\.samsung|com\.sec|com\.lg|com\.htc|""" +
        """com\.motorola|com\.lenovo|com\.asus|com\.nokia|com\.zte)\\."""
    )

    /** 设备管理员 / 用户 / 设备策略类命令：非常敏感，必须确认。 */
    private val ADMIN_PATTERNS = listOf(
        Regex("""\\bdpm\\b"""),
        Regex("""\\bpm\\s+set-user-restriction\\b"""),
        Regex("""\\bpm\\s+remove-user\\b"""),
        Regex("""\\bpm\\s+create-user\\b"""),
        Regex("""\\bpm\\s+set-user-icon\\b"""),
        Regex("""\\bam\\s+set-user-restriction\\b"""),
        Regex("""\\bcmd\\s+device_policy\\b"""),
        Regex("""\\bcpm\\s+(install|remove)\\b""")
    )

    /** 卸载 / 冻结 / 清数据：需要用户确认。 */
    private val NEED_CONFIRM_PATTERNS = listOf(`,
'2) 新增系统应用/管理员判定规则');

// ---------------------------------------------------------------- 3) inspect 里加分析
rep(
`        // ---- 规则 3：卸载 / 冻结 → 需要用户确认 --------------------------
        if (NEED_CONFIRM_PATTERNS.any { it.containsMatchIn(flat) }) {
            // 目标是否只涉及自家应用？那种更严重，交给规则 1 直接拒绝（已拦）
            LOGGER.w("卸载/冻结命令需用户确认: caller=%s, cmd=%s", who, flat)
            return InterceptDecision.NeedConfirm(describeDanger(flat))
        }

        return InterceptDecision.Allow
    }`,
`        // ---- 规则 3：卸载 / 冻结 → 需要用户确认 --------------------------
        if (NEED_CONFIRM_PATTERNS.any { it.containsMatchIn(flat) }) {
            // 目标是否只涉及自家应用？那种更严重，交给规则 1 直接拒绝（已拦）
            //
            // 2026-10 增强：在"要确认"的基础上再做一层**命令分析**，
            // 识别出更危险的形态并在弹窗里说清风险：
            //   · 目标是系统应用（设置/系统界面/包安装器…）→ 冻结它们会让系统不可用
            //   · 一条命令里批量动 3 个以上包（典型"一键卸载/冻结全部"）
            //   · 动设备管理员 / 用户 / 设备策略（dpm、set-user-restriction…）
            val kind = analyzeRiskKind(flat)
            LOGGER.w("卸载/冻结命令需用户确认: caller=%s, kind=%s, cmd=%s", who, kind, flat)
            return InterceptDecision.NeedConfirm(describeDanger(flat), kind)
        }

        // ---- 规则 4：设备管理员 / 用户 / 策略类命令 → 需要用户确认 ---------
        // 不一定伴随卸载（例如只调 dpm 摘管理员），单独再兜一层。
        if (ADMIN_PATTERNS.any { it.containsMatchIn(flat) }) {
            LOGGER.w("设备管理员/策略类命令需用户确认: caller=%s, cmd=%s", who, flat)
            return InterceptDecision.NeedConfirm(describeDanger(flat), "admin")
        }

        return InterceptDecision.Allow
    }

    // ------------------------------------------------------------------
    // 命令分析：判断风险类型
    // ------------------------------------------------------------------

    /**
     * 分析这条命令属于哪种风险类型，供弹窗给出针对性说明。
     *
     * <p>优先级：admin（动管理员/策略）> system（动系统应用）> bulk（批量）> normal。
     */
    private fun analyzeRiskKind(flat: String): String {
        if (ADMIN_PATTERNS.any { it.containsMatchIn(flat) }) return "admin"
        if (mentionsSystemPackage(flat)) return "system"
        if (affectedPackageCount(flat) >= 3) return "bulk"
        return "normal"
    }

    /** 命令里是否点到了系统应用（关键组件清单 + 通用系统包名前缀） */
    private fun mentionsSystemPackage(flat: String): Boolean {
        if (CRITICAL_SYSTEM_PACKAGES.any { flat.contains(it) }) return true
        // 通用前缀：逐个提取包名再判断，避免误命中无关字符串
        return extractPackages(flat).any { SYSTEM_PACKAGE_PATTERN.containsMatchIn(it) }
    }

    /**
     * 这条命令大概会影响多少个包。
     *
     * <p>用 {@link #extractPackages} 提取到的包名个数估算 ——
     * "一键卸载/冻结全部应用"这类恶意命令通常会在一条命令里串起很多包名，
     * 或者用 `pm list packages | while ...` 的形式（那种已被规则 2 直接拒绝）。
     */
    private fun affectedPackageCount(flat: String): Int = extractPackages(flat).size`,
'3) inspect 里加入命令分析 + 风险分级');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
