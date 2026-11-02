// ==========================================================================
// 按用户要求改回并增强：一打开应用就弹「允许通知」→ 再弹「允许读取应用列表」
//
// 依据（实测 + 网上资料）：
//   · 标准 Android 上 QUERY_ALL_PACKAGES 是 normal 权限（安装即授予），
//     requestPermissions() 不会弹窗 —— 这是本机（Android 14）实测到的现象。
//   · 但**国内 ROM（华为/HyperOS/ColorOS 等）把它当敏感权限**，会弹窗让用户授权，
//     华为应用市场的审核文档里就明确有「提前向用户弹窗申请开启【读取已安装
//     应用列表】权限」这一条；sdmaid-se 也专门为这些 ROM 修过应用扫描。
//
// 所以这里**照常发起运行时请求**：
//   · ROM 支持 → 弹出授权框，用户点「允许」→ 授权完成；
//   · ROM 不支持（普通 Android）→ 系统立刻回调"已授予"，不会弹框、也不会卡住；
//   · 两种情况下，如果最终没拿到权限，就**自动跳到本应用的应用信息页**，
//     让用户在权限列表里打开「应用列表 / 已安装应用」——
//     保证任何 ROM 上用户都有办法授权，而不是"根本获取不到"。
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

// 1) 顺序请求：通知 → 应用列表（都走运行时请求）
rep(
`    private void requestEntryPermissions() {
        try {
            entryPermQueue.clear();
            // 已授予的不再请求（Android 对已授权限也不会再弹）
            if (!hasNotificationPermissionOuter()) {
                entryPermQueue.add(android.Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!hasAppListPermissionOuter()) {
                entryPermQueue.add(android.Manifest.permission.QUERY_ALL_PACKAGES);
            }
            if (entryPermQueue.isEmpty()) {
                Log.i("MainActivity", "进入应用权限检查：无需请求");
                notifyWebPermissions();
                return;
            }
            Log.i("MainActivity", "进入应用权限检查：待请求 " + entryPermQueue.size() + " 项");
            requestNextEntryPermission();
        } catch (Throwable tr) {
            Log.w("MainActivity", "requestEntryPermissions 失败（已忽略）", tr);
        }
    }`,
`    private void requestEntryPermissions() {
        try {
            entryPermQueue.clear();
            // ① 允许通知
            if (!hasNotificationPermissionOuter()) {
                entryPermQueue.add(android.Manifest.permission.POST_NOTIFICATIONS);
            }
            // ② 允许读取应用列表
            //
            // ⚠️ 说明：在标准 Android 上 QUERY_ALL_PACKAGES 是 normal 权限（安装即授予），
            //    系统不会弹框；但在**国内 ROM（华为 / HyperOS / ColorOS 等）**上它被当作
            //    敏感权限，会向用户弹窗要求授权（华为应用市场审核文档里就明确有这一条）。
            //    因此这里照常发起运行时请求：
            //      · ROM 支持 → 弹出授权框，用户点允许即完成；
            //      · ROM 不支持 → 系统立刻回调"已授予"，不弹框也不卡流程；
            //      · 若最终仍未拿到权限，handleEntryPermissionResult 会自动跳到
            //        应用信息页让用户手动打开，保证任何 ROM 上都有办法授权。
            if (!hasAppListPermissionOuter()) {
                entryPermQueue.add(android.Manifest.permission.QUERY_ALL_PACKAGES);
            }
            if (entryPermQueue.isEmpty()) {
                Log.i("MainActivity", "进入应用权限检查：无需请求");
                notifyWebPermissions();
                return;
            }
            Log.i("MainActivity", "进入应用权限检查：待请求 " + entryPermQueue.size() + " 项");
            requestNextEntryPermission();
        } catch (Throwable tr) {
            Log.w("MainActivity", "requestEntryPermissions 失败（已忽略）", tr);
        }
    }`,
'1) 顺序请求注释与逻辑（含应用列表）');

