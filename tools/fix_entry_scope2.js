// ==========================================================================
// 修复作用域 v2：锚点要用 CRLF
// ==========================================================================
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/MainActivity.java';
let t = fs.readFileSync(P, 'utf8');

function extract(startMarker) {
  const i = t.indexOf(startMarker);
  if (i < 0) return null;
  let start = t.lastIndexOf('\n', i) + 1;
  let j = t.indexOf('{', i);
  if (j < 0) return null;
  let depth = 0, k = j;
  for (; k < t.length; k++) {
    const c = t[k];
    if (c === '{') depth++;
    else if (c === '}') { depth--; if (depth === 0) { k++; break; } }
  }
  return { text: t.slice(start, k), start, end: k };
}

const sigs = [
  'public String getEntryRequirements() {',
  'private boolean hasNotificationPermission() {',
  'private boolean hasAppListAccess() {',
  'public String getEnvGoodHint() {',
];
for (const s of sigs) {
  if (!extract(s)) { console.log('!! 找不到 ' + s); process.exit(1); }
}

const delegate = {
  'public String getEntryRequirements() {':
`public String getEntryRequirements() {
            return MainActivity.this.buildEntryRequirementsJson();
        }`,
  'private boolean hasNotificationPermission() {':
`private boolean hasNotificationPermission() {
            return MainActivity.this.hasNotificationPermissionOuter();
        }`,
  'private boolean hasAppListAccess() {':
`private boolean hasAppListAccess() {
            return MainActivity.this.hasAppListAccessOuter();
        }`,
  'public String getEnvGoodHint() {':
`public String getEnvGoodHint() {
            return MainActivity.this.buildEnvGoodHintJson();
        }`,
};

for (const [sig, body] of Object.entries(delegate)) {
  const r = extract(sig);
  t = t.slice(0, r.start) + body + t.slice(r.end);
  console.log('OK 内部类委托: ' + sig.replace('() {', ''));
}

const outerImpl = `
    // ======================================================================
    // 2026-10 新增（用户需求）：进入应用权限门槛 —— 外层实现
    // ----------------------------------------------------------------------
    // 这些判定放在 MainActivity 外层而不是 JavaScriptInterface 内部，
    // 是因为 onRequestPermissionsResult()（Activity 回调）也要用它们；
    // 内部类里的同名方法只做委托，保证只有一份实现。
    // ======================================================================

    /** 组装权限门槛 JSON，供网页与权限回调共用 */
    private String buildEntryRequirementsJson() {
        boolean notif = hasNotificationPermissionOuter();
        boolean apps = hasAppListAccessOuter();
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

    /** 通知权限是否可用（Android 13+ 需运行时授予；12L 及以下安装即授予） */
    private boolean hasNotificationPermissionOuter() {
        try {
            if (Build.VERSION.SDK_INT < 33) return true;
            return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable tr) {
            return true;
        }
    }

    /**
     * 读取已安装应用的能力是否真的可用。
     * 不读 flag，而是实际查一次包列表：Android 11+ 若 QUERY_ALL_PACKAGES 被关掉，
     * getInstalledApplications 会返回被过滤后极短的列表。
     */
    private boolean hasAppListAccessOuter() {
        try {
            List<ApplicationInfo> list = getPackageManager().getInstalledApplications(0);
            return list != null && list.size() >= 5;
        } catch (Throwable tr) {
            return false;
        }
    }

    /** 包是否已安装（外层版本，供环境判定复用） */
    private boolean isInstalledPkg(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否存在 su 二进制（只读探测，不执行 su） */
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
    }

    /** 是否安装了 Dhizuku */
    private boolean deviceHasDhizuku() {
        return isInstalledPkg("com.rosan.dhizuku");
    }

    /** 环境良好（绿色描边）提示的 JSON */
    private String buildEnvGoodHintJson() {
        boolean root = deviceIsRooted();
        boolean dhizuku = deviceHasDhizuku();
        boolean show = (!root && !dhizuku);
        return "{\\"show\\":" + show + ",\\"root\\":" + root
                + ",\\"dhizuku\\":" + dhizuku + "}";
    }
`;

// 锚点：CRLF 版 onCreate
const anchor = '\r\n    @Override\r\n    protected void onCreate(Bundle savedInstanceState) {';
let ai = t.indexOf(anchor);
if (ai < 0) {
  // 退一步：只认方法签名行
  const sig = '    protected void onCreate(Bundle savedInstanceState) {';
  const si = t.indexOf(sig);
  if (si < 0) { console.log('!! 仍找不到 onCreate'); process.exit(1); }
  // 往前包含 @Override 行
  ai = t.lastIndexOf('    @Override', si);
  console.log('用签名行定位 @ ' + ai);
}
t = t.slice(0, ai) + '\r\n' + outerImpl.replace(/\n/g, '\r\n') + t.slice(ai);
console.log('OK 外层类新增 6 个方法');

fs.writeFileSync(P, t, 'utf8');
console.log('最终 ' + t.length + ' bytes');
