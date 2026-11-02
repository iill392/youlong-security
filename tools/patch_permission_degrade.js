// ==========================================================================
// ProtectService 权限降级逻辑（用户需求 2026-10）
//
// 需求：
//   运行中无障碍 / 悬浮窗权限被收回时：
//     · 无障碍没了 → 触发方式自动切到「摇一摇」，Toast 提示用户改用摇一摇
//     · 悬浮窗没了 → Toast 提示
//   只要用户触发（摇一摇/音量键），流程照旧；但没有悬浮窗时
//   弹不出覆盖层 → 直接跳转到本应用（MainActivity）里继续流程。
// ==========================================================================
const fs = require('fs');
const P = 'E:/anquan/app/src/main/java/com/youlong/hd/ProtectService.java';
let t = fs.readFileSync(P, 'utf8');
const orig = t.length;

function rep(from, to, label) {
  const n = t.split(from).length - 1;
  if (n !== 1) { console.log('!! ' + label + ' 匹配 ' + n + ' 次，跳过'); return false; }
  t = t.replace(from, to);
  console.log('OK ' + label);
  return true;
}

// ---------------------------------------------------------------- 1) 新增字段
// 放在 dailyForceStopLoop 字段旁边（同一批运行期状态）
rep(
`    private volatile boolean dailyForceStopLoop = false;`,
`    private volatile boolean dailyForceStopLoop = false;

    // ===== 2026-10 新增（用户需求）：运行期权限降级状态 =====
    // 记录上一轮"无障碍是否可用"，只在**状态发生变化**时才提示与切换，
    // 否则每 4 秒一轮的巡检会不停弹 Toast、反复覆盖用户的音量键开关设置。
    private Boolean lastA11yAvailable = null;
    // 无障碍丢失时若用户原本开着音量键触发，记下来，权限恢复后还原
    private boolean volTriggerSuspendedForA11y = false;`,
'A) 新增降级状态字段');

// ---------------------------------------------------------------- 2) 巡检调用
rep(
`                // ===== 病毒库循环检测（每 4 秒一轮，每 50 轮重新拉取服务器数据）=====
                if (anyTriggerOn()) {
                    try {
                        virusScanOnce();
                    } catch (Exception e) {
                        Log.e(TAG, "病毒库检测异常", e);
                    }
                }`,
`                // ===== 病毒库循环检测（每 4 秒一轮，每 50 轮重新拉取服务器数据）=====
                if (anyTriggerOn()) {
                    try {
                        virusScanOnce();
                    } catch (Exception e) {
                        Log.e(TAG, "病毒库检测异常", e);
                    }
                }

                // ===== 2026-10 新增（用户需求）：权限降级巡检 =====
                // 无障碍 / 悬浮窗权限可能在运行中被系统或用户收回，
                // 这里每轮（4 秒）检一次状态并做相应降级，见 monitorPermissionDegrade()。
                try {
                    monitorPermissionDegrade();
                } catch (Exception e) {
                    Log.e(TAG, "权限降级巡检异常", e);
                }`,
'B) tick 循环里加入巡检');

