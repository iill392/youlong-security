// ==========================================================================
// 按用户提供的方法实现「允许读取应用列表」弹窗（国内 ROM 专用权限）
//
//   ① 清单声明 com.android.permission.GET_INSTALLED_APPS（已单独改）
//   ② 用 oem_installed_apps_runtime_permission_enable 判断 ROM 是否支持
//   ③ 支持 → checkSelfPermission 判未授予 → requestPermissions 弹系统框
//
// 同时保留 QUERY_ALL_PACKAGES 那条路（海外版 / 原生 Android 靠它）。
// ==========================================================================
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8').replace(/\r\n/g, '\n');
const orig = t.length;

function rep(from, to, label) {
  const n = t.split(from).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次'); return false; }
  t = t.replace(from, to);
  console.log('OK ' + label);
  return true;
}

// 1) 常量 + 新权限判断／申请方法
rep(
`    /** 读取应用列表权限是否已授予 */
    private boolean hasAppListPermissionOuter() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            return checkSelfPermission(android.Manifest.permission.QUERY_ALL_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable tr) {
            return false;
        }
    }`,
`    /** 国内 ROM 的「读取已安装应用列表」权限名（vivo / 小米 / OPPO 等） */
    private static final String PERM_GET_INSTALLED_APPS =
            "com.android.permission.GET_INSTALLED_APPS";

    /** 小米系 ROM 的应用列表权限提供者包名 */
    private static final String MIUI_PERM_PROVIDER = "com.lbe.security.miui";

    /**
     * 当前 ROM 是否支持动态申请「读取已安装应用列表」（2026-10 新增）。
     *
     * <p>并非所有国内系统版本都支持，海外版（原生 Android）没有这个权限，
     * 所以申请前必须先判断：
     *   · vivo / 小米 / OPPO 等国内 ROM 的通用判断方式 —— 读系统隐藏配置项
     *     {@code oem_installed_apps_runtime_permission_enable}；
     *   · 小米另有判断方式：检查权限提供者包名是否为 {@code com.lbe.security.miui}。
     */
    private boolean isRuntimePermissionEnable() {
        try {
            final int v = android.provider.Settings.Secure.getInt(
                    getContentResolver(),
                    "oem_installed_apps_runtime_permission_enable", 0);
            if (v > 0) return true;
        } catch (Throwable ignored) {}
        // 小米兜底：装了 MIUI 的权限提供者就认为支持
        try {
            return isInstalledPkg(MIUI_PERM_PROVIDER);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 国内 ROM 的 GET_INSTALLED_APPS 是否已授予 */
    private boolean hasOemInstalledAppsPermission() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            return checkSelfPermission(PERM_GET_INSTALLED_APPS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable tr) {
            return false;
        }
    }

    /**
     * 读取应用列表权限是否已授予（两条路互补）。
     *
     * <p>海外版 / 原生 Android：靠 {@code QUERY_ALL_PACKAGES}（安装即授予）；
     * <p>国内 ROM：靠 {@code com.android.permission.GET_INSTALLED_APPS}（需用户授权）。
     * 任一满足即认为可用。
     */
    private boolean hasAppListPermissionOuter() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            // 国内 ROM 的权限：支持该机制时必须已授予
            if (isRuntimePermissionEnable()) {
                return hasOemInstalledAppsPermission();
            }
            // 原生 Android：QUERY_ALL_PACKAGES 安装即授予
            return checkSelfPermission(android.Manifest.permission.QUERY_ALL_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable tr) {
            return false;
        }
    }

    /**
     * 申请「读取已安装应用列表」权限（国内 ROM 会弹系统授权框）。
     *
     * @return true = 已发起申请（或无需申请）；false = 该 ROM 不支持该机制、
     *         或已被永久拒绝，需要引导用户去应用信息页手动打开
     */
    private boolean requestOemInstalledAppsPermission() {
        try {
            if (!isRuntimePermissionEnable()) {
                Log.w("MainActivity", "该 ROM 不支持动态申请读取应用列表（无 oem 开关），走兜底引导");
                return false;
            }
            if (hasOemInstalledAppsPermission()) {
                Log.i("MainActivity", "读取应用列表权限已授予，无需申请");
                return true;
            }
            Log.i("MainActivity", "发起申请：" + PERM_GET_INSTALLED_APPS);
            requestPermissions(new String[]{PERM_GET_INSTALLED_APPS}, REQ_OEM_APPLIST_PERMISSION);
            return true;
        } catch (Throwable tr) {
            Log.w("MainActivity", "申请 " + PERM_GET_INSTALLED_APPS + " 失败", tr);
            return false;
        }
    }`,
'1) 新增国内 ROM 权限判断与申请方法');

