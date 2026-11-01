// ==========================================================================
// 游龙安全护盾 —— 新增文件（2026-10-31，用户需求）
// --------------------------------------------------------------------------
// 指令拦截白名单的「硬危险」规则：
//
//   白名单应用默认不再逐条询问用户，普通命令直接放行；
//   但只要命令/脚本出现以下行为，立即硬拦截（不询问、不可放行）：
//     · 针对游龙自身 com.youlong.hd / 配套工具 com.youlong.tool；
//     · 卸载、停用、冻结、强停、kill 这些应用；
//     · 对系统设置、SystemUI、设置提供者、权限控制器等系统关键组件
//       做卸载、停用、冻结、强停；
//     · 任意包含 youlong 的命令（延续原有保护）。
//
// 本对象不负责弹窗/审计，只返回命中原因；调用方统一走 Block 分支。
// ==========================================================================

package roro.stellar.server.service.process.policy

object HardBlockRules {

    private const val SELF = "(?:com\\.youlong\\.hd|com\\.youlong\\.tool)"

    /**
     * 系统关键组件：AOSP + 主流厂商设置/SystemUI/安全中心/桌面/安装器。
     * 只匹配真正的包名，避免误伤普通字符串。
     */
    private const val SYSTEM =
        "(?:" +
            "com\\.android\\.(?:settings|systemui|packageinstaller|permissioncontroller|shell|phone|providers\\.settings|server\\.telecom|providers\\.telephony)" +
            "|com\\.google\\.android\\.(?:packageinstaller|permissioncontroller)" +
            "|com\\.(?:vivo|bbk|iqoo|miui|xiaomi|huawei|honor|oppo|coloros|oplus|realme|oneplus)\\.[A-Za-z0-9_.]*" +
            "(?:settings|systemui|securitycenter|safecenter|launcher|packageinstaller|home)[A-Za-z0-9_.]*" +
            ")"

    /** 卸载/停用/冻结/强停/杀进程操作。 */
    private const val FREEZE =
        "(?:" +
            "pm\\s+(?:uninstall|disable-user|disable|suspend|hide|clear)" +
            "|cmd\\s+package\\s+(?:uninstall|disable-user|disable|suspend|hide|clear|set-user-restriction)" +
            "|am\\s+(?:force-stop|kill|kill-all)" +
            "|kill(?:all)?" +
            "|pkill" +
            ")"

    private val SELF_OP = Regex(
        "(?is)(?:$FREEZE)[\\s\\S]{0,200}?$SELF|$SELF[\\s\\S]{0,200}?(?:$FREEZE)",
        RegexOption.IGNORE_CASE
    )

    private val SYSTEM_OP = Regex(
        "(?is)(?:$FREEZE)[\\s\\S]{0,200}?$SYSTEM|$SYSTEM[\\s\\S]{0,200}?(?:$FREEZE)",
        RegexOption.IGNORE_CASE
    )

    /**
     * @return 命中原因；未命中返回 null。
     * 命中即为硬拦截，白名单也不能放行。
     */
    @JvmStatic
    fun detect(text: String?): String? {
        if (text.isNullOrBlank()) return null

        // 1) 任何 youlong 关键字：延续原有最高优先级保护。
        if (Regex("(?i)youlong").containsMatchIn(text)) {
            return "命令/脚本包含 youlong，可能针对游龙安全护盾或配套工具"
        }

        // 2) 针对游龙自身/配套工具的卸载、停用、冻结、强停、kill。
        if (SELF_OP.containsMatchIn(text)) {
            return "命令/脚本试图卸载、冻结或停止游龙安全护盾/游龙工具"
        }

        // 3) 针对系统设置/SystemUI 等关键组件的卸载、停用、冻结、强停。
        if (SYSTEM_OP.containsMatchIn(text)) {
            return "命令/脚本试图卸载、冻结或停止系统设置/SystemUI 等关键组件"
        }

        return null
    }
}