// ---------------------------------------------------------------- 3) 新增方法
rep(
`    /** 检测本应用的无障碍服务是否开启 */
    private boolean isAccessibilityServiceEnabled() {`,
`    // ======================================================================
    // 2026-10 新增（用户需求）：运行期权限降级
    // ----------------------------------------------------------------------
    // 场景一：无障碍权限丢了
    //   → 触发方式自动切换为「摇一摇」（摇一摇不依赖无障碍，靠传感器）
    //   → Toast 提示用户「已切换为摇一摇，摇动手机即可」
    //   → 顺带把音量键触发关掉：音量键的检测依赖无障碍按键事件，
    //     无障碍没了它其实点不动，留着开关是骗用户
    //   → 权限恢复后，若原先开着音量键触发，自动还原
    //
    // 场景二：悬浮窗权限丢了
    //   → Toast 提示用户「弹窗无法显示」
    //   → 触发流程本身不变（见 launchAppForRescue()：直接跳转到本应用）
    //
    // 两个都丢 → 提示里两句都带上。
    //
    // 只在**状态发生变化**时提示/切换，避免每 4 秒刷一次 Toast。
    // ======================================================================
    private void monitorPermissionDegrade() {
        final boolean a11yOk = isAccessibilityServiceEnabled();
        final boolean overlayOk = canDrawOverlays();
        final SharedPreferences sp = getSharedPreferences("shield_prefs", MODE_PRIVATE);

        // 悬浮窗是"用的时候才发现"，这里只做提示，不改任何开关
        boolean overlayChanged = (lastOverlayAvailable != null && lastOverlayAvailable != overlayOk);
        lastOverlayAvailable = overlayOk;

        boolean a11yChanged = (lastA11yAvailable != null && lastA11yAvailable != a11yOk);

        if (a11yChanged) {
            if (!a11yOk) {
                // —— 无障碍丢失：切换为摇一摇 ——
                Log.w(TAG, "权限降级：无障碍已丢失，触发方式自动切换为摇一摇");
                sp.edit().putBoolean("shake_trigger_on", true).apply();
                if (sp.getBoolean("volume_trigger_on", false)) {
                    volTriggerSuspendedForA11y = true;
                    sp.edit().putBoolean("volume_trigger_on", false).apply();
                    Log.w(TAG, "权限降级：音量键触发依赖无障碍按键事件，已临时关闭");
                }
                final String msg = volTriggerSuspendedForA11y
                        ? "无障碍权限已丢失，已自动切换为摇一摇触发（摇动手机即可），音量键触发暂时不可用"
                        : "无障碍权限已丢失，已自动切换为摇一摇触发（摇动手机即可）";
                h.post(() -> Toast.makeText(ProtectService.this, msg, Toast.LENGTH_LONG).show());
            } else {
                // —— 无障碍恢复：还原音量键触发 ——
                Log.i(TAG, "权限恢复：无障碍已重新开启");
                if (volTriggerSuspendedForA11y) {
                    volTriggerSuspendedForA11y = false;
                    sp.edit().putBoolean("volume_trigger_on", true).apply();
                    Log.i(TAG, "权限恢复：音量键触发已还原");
                }
                h.post(() -> Toast.makeText(ProtectService.this,
                        "无障碍权限已恢复，防护功能回到正常状态", Toast.LENGTH_LONG).show());
            }
        }

        if (overlayChanged) {
            if (!overlayOk) {
                Log.w(TAG, "权限降级：悬浮窗已丢失，覆盖层将无法显示（触发时会直接跳转到本应用）");
                h.post(() -> Toast.makeText(ProtectService.this,
                        "悬浮窗权限已丢失：拦截弹窗将无法显示，触发时会直接跳转到游龙安全护盾",
                        Toast.LENGTH_LONG).show());
            } else {
                Log.i(TAG, "权限恢复：悬浮窗已重新开启");
                h.post(() -> Toast.makeText(ProtectService.this,
                        "悬浮窗权限已恢复，拦截弹窗可正常显示", Toast.LENGTH_LONG).show());
            }
        }

        // 两个都没了 → 额外给一句合并提示，别让用户只看到其中一条
        if (a11yChanged && overlayChanged && !a11yOk && !overlayOk) {
            h.post(() -> Toast.makeText(ProtectService.this,
                    "无障碍与悬浮窗权限都已丢失：请摇动手机触发，触发后会直接跳转到游龙安全护盾",
                    Toast.LENGTH_LONG).show());
        }

        lastA11yAvailable = a11yOk;
    }

    /** 悬浮窗（SYSTEM_ALERT_WINDOW）权限是否可用 */
    private boolean canDrawOverlays() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return android.provider.Settings.canDrawOverlays(this);
            }
            return true;   // Android 6 以下安装即授予
        } catch (Throwable tr) {
            return false;
        }
    }

    /**
     * 弹不出覆盖层时的兜底：**直接跳转到本应用**（用户需求）。
     *
     * <p>正常路径是 WindowManager 覆盖层，不需要任何 Activity；
     * 但悬浮窗权限被收回后会抛 SecurityException。此时：
     *   1. 先用 startActivity 试（Android 10+ 后台启动 Activity 需要
     *      SYSTEM_ALERT_WINDOW，而这正是刚丢的权限，所以多半会失败）；
     *   2. 失败则发一个**全屏 Intent 通知**（USE_FULL_SCREEN_INTENT，
     *      锁屏/后台也能拉起界面），点它即进入本应用；
     *   3. 同时用普通通知留一个入口，保证用户一定能进来。
     *
     * <p>无论走哪条路，进的都是 MainActivity —— 也就是用户要的
     * 「跳转到我们的安全护盾里」，进去之后接着走原来的流程。
     */
    private void launchAppForRescue(String reason) {
        Log.w(TAG, "悬浮窗不可用，改为直接跳转到本应用：reason=" + reason);

        // ---- 1) 直接 startActivity ----
        try {
            Intent it = new Intent(ProtectService.this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            it.putExtra("rescue_from_service", true);
            it.putExtra("rescue_reason", reason);
            startActivity(it);
            Log.i(TAG, "已直接拉起 MainActivity 继续救援流程");
            return;
        } catch (Throwable tr) {
            Log.w(TAG, "直接 startActivity 被系统拦截（后台启动限制），改用全屏通知", tr);
        }

        // ---- 2) 全屏 Intent 通知（后台/锁屏也能弹界面）----
        try {
            launchAppForRescueByNotification(reason);
        } catch (Throwable tr) {
            Log.e(TAG, "全屏通知兜底也失败", tr);
        }
    }

    /** 用通知把用户带回本应用（全屏 Intent + 普通通知双保险） */
    private void launchAppForRescueByNotification(String reason) {
        final String CH = "rescue_channel";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "紧急拦截救援", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("悬浮窗不可用时，点击进入应用继续拦截流程");
            nm.createNotificationChannel(ch);
        }

        Intent full = new Intent(ProtectService.this, MainActivity.class);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        full.putExtra("rescue_from_service", true);
        full.putExtra("rescue_reason", reason);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(
                ProtectService.this, 0x5E5C, full, piFlags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("检测到威胁，需要你确认")
                .setContentText("悬浮窗不可用，点此进入游龙安全护盾继续拦截")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("悬浮窗权限不可用，无法直接弹出拦截窗口。\\n"
                                + "点击本通知进入「游龙安全护盾」继续处理。\\n触发原因：" + reason))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi);

        try {
            b.setFullScreenIntent(pi, true);
        } catch (Throwable ignored) {}

        try {
            nm.notify(0x5E5C, b.build());
            Log.i(TAG, "已发全屏通知，引导用户进入应用继续救援");
        } catch (Throwable tr) {
            Log.e(TAG, "发通知失败", tr);
        }
    }

    /** 检测本应用的无障碍服务是否开启 */
    private boolean isAccessibilityServiceEnabled() {`,
'C) 新增 monitorPermissionDegrade / canDrawOverlays / launchAppForRescue');