// 2) 启动队列：国内 ROM 用 GET_INSTALLED_APPS
rep(
`            if (!hasAppListPermissionOuter()) {
                entryPermQueue.add(android.Manifest.permission.QUERY_ALL_PACKAGES);
            }`,
`            if (!hasAppListPermissionOuter()) {
                // 国内 ROM：走厂商的 GET_INSTALLED_APPS（会弹「允许读取应用列表」）
                // 原生 / 海外版：走 QUERY_ALL_PACKAGES（安装即授予，不会弹框）
                if (isRuntimePermissionEnable()) {
                    entryPermQueue.add(PERM_GET_INSTALLED_APPS);
                } else {
                    entryPermQueue.add(android.Manifest.permission.QUERY_ALL_PACKAGES);
                }
            }`,
'2) 启动队列按 ROM 选择权限');

// 3) 回调：处理国内 ROM 权限结果
rep(
`        // 2026-10 新增（用户需求）：一打开应用时的顺序权限请求 —— 弹完一个接着弹下一个
        if (requestCode == REQ_ENTRY_PERMISSION) {`,
`        // 2026-10 新增：国内 ROM「读取应用列表」权限的申请结果
        if (requestCode == REQ_OEM_APPLIST_PERMISSION) {
            boolean granted = false;
            try {
                granted = grantResults != null && grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {}
            Log.i("MainActivity", "读取应用列表权限结果：granted=" + granted);
            if (!granted) {
                // 用户拒绝 → 引导到应用信息页手动打开
                openAppInfoPageOuter();
            }
            notifyWebPermissions();
            return;
        }

        // 2026-10 新增（用户需求）：一打开应用时的顺序权限请求 —— 弹完一个接着弹下一个
        if (requestCode == REQ_ENTRY_PERMISSION) {`,
'3) 回调处理国内 ROM 权限结果');

// 4) 请求码
rep(
`    private static final int REQ_ENTRY_PERMISSION = 0x7A23;`,
`    private static final int REQ_ENTRY_PERMISSION = 0x7A23;
    // 国内 ROM「读取已安装应用列表」专用权限（com.android.permission.GET_INSTALLED_APPS）
    private static final int REQ_OEM_APPLIST_PERMISSION = 0x7A24;`,
'4) 新增请求码');

// 5) 网页按钮改调国内 ROM 方案
rep(
`        public void requestAppListPermission() {
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
        }`,
`        public void requestAppListPermission() {
            runOnUiThread(() -> {
                // 优先走国内 ROM 的 GET_INSTALLED_APPS（会弹「允许读取应用列表」）；
                // 该 ROM 不支持时退回 QUERY_ALL_PACKAGES；两者都不行才引导到应用信息页。
                if (MainActivity.this.requestOemInstalledAppsPermission()) {
                    return;
                }
                try {
                    requestPermissions(
                            new String[]{android.Manifest.permission.QUERY_ALL_PACKAGES},
                            REQ_APPLIST_PERMISSION);
                } catch (Throwable tr) {
                    Log.w("MainActivity", "申请应用列表权限失败，退回应用信息页", tr);
                    openAppInfoPage();
                }
            });
        }`,
'5) 网页按钮改走国内 ROM 方案');

// 6) 兜底方法也走国内 ROM 方案
rep(
`            if (android.Manifest.permission.QUERY_ALL_PACKAGES.equals(deniedPermission)) {
                Log.w("MainActivity", "读取应用列表权限未获得，跳到应用信息页引导用户手动开启");
                openAppInfoPageOuter();
            }`,
`            final boolean appListDenied =
                    android.Manifest.permission.QUERY_ALL_PACKAGES.equals(deniedPermission)
                            || PERM_GET_INSTALLED_APPS.equals(deniedPermission);
            if (appListDenied) {
                Log.w("MainActivity", "读取应用列表权限未获得，跳到应用信息页引导用户手动开启");
                openAppInfoPageOuter();
            }`,
'6) 兜底识别国内 ROM 权限');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
