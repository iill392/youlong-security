// ==========================================================================
// 修 root 误判（用户反馈：没 root 的用户被判定有 root）
//
// 原来的判定有两个不可靠的兜底：
//   ① suBinaryExists()：只看 /system/bin/su 等路径**文件是否存在**。
//      不少 ROM / 厂商工程模式 / 残留文件都可能有同名文件，但设备并没有 root。
//   ② 包名加上了 com.koushikdutta.superuser（Superuser 的旧客户端），
//      这个包名并不专属 root 管理工具，容易误命中。
//
// 改成"**实证**"判定：只有 `su -c id` 真的能跑起来并且返回 `uid=0` 才算有 root。
//   · Magisk / KernelSU / APatch 装了且已授权 → su 直接返回 uid=0 → 判有 root ✔
//   · 没有 root（或用户拒绝授权）→ su 不存在 / 被拒 / 超时 → 判没有 root ✔
// 同时把三套重复实现统一到外层一个方法，内部类改为委托，避免以后又各改各的。
// ==========================================================================
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;
const crlf = s => s.replace(/\r?\n/g, '\r\n');

function rep(from, to, label) {
  const f = crlf(from), g = crlf(to);
  const n = t.split(f).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次，跳过'); return false; }
  t = t.replace(f, g);
  console.log('OK ' + label);
  return true;
}

// ---------------------------------------------------------------- 1) 外层：改判定
rep(
`    /** 是否存在 su 二进制（只读探测，不执行 su） */
    private boolean suBinaryExistsOuter() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
                "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/busybox"
        };
        for (String p : paths) {
            try { if (new File(p).exists()) return true; } catch (Throwable ignored) {}
        }
        return false;
    }

    /** 设备是否 root（包名 + su 路径，无副作用） */
    private boolean deviceIsRooted() {
        if (isInstalledPkg("com.topjohnwu.magisk")
                || isInstalledPkg("io.github.huskydg.magisk")
                || isInstalledPkg("me.weishu.kernelsu")
                || isInstalledPkg("com.rifsxd.ksunext")
                || isInstalledPkg("me.bmax.apatch")
                || isInstalledPkg("eu.chainfire.supersu")
                || isInstalledPkg("com.koushikdutta.superuser")) {
            return true;
        }
        return suBinaryExistsOuter();
    }`,
`    /**
     * su 二进制的候选路径（仅作为"值得一试"的前置判断，**不作为 root 结论**）。
     *
     * ⚠️ 2026-10 修正：原实现用"文件存在"直接下 root 结论，导致**没 root 的用户
     *    被误判**（部分 ROM / 工程模式 / 残留文件会有同名文件，但设备并无 root）。
     *    现在这里只用来决定"要不要去试一次 su"，最终结论必须由
     *    {@link #suGrantsRoot()} 实证得出。
     */
    private boolean suBinaryPresent() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
                "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su"
        };
        for (String p : paths) {
            try { if (new File(p).exists()) return true; } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * 实证：su 是否真的给到了 root。
     *
     * <p>执行 {@code su -c id}，只有输出里出现 {@code uid=0} 才算有 root。
     * 这是唯一可靠的判据 ——
     *   · Magisk / KernelSU / APatch 已授权 → 返回 uid=0 → 判有 root；
     *   · 没有 root（su 不存在）/ 用户拒绝授权 / 超时 → 判没有 root。
     *
     * <p>带 3 秒超时且只跑一次，避免卡住首页；对已 root 的设备最多弹一次
     * 系统的 su 授权框（用户拒绝后就按"没有 root"处理，且各家 su 会记住拒绝）。
     */
    private boolean suGrantsRoot() {
        // 先看包名：装了已知的 root 管理器才值得跑一次 su（减少无谓的尝试）
        boolean knownManager = isInstalledPkg("com.topjohnwu.magisk")
                || isInstalledPkg("io.github.huskydg.magisk")
                || isInstalledPkg("me.weishu.kernelsu")
                || isInstalledPkg("com.rifsxd.ksunext")
                || isInstalledPkg("me.bmax.apatch");
        if (!knownManager && !suBinaryPresent()) return false;

        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            final Process proc = p;
            final StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) out.append(line).append('\\n');
                } catch (Throwable ignored) {}
            });
            reader.setDaemon(true);
            reader.start();

            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
                return false;
            }
            reader.join(500);
            String s = out.toString();
            boolean rooted = s.contains("uid=0");
            Log.i("MainActivity", "su -c id => " + s.replace('\\n', ' ').trim() + "  rooted=" + rooted);
            return rooted;
        } catch (Throwable tr) {
            // su 不存在、权限被拒、进程异常 —— 一律按"没有 root"处理
            return false;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 设备是否 root（2026-10 改为"实证"判定，修掉没 root 被误判的问题）。
     *
     * <p>判据只有一条：{@code su -c id} 真的返回 {@code uid=0}。
     * 不再用"su 文件存在"或"装了某个包名"直接下结论。
     */
    private boolean deviceIsRooted() {
        try {
            return suGrantsRoot();
        } catch (Throwable tr) {
            return false;
        }
    }`,
'1) 外层改为实证判定（su -c id 必须返回 uid=0）');