// ---------------------------------------------------------------- 4) 覆盖层失败兜底
rep(
`            wm.addView(root, lp);
            Log.i(TAG, "直接覆盖层弹窗已显示: " + pkg);
        } catch (SecurityException e) {
            Log.w(TAG, "覆盖层权限不足(SYSTEM_ALERT_WINDOW), fallback通知: " + pkg, e);
        } catch (Exception e) {
            Log.e(TAG, "showWarnOverlay 失败", e);
        }`,
`            wm.addView(root, lp);
            Log.i(TAG, "直接覆盖层弹窗已显示: " + pkg);
        } catch (SecurityException e) {
            // 2026-10 改动（用户需求）：原本这里只打一条日志（等于用户什么都看不到）。
            // 现在改为**直接跳转到本应用**继续流程 —— 悬浮窗没了，覆盖层弹不出来，
            // 但"流程还是那个流程"，只是把承载流程的界面从覆盖层换成应用自己。
            Log.w(TAG, "覆盖层权限不足(SYSTEM_ALERT_WINDOW)，改为跳转本应用: " + pkg, e);
            launchAppForRescue(reason != null && !reason.isEmpty() ? reason : "检测到威胁");
        } catch (Exception e) {
            Log.e(TAG, "showWarnOverlay 失败，改为跳转本应用", e);
            launchAppForRescue(reason != null && !reason.isEmpty() ? reason : "检测到威胁");
        }`,
'D) showWarnOverlay 失败时跳转本应用');

// 新增字段 lastOverlayAvailable（补进字段块）
rep(
`    // 无障碍丢失时若用户原本开着音量键触发，记下来，权限恢复后还原
    private boolean volTriggerSuspendedForA11y = false;`,
`    // 无障碍丢失时若用户原本开着音量键触发，记下来，权限恢复后还原
    private boolean volTriggerSuspendedForA11y = false;
    // 上一轮"悬浮窗是否可用"
    private Boolean lastOverlayAvailable = null;`,
'E) 新增 lastOverlayAvailable 字段');

fs.writeFileSync(P, t, 'utf8');
console.log('\n' + orig + ' -> ' + t.length + ' bytes');
