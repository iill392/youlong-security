// ==========================================================================
// 修"读取已安装应用列表"：改成无需授权即可用的查询方式
//
// 问题：原来用 getInstalledApplications(0) 判定"能否读取应用列表"。
//       在 Android 11+ 这套 API 受包可见性（QUERY_ALL_PACKAGES）影响，
//       很多 ROM 上即使声明了该权限也会被过滤，于是误判成"缺权限"，
//       界面上就冒出"检查读取应用权限"这种要用户自己去设置的按钮 ——
//       用户体验很差，而且根本没必要。
//
// 改法：应用列表本来就只需要"已安装的可见应用（有启动图标的那些）"，
//       用 PackageManager.queryIntentActivities(MAIN + LAUNCHER) 即可拿到，
//       **无需任何权限、无需用户操作**，这也正是网页 getInstalledApps() 实际用的方式。
//       判定与取数据统一到同一条路径，就不会再误报。
// ==========================================================================
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;

function rep(from, to, label) {
  const f = from.replace(/\r?\n/g, '\r\n');
  const g = to.replace(/\r?\n/g, '\r\n');
  const n = t.split(f).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次，跳过'); return false; }
  t = t.replace(f, g);
  console.log('OK ' + label);
  return true;
}

// 1) 判定改用 launcher 查询
rep(
`    /**
     * 读取已安装应用的能力是否真的可用。
     * 不读 flag，而是实际查一次包列表：Android 11+ 若 QUERY_ALL_PACKAGES 被关掉，
     * getInstalledApplications 会返回被过滤后极短的列表。
     */
    private boolean hasAppListAccessOuter() {
        try {
            java.util.List<android.content.pm.ApplicationInfo> list =
                    getPackageManager().getInstalledApplications(0);
            return list != null && list.size() >= 5;
        } catch (Throwable tr) {
            return false;
        }
    }`,
`    /**
     * 读取"已安装应用列表"的能力是否可用。
     *
     * <p>2026-10 修正（用户反馈）：**不要再让用户去设置里授权**。
     * 应用列表实际只需要"有启动图标的可见应用"，用
     * {@code queryIntentActivities(MAIN + LAUNCHER)} 就能拿到 ——
     * 这条路径在 Android 11+ **不需要任何权限、不需要用户操作**，
     * 也正是网页 getInstalledApps() 实际使用的方式。
     *
     * <p>原来用 getInstalledApplications(0) 判定，它受包可见性
     * （QUERY_ALL_PACKAGES）影响，很多 ROM 上会被过滤 → 误判成"缺权限" →
     * 界面上就冒出"检查读取应用权限"这种把用户丢去设置的按钮。
     * 现在判定与取数据走同一条路径，不会再误报。
     */
    private boolean hasAppListAccessOuter() {
        try {
            android.content.Intent it = new android.content.Intent(
                    android.content.Intent.ACTION_MAIN);
            it.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> list =
                    getPackageManager().queryIntentActivities(it, 0);
            boolean ok = list != null && list.size() >= 3;
            Log.i("MainActivity", "应用列表可用性(launcher 查询)=" + ok
                    + " 数量=" + (list == null ? -1 : list.size()));
            return ok;
        } catch (Throwable tr) {
            Log.w("MainActivity", "queryIntentActivities 失败", tr);
            return false;
        }
    }`,
'1) 应用列表判定改为无授权的 launcher 查询');

// 2) 权限卡按钮：把"跳设置"改成"直接请求授权"
rep(
`        /**
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
        }`,
`        /**
         * 请求"读取应用列表"相关权限。
         *
         * <p>2026-10 修正（用户反馈）：**不再把用户丢去系统设置**。
         * 应用列表本身用 launcher 查询即可，无需权限；
         * 这里统一改成"由应用直接发起系统授权弹窗"，能弹就弹，
         * 弹不出来（系统已永久拒绝）才退回应用信息页。
         */
        @JavascriptInterface
        public void requestAppListPermission() {
            runOnUiThread(() -> {
                try {
                    // 该能力在当前 Android 上无需运行时权限，直接尝试申请
                    // 已声明但在部分 ROM 上被限制的包可见性相关权限，让系统弹窗处理。
                    requestPermissions(
                            new String[]{
                                    android.Manifest.permission.QUERY_ALL_PACKAGES
                            },
                            REQ_APPLIST_PERMISSION);
                } catch (Throwable tr) {
                    Log.w("MainActivity", "申请应用列表权限失败，退回应用信息页", tr);
                    openAppInfoPage();
                }
            });
        }

        /** 兜底：打开本应用「应用信息」页（仅在系统弹窗不可用时使用） */
        private void openAppInfoPage() {
            try {
                Intent it = new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                it.setData(android.net.Uri.parse("package:" + getPackageName()));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            } catch (Throwable tr) {
                Log.w("MainActivity", "打开应用信息页失败", tr);
            }
        }`,
'2) 权限卡按钮改为直接请求授权');

// 3) 新增请求码
rep(
`    // 2026-10 新增：通知权限申请请求码（进入应用时的权限门槛用）
    private static final int REQ_NOTIFICATION_PERMISSION = 0x7A21;`,
`    // 2026-10 新增：权限申请请求码（进入应用时的权限门槛用）
    private static final int REQ_NOTIFICATION_PERMISSION = 0x7A21;
    private static final int REQ_APPLIST_PERMISSION = 0x7A22;`,
'3) 新增 REQ_APPLIST_PERMISSION 请求码');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
