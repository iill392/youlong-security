// ==========================================================================
// 进入应用时的权限门槛 + 绿色环境提示（2026-10 用户需求）
//
//   需求 1：进入软件就要「读取应用权限」和「通知权限」，不开则无法开启安全守护
//   需求 2：无 root 且无 com.rosan.dhizuku 时，在安全守护开关上方显示
//           一个**绿色描边**的提示（可关闭）
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

// ---------------------------------------------------------------------------
// 在 getEnvironmentWarning() 之前插入整块新代码
// ---------------------------------------------------------------------------
rep(
`        @JavascriptInterface
        public String getEnvironmentWarning() {`,
`        // ==================================================================
        // 2026-10 新增（用户需求）：进入应用时的权限门槛
        // ------------------------------------------------------------------
        // 需求：进入软件就要拿到「读取应用（已安装应用列表）」与「通知」两项权限，
        //       不开就不允许开启安全守护。
        //
        // 为什么不能只看 checkSelfPermission：
        //   · 读取应用列表靠的是 QUERY_ALL_PACKAGES —— 它是 **normal 权限**，
        //     安装时即授予，没有运行时弹窗。只看 flag 会永远显示"已授予"，
        //     万一 OEM 裁剪或用户在应用信息里关掉了，界面却还显示正常。
        //     所以这里**真的去查一次已安装应用数量**，查得到才算可用。
        //   · 通知权限（Android 13+ POST_NOTIFICATIONS）是运行时权限，可弹窗请求；
        //     Android 12 及以下安装即授予。
        //
        // 返回 JSON 供网页决定：是否弹权限引导、开关是否允许打开。
        // ==================================================================
        @JavascriptInterface
        public String getEntryRequirements() {
            boolean notif = hasNotificationPermission();
            boolean apps = hasAppListAccess();

            // 还缺哪些权限（用于网页按需显示引导）
            StringBuilder missing = new StringBuilder("[");
            boolean first = true;
            if (!notif) { missing.append("\\"notification\\""); first = false; }
            if (!apps) { missing.append(first ? "\\"applist\\"" : ",\\"applist\\""); }
            missing.append("]");

            return "{\\"notification\\":" + notif
                    + ",\\"applist\\":" + apps
                    + ",\\"ok\\":" + (notif && apps)
                    + ",\\"missing\\":" + missing + "}";
        }

        /** 通知权限是否可用（Android 13+ 需运行时授予） */
        private boolean hasNotificationPermission() {
            try {
                if (Build.VERSION.SDK_INT < 33) return true;   // Android 12L 及以下安装即授予
                return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable tr) {
                return true;   // 判定不了就不拦用户，避免误伤
            }
        }

        /**
         * 读取已安装应用的能力是否真的可用。
         *
         * <p>不是简单读 QUERY_ALL_PACKAGES 的 flag，而是实际查一次包列表：
         * 只有查得到（返回非空）才算可用。Android 11+ 若该权限被关掉，
         * getInstalledApplications 会返回被过滤后的极短列表甚至空列表。
         */
        private boolean hasAppListAccess() {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                java.util.List<android.content.pm.ApplicationInfo> list =
                        pm.getInstalledApplications(0);
                // 任何一个正常手机都会有几十个应用；少于 5 个说明被系统过滤了
                return list != null && list.size() >= 5;
            } catch (Throwable tr) {
                return false;
            }
        }

        /**
         * 申请通知权限（网页在进入应用时调用；Android 13+ 才会真的弹窗）。
         */
        @JavascriptInterface
        public void requestNotificationPermission() {
            try {
                if (Build.VERSION.SDK_INT < 33) return;
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                runOnUiThread(() -> {
                    try {
                        requestPermissions(
                                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                                REQ_NOTIFICATION_PERMISSION);
                    } catch (Throwable tr) {
                        Log.w("MainActivity", "申请通知权限失败", tr);
                    }
                });
            } catch (Throwable tr) {
                Log.w("MainActivity", "requestNotificationPermission 失败", tr);
            }
        }

        /**
         * 读取应用权限不可用时的引导：直接打开本应用的「应用信息」页，
         * 用户可在权限列表里检查「应用列表 / 已安装应用」相关项。
         * （QUERY_ALL_PACKAGES 是安装期授予，正常无需操作；这里只是兜底入口。）
         */
        @JavascriptInterface
        public void openAppListPermissionSettings() {
            runOnUiThread(() -> {
                try {
                    Intent it = new Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    it.setData(android.net.Uri.parse("package:" + getPackageName()));
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                } catch (Throwable tr) {
                    Log.w("MainActivity", "打开应用信息页失败", tr);
                }
            });
        }

        /**
         * 环境提示（绿色描边卡片）的判定：**没有 root 且没有 Dhizuku** 时显示。
         * 与 getEnvironmentWarning 的"异常"方向相反 —— 这里是"环境良好"的正向提示。
         */
        @JavascriptInterface
        public String getEnvGoodHint() {
            boolean dhizuku = false, root = false;
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                dhizuku = isPackageInstalled(pm, "com.rosan.dhizuku");
                root = isPackageInstalled(pm, "com.topjohnwu.magisk")
                        || isPackageInstalled(pm, "io.github.huskydg.magisk")
                        || isPackageInstalled(pm, "me.weishu.kernelsu")
                        || isPackageInstalled(pm, "com.rifsxd.ksunext")
                        || isPackageInstalled(pm, "me.bmax.apatch")
                        || isPackageInstalled(pm, "eu.chainfire.supersu")
                        || isPackageInstalled(pm, "com.koushikdutta.superuser");
            } catch (Throwable ignored) {}
            if (!root) root = suBinaryExists();

            boolean show = (!root && !dhizuku);
            return "{\\"show\\":" + show + ",\\"root\\":" + root
                    + ",\\"dhizuku\\":" + dhizuku + "}";
        }

        @JavascriptInterface
        public String getEnvironmentWarning() {`,
'1) 新增 getEntryRequirements / requestNotificationPermission / getEnvGoodHint');

// ---------------------------------------------------------------------------
// 权限回调：把结果回灌给网页，让引导卡片立刻更新
// ---------------------------------------------------------------------------
rep(
`    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {`,
`    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // 2026-10 新增：通知权限申请结果回灌网页，让"进入应用权限门槛"卡片立即刷新
        if (requestCode == REQ_NOTIFICATION_PERMISSION) {
            try {
                final String js =
                        "if(window.__dshOnPermResult)window.__dshOnPermResult("
                                + getEntryRequirements() + ");";
                if (webView != null) {
                    webView.post(() -> {
                        try { webView.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                    });
                }
                Log.i("MainActivity", "通知权限申请结果已回灌网页");
            } catch (Throwable tr) {
                Log.w("MainActivity", "回灌权限结果失败", tr);
            }
        }`,
'2) 权限回调回灌网页');

// ---------------------------------------------------------------------------
// 请求码常量
// ---------------------------------------------------------------------------
rep(
`    private DownloadManager downloadManager;`,
`    private DownloadManager downloadManager;

    // 2026-10 新增：通知权限申请请求码（进入应用时的权限门槛用）
    private static final int REQ_NOTIFICATION_PERMISSION = 0x7A21;`,
'3) 新增请求码常量');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
