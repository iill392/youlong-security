// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 脚本内容「危险命令」识别器（2026-10-25，用户要求换思路）：
//
// 应用执行 `sh 文件` 时，服务端已经能读到完整文件内容；本对象在交给
// PolicyEngine 评分之前先做一次确定性命中：
//   · 含 youlong；
//   · 冻结/卸载所有应用（-a / --all / 枚举后批量操作 / killall / pkill）；
//   · 删除系统/数据目录、重启、格式化、写块设备、重挂载；
//   · 静默写无障碍、持久化看门狗（nohup）、编码/解密后执行；
//   · 安装应用、设备管理员等。
// 命中即 BLOCK（不弹审批）。加密/二进制文件由 ProcessManager.looksBinary +
// CommandInterceptor 的编码规则共同拦截。
//
// 说明：这是「宁可误杀」的确定性列表，面向的是「已授权应用用 shell 搞破坏」，
// 正常工具的正常命令（ls / pm list packages / am start 等）不在列表里。
//
// 与 MPL-2.0 的关系：本文件是本工程新增，不是上游派生文件。
// ==========================================================================

package roro.stellar.server.service.process.policy

object DangerousScriptDetector {

    private class Rule(val label: String, val regex: Regex)

    private val RULES: List<Rule> = listOf(
        // 针对本产品
        Rule("含 youlong 关键字", Regex("(?i)youlong")),

        // 批量冻结 / 卸载
        Rule(
            "批量冻结应用",
            Regex("(?i)(?:pm\\s+disable-user|cmd\\s+package\\s+disable-user)[^\\n]*(\\s-a\\b|--all\\b)")
        ),
        Rule(
            "批量卸载应用",
            Regex("(?i)(?:pm\\s+uninstall|cmd\\s+package\\s+uninstall)[^\\n]*(\\s-a\\b|--all\\b)")
        ),
        Rule(
            "枚举应用后批量处置",
            Regex(
                "(?is)(?:pm|cmd\\s+package)\\s+list\\s+packages[\\s\\S]{0,3000}?" +
                        "(?:pm\\s+(disable-user|disable|uninstall|suspend|hide)|" +
                        "cmd\\s+package\\s+(disable-user|disable|uninstall|suspend|hide))"
            )
        ),
        Rule("批量停止进程", Regex("(?i)\\b(killall|pkill)\\b")),
        Rule("强制停止全部应用", Regex("(?i)am\\s+(kill-all|force-stop\\s+[\"']?\\*)")),
        Rule("前台应用杀手", Regex("(?is)dumpsys\\s+window[\\s\\S]{0,400}?am\\s+force-stop")),

        // 系统破坏
        Rule(
            "删除系统/数据目录",
            Regex(
                "(?i)\\brm\\s+(-[a-z]*[rf][a-z]*\\s+)+" +
                        "(/|/system|/sdcard|/data|/data/app|/data/data|/data/system)(\\s|/|$)"
            )
        ),
        Rule("重启/关机", Regex("(?i)\\b(reboot|shutdown|poweroff)\\b")),
        Rule("格式化/擦除分区", Regex("(?i)\\b(mkfs(\\.\\w+)?|wipe\\s+(data|system|cache))\\b")),
        Rule("直接写块设备", Regex("(?i)\\bdd\\s+[^\\n]*of=/dev/block")),
        Rule("重新挂载系统分区", Regex("(?i)\\bmount\\s+[^\\n]*remount\\b")),

        // 无障碍 / 持久化 / 编码执行
        Rule(
            "静默修改无障碍服务",
            Regex("(?i)settings\\s+put\\s+secure\\s+enabled_accessibility_services")
        ),
        Rule("持久化看门狗（nohup）", Regex("(?i)\\bnohup\\b")),
        Rule(
            "编码/解密后执行",
            Regex(
                "(?i)(base64\\s+(-d|--decode)|openssl\\s+(enc|aes|des|rsautl|pkeyutl)|" +
                        "gpg\\s+(--decrypt|-d)|xxd\\s+-r|\\beval\\b)"
            )
        ),

        // 安装 / 设备管理员
        Rule(
            "通过 shell 安装应用",
            Regex("(?i)\\b(?:pm\\s+install(?:-existing)?|cmd\\s+package\\s+install(?:-existing)?)\\b")
        ),
        Rule(
            "设备管理员/策略操作",
            Regex("(?i)\\bdpm\\s+(set-device-owner|set-profile-owner|remove-active-admin)\\b")
        ),

        // 中文脚本/提示文案里直接写明的批量操作
        Rule(
            "中文批量操作描述",
            Regex("冻结所有应用|卸载所有应用|禁用所有应用|清除所有应用|删除所有应用|安装应用")
        )
    )

    /**
     * @return 命中的规则说明；未命中返回 null
     */
    fun detect(script: String?): String? {
        if (script.isNullOrBlank()) return null
        for (r in RULES) {
            if (r.regex.containsMatchIn(script)) return r.label
        }
        return null
    }
}