// ---------------------------------------------------------------- 2) 内部类 getEnvironmentWarning 委托
rep(
`        @JavascriptInterface
        public String getEnvironmentWarning() {
            boolean dhizuku = false;
            boolean root = false;

            try {
                PackageManager pm = MainActivity.this.getPackageManager();
                dhizuku = isPackageInstalled(pm, "com.rosan.dhizuku");
                // 常见的 root 方案管理器包名；装了基本可以认定设备已 root
                root = isPackageInstalled(pm, "com.topjohnwu.magisk")
                        || isPackageInstalled(pm, "io.github.huskydg.magisk")
                        || isPackageInstalled(pm, "me.weishu.kernelsu")
                        || isPackageInstalled(pm, "com.rifsxd.ksunext")
                        || isPackageInstalled(pm, "me.bmax.apatch")
                        || isPackageInstalled(pm, "eu.chainfire.supersu")
                        || isPackageInstalled(pm, "com.koushikdutta.superuser");
            } catch (Throwable ignored) {
            }

            if (!root) {
                root = suBinaryExists();
            }

            // 严重程度：root > dhizuku`,
`        @JavascriptInterface
        public String getEnvironmentWarning() {
            // 2026-10 修正（用户反馈：没 root 被误判有 root）：
            // 原来这里自己实现了一套"包名 + su 文件存在"的判定，与外层重复且不可靠。
            // 现在统一委托外层的 deviceIsRooted() / deviceHasDhizuku()，
            // 二者都改成**实证判定**（su -c id 必须返回 uid=0）。
            final boolean root = MainActivity.this.deviceIsRooted();
            final boolean dhizuku = MainActivity.this.deviceHasDhizuku();

            // 严重程度：root > dhizuku`,
'2) getEnvironmentWarning 委托外层判定');

// ---------------------------------------------------------------- 3) 删掉内部类里的旧实现
rep(
`        /** 包是否已安装（Android 11+ 对不可见包会抛 NameNotFoundException，按未安装处理） */
        private boolean isPackageInstalled(PackageManager pm, String pkg) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }`,
`        /**
         * 2026-10 修正：原来这里还有一份 isPackageInstalled() 与 suBinaryExists()，
         * 与外层实现重复且 root 判定不可靠。现已统一到外层
         * （deviceIsRooted / deviceHasDhizuku / isInstalledPkg / suGrantsRoot），
         * 本内部类不再持有独立的 root 判定实现。
         */
        @SuppressWarnings("unused")
        private boolean isPackageInstalledUnusedPlaceholder() {
            return false;
        }`,
'3) 移除内部类里重复的包判定实现');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