// 2) 权限回调：应用列表拿不到就引导到应用信息页
rep(
`        // 2026-10 新增（用户需求）：一打开应用时的顺序权限请求 —— 弹完一个接着弹下一个
        if (requestCode == REQ_ENTRY_PERMISSION) {
            String grantedState = "unknown";
            try {
                if (permissions != null && grantResults != null && permissions.length > 0) {
                    grantedState = permissions[0] + "=" + (grantResults[0] == PackageManager.PERMISSION_GRANTED);
                }
            } catch (Throwable ignored) {}
            Log.i("MainActivity", "权限结果：" + grantedState
                    + "，剩余待请求 " + entryPermQueue.size() + " 项");
            // 继续弹下一个（通知 → 应用列表）
            requestNextEntryPermission();
            return;
        }`,
`        // 2026-10 新增（用户需求）：一打开应用时的顺序权限请求 —— 弹完一个接着弹下一个
        if (requestCode == REQ_ENTRY_PERMISSION) {
            String deniedPermission = null;
            try {
                if (permissions != null && grantResults != null && permissions.length > 0) {
                    final boolean granted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                    Log.i("MainActivity", "权限结果：" + permissions[0] + "=" + granted
                            + "，剩余待请求 " + entryPermQueue.size() + " 项");
                    if (!granted) deniedPermission = permissions[0];
                }
            } catch (Throwable ignored) {}
            // 继续弹下一个（通知 → 应用列表）
            requestNextEntryPermission();
            // 队列走完后再处理"被拒绝"的兜底
            if (entryPermQueue.isEmpty()) {
                handleEntryPermissionResult(deniedPermission);
            }
            return;
        }`,
'2) 回调里记录被拒权限并兜底');

// 3) 新增兜底方法
rep(
`    /** 把权限状态回灌网页，让界面上的引导卡片同步刷新 */`,
`    /**
     * 顺序请求结束后：若「读取应用列表」仍不可用，就引导用户去应用信息页手动打开。
     *
     * <p>为什么需要这一步：国内 ROM 上该权限会弹窗（用户点允许即可）；
     * 但标准 Android 上它不可弹窗，而个别 ROM 又默认关掉 —— 这时界面上会
     * 一直显示"读取已安装应用"缺失。给一个直达应用信息页的入口，
     * 用户总能自己把它打开，不会出现"根本获取不到"的死胡同。
     */
    private void handleEntryPermissionResult(String deniedPermission) {
        try {
            if (android.Manifest.permission.QUERY_ALL_PACKAGES.equals(deniedPermission)) {
                Log.w("MainActivity", "读取应用列表权限未获得，跳到应用信息页引导用户手动开启");
                openAppInfoPage();
            }
            // 通知权限被拒：不强制跳转，网页上的卡片会提示用户，点按钮可再次请求
            notifyWebPermissions();
        } catch (Throwable tr) {
            Log.w("MainActivity", "handleEntryPermissionResult 失败（已忽略）", tr);
        }
    }

    /** 把权限状态回灌网页，让界面上的引导卡片同步刷新 */`,
'3) 新增 handleEntryPermissionResult');

// 4) 网页侧的"检查读取应用权限"按钮改为调用运行时请求（恢复该入口）
rep(
`        @JavascriptInterface
        public void openAppListPermissionSettings() {`,
`        /**
         * 请求「读取应用列表」权限（网页上的按钮调这个）。
         * 国内 ROM 会弹授权框；标准 Android 会立刻回调已授予；
         * 两者都不会卡住，拿不到时 handleEntryPermissionResult 会引导到应用信息页。
         */
        @JavascriptInterface
        public void requestAppListPermissionFromWeb() {
            runOnUiThread(() -> {
                try {
                    MainActivity.this.requestPermissions(
                            new String[]{android.Manifest.permission.QUERY_ALL_PACKAGES},
                            REQ_ENTRY_PERMISSION);
                } catch (Throwable tr) {
                    Log.w("MainActivity", "网页请求应用列表权限失败", tr);
                    openAppInfoPage();
                }
            });
        }

        @JavascriptInterface
        public void openAppListPermissionSettings() {`,
'4) 新增网页可调的应用列表权限请求入口');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
