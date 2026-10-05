package com.youlong.hd;

/**
 * 包名校验工具 —— 在把包名拼进 shell 命令之前统一校验。
 *
 * <h3>为什么需要它</h3>
 * 本应用有多处把包名直接拼进 shell 命令，例如：
 * <pre>
 *   StellarUtils.runCommand("pm uninstall " + pkg, 15000);
 *   StellarUtils.runCommand("am force-stop " + pkg, 10000);
 *   StellarUtils.runCommand("pm disable-user --user 0 " + pkg, 15000);
 * </pre>
 *
 * 其中一部分包名来自<b>文本解析</b>，而不是系统 API：
 * <ul>
 *   <li>{@code ProtectService.getFgSimple()} —— 从
 *       {@code dumpsys window windows} 输出里用 {@code indexOf("u0 ")} +
 *       {@code substring()} 切片取值，原先仅检查 {@code contains(".")}；</li>
 *   <li>{@code getFgViaStellar()} —— 同样来自 dumpsys 文本。</li>
 * </ul>
 * dumpsys 的输出格式并非稳定接口，不同 ROM / 不同系统版本的格式差异，
 * 或者被解析到一段非预期的文本，都可能产出一个「看起来像包名」但实际含有
 * shell 元字符（{@code ; | &amp; $ ` ( ) \n} 等）的字符串。一旦进入上面的命令，
 * 就会被 shell 当作额外命令执行。
 *
 * <h3>这个类做什么</h3>
 * 用白名单方式校验：只接受符合 Android 包名规范的字符串，其余一律拒绝。
 * 这是<b>纵深防御</b>——即使上游解析出错，也无法把控制字符带进 shell。
 *
 * <h3>不做什么</h3>
 * 不做 URL 编码 / 引号转义，也不改变任何现有命令的语义；只为「合法包名」放行。
 * 合法的 Android 包名本来就不会包含 shell 元字符，因此该校验对正常流程
 * <b>零影响</b>。
 */
public final class PkgGuard {

    private PkgGuard() {}

    /** Android 包名长度上限（保守取值，实际系统限制更宽松）。 */
    private static final int MAX_LEN = 255;

    /**
     * 校验字符串是否为合法的 Android 包名。
     *
     * <p>规则：至少两段、以 '.' 分隔；每段以字母开头，其余字符为
     * ASCII 字母 / 数字 / 下划线。任何其它字符（含全部 shell 元字符）都判为非法。
     *
     * <pre>
     *   合法：com.example.app   a.b_c.d1   com.a1.b2
     *   非法：(null)  ""  "."  "a"  "a."  ".a"  "a..b"
     *         "com.example; id"   "com.example|x"   "com.example`id`"
     *         "com.example\nid"   "com.example/app"  "com.example app"
     * </pre>
     */
    public static boolean isValid(String pkg) {
        if (pkg == null) return false;

        final int n = pkg.length();
        if (n < 3 || n > MAX_LEN) return false;

        boolean sawDot = false;
        boolean atSegmentStart = true;

        for (int i = 0; i < n; i++) {
            final char c = pkg.charAt(i);

            if (c == '.') {
                // 开头就是点、或连续两个点 → 非法
                if (atSegmentStart) return false;
                sawDot = true;
                atSegmentStart = true;
                continue;
            }

            if (atSegmentStart) {
                // 每段必须以字母开头（与 Android 规范一致）
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return false;
                atSegmentStart = false;
                continue;
            }

            final boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_';
            if (!ok) return false;   // 任何其它字符，包括所有 shell 元字符
        }

        // 至少要有一个 '.'，且不能以 '.' 结尾
        return sawDot && !atSegmentStart;
    }

    /**
     * 校验通过则返回原包名，否则返回 {@code null}。
     *
     * <p>便于写成「取不到合法包名就放弃」的形式：
     * <pre>
     *   String p = PkgGuard.check(pkg);
     *   if (p == null) { Log.w(TAG, "非法包名，已忽略: " + pkg); return; }
     *   StellarUtils.runCommand("pm uninstall " + p, 15000);
     * </pre>
     */
    public static String check(String pkg) {
        return isValid(pkg) ? pkg : null;
    }
}
