// ==========================================================================
// 需求：一打开软件就**必须先弹「允许通知」**，再弹「允许读取应用列表」，
//       两个都必须由系统弹窗让用户授权。
//
// 原因（为什么原来"不是刚打开就弹"）：之前的请求发生在网页 init()/桥接就绪后，
// 时序上晚了一拍。现在改成**原生 onCreate 里直接发起**，不依赖 WebView。
//
// 实现：
//   1. onCreate 里调用 requestEntryPermissions()；
//   2. 先 POST_NOTIFICATIONS（Android 13+ 才需要），拿到结果后再请求下一个；
//   3. 再 QUERY_ALL_PACKAGES（读取已安装应用列表）；
//   4. 全部结束后把最终状态回灌网页（__dshOnPermResult），界面同步刷新；
//   5. 已经授予过的权限不再重复弹（Android 也不会重复弹）。
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

// 1) onCreate 里发起（放在 setupWindowInsets 之后，确保 Activity 已就绪）
rep(
`        // 2026-10 新增：显式预留系统栏区域。Android 15+ 强制 edge-to-edge 后，
        // 不自己消费 WindowInsets 就会被状态栏/手势条压住内容。
        setupWindowInsets();`,
`        // 2026-10 新增：显式预留系统栏区域。Android 15+ 强制 edge-to-edge 后，
        // 不自己消费 WindowInsets 就会被状态栏/手势条压住内容。
        setupWindowInsets();

        // ==================================================================
        // 2026-10 新增（用户需求）：**一打开应用就先弹权限**
        // ------------------------------------------------------------------
        // 顺序：① 允许通知 → ② 允许读取应用列表。
        // 之前放在网页 init()/桥接就绪后请求，时序晚了一拍（表现为"不是刚打开就弹"）；
        // 现在改在原生 onCreate 里直接发起，不依赖 WebView。
        // 用 post 到主线程消息队列，确保窗口已经绑定、系统弹窗能正常出现。
        // ==================================================================
        window.getDecorView().post(() -> requestEntryPermissions());`,
'1) onCreate 里发起权限请求');

// 2) 新增顺序请求逻辑
rep(
`    // ======================================================================
    // 2026-10 新增：显式预留系统栏区域`,
`    // ======================================================================
    // 2026-10 新增（用户需求）：一打开应用就顺序请求两项权限
    // ----------------------------------------------------------------------
    //   ① POST_NOTIFICATIONS（Android 13+ 才是运行时权限）→ 系统弹「允许通知」
    //   ② QUERY_ALL_PACKAGES（读取已安装应用列表）
    // 逐个请求：上一个有结果后再发下一个，保证"先通知、后应用列表"的顺序。
    // ======================================================================
    private void requestEntryPermissions() {
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
    }

    /** 依次弹出下一个权限请求 */
    private void requestNextEntryPermission() {
        if (entryPermQueue.isEmpty()) {
            notifyWebPermissions();
            return;
        }
        final String permission = entryPermQueue.removeAt(0);
        try {
            Log.i("MainActivity", "请求权限：" + permission);
            requestPermissions(new String[]{permission}, REQ_ENTRY_PERMISSION);
        } catch (Throwable tr) {
            Log.w("MainActivity", "请求权限失败：" + permission, tr);
            requestNextEntryPermission();
        }
    }

    /** 把权限状态回灌网页，让界面上的引导卡片同步刷新 */
    private void notifyWebPermissions() {
        try {
            final String js =
                    "if(window.__dshOnPermResult)window.__dshOnPermResult("
                            + buildEntryRequirementsJson() + ");";
            if (webView != null) {
                webView.post(() -> {
                    try { webView.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable tr) {
            Log.w("MainActivity", "回灌权限状态失败", tr);
        }
    }

    /** 读取应用列表权限是否已授予 */
    private boolean hasAppListPermissionOuter() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            return checkSelfPermission(android.Manifest.permission.QUERY_ALL_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable tr) {
            return false;
        }
    }

    // ======================================================================
    // 2026-10 新增：显式预留系统栏区域`,
'2) 新增顺序请求逻辑');

// 3) 字段：请求码 + 队列
rep(
`    // 2026-10 新增：权限申请请求码（进入应用时的权限门槛用）
    private static final int REQ_NOTIFICATION_PERMISSION = 0x7A21;
    private static final int REQ_APPLIST_PERMISSION = 0x7A22;`,
`    // 2026-10 新增：权限申请请求码（进入应用时的权限门槛用）
    private static final int REQ_NOTIFICATION_PERMISSION = 0x7A21;
    private static final int REQ_APPLIST_PERMISSION = 0x7A22;
    // 一打开应用就顺序请求权限：共用一个请求码，靠队列区分是第几个
    private static final int REQ_ENTRY_PERMISSION = 0x7A23;

    /** 待请求的权限队列（先通知、后应用列表） */
    private final java.util.ArrayDeque<String> entryPermQueue = new java.util.ArrayDeque<>();`,
'3) 新增请求码与队列字段');

// 4) 权限回调里处理队列
rep(
`    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // 2026-10 新增：通知权限申请结果回灌网页，让"进入应用权限门槛"卡片立即刷新
        if (requestCode == REQ_NOTIFICATION_PERMISSION) {`,
`    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // 2026-10 新增（用户需求）：一打开应用时的顺序权限请求 —— 弹完一个接着弹下一个
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
        }

        // 2026-10 新增：通知权限申请结果回灌网页，让"进入应用权限门槛"卡片立即刷新
        if (requestCode == REQ_NOTIFICATION_PERMISSION) {`,
'4) 权限回调处理队列');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
