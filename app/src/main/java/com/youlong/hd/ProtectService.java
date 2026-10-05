package com.youlong.hd;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import roro.stellar.Stellar;

/**
 * 防锁机守护服务
 *
 * 检测机制（仅拦截黑名单，不再检测高风险权限）：
 * 1. 软件级黑名单（硬编码）→ 收集全部包名，弹批量清除弹窗
 * 2. 用户黑名单 → 弹单个拦截弹窗
 * 3. 无障碍实时前台变化检测
 * 4. 音量键连按逃生（按键与次数可自定义）
 * 5. 新包安装检测
 */
public class ProtectService extends Service implements SensorEventListener {

    private static final String TAG = "ProtectService";
    private static final String CHANNEL_ID = "shield_channel";
    private static final String WARN_CHANNEL_ID = "shield_warn_channel";
    private static final int NOTIFY_ID = 1001;

    private long startTime;
    private Handler h;
    private Runnable tick;
    private Runnable notifyUpdater;
    private PowerManager.WakeLock wakeLock;
    // ⚠️ 2026-10-05：白/黑名单缓存改用并发集合。
    //   原因：每 4 秒的 loadWhitelist()/loadBlacklist() 已挪到后台线程执行，
    //   而主线程（无障碍事件、广播接收器）同时会 contains() 查询。
    //   用普通 HashSet 会出现"后台 clear() 正在扩容、主线程正在查"的数据竞争，
    //   极端情况下查到错误的黑白名单结果（该拦的没拦 / 不该拦的拦了）。
    //   newSetFromMap(ConcurrentHashMap) 的迭代器是弱一致的，不会抛 ConcurrentModificationException。
    private final Set<String> whitelistCache =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private final Set<String> blacklistCache =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private BroadcastReceiver volRcvr;
    private BroadcastReceiver pkgRcvr;
    // ===== 音量键触发（按键与次数均可由用户自定义）=====
    // 音量回调实例化一次、长期复用：tick 巡检发现它被清掉时（逃生"静默失效"的
    // 典型原因，见 ensureVolumeTriggerListener）能原样重新挂上。
    private final AdSkipService.VolumeChangeListener volumeListener =
            new AdSkipService.VolumeChangeListener() {
                @Override
                public void onVolumeChanged(int volumeType, int direction) {
                    Log.v(TAG, "无障碍音量回调 type=" + volumeType + " dir=" + direction);
                    countVolumePress(direction);
                }
            };
    // 一次音量按键记录。同一物理按键会被三层（无障碍/广播/轮询）重复上报，
    // 靠时间窗口去重；方向可能先从"未知"再来"明确"，所以 dir 可被补全。
    private static class VolPress {
        final long ts;
        int dir;          // -1 = 音量减，+1 = 音量加，0 = 方向未知
        VolPress(long ts, int dir) { this.ts = ts; this.dir = dir; }
    }
    private final List<VolPress> volPresses = new ArrayList<>();
    // 各音频流在"上一次按键判定时"的音量基线，用于推断按键方向
    private final SparseIntArray pressBaseVolumes = new SparseIntArray();
    private String lastWarnPkg = "";
    private long lastWarnTime = 0;
    private AudioManager audioManager;
    private Runnable volPollRunnable;
    // ===== 紧急救援通知循环：每5秒重发"请尽快卸载"通知直到应用卸载 =====
    // 保存引用以便护盾关闭时移除；循环内部也会检查护盾开关，关闭即停
    private Runnable rescueNotifLoop;
    private int rescueNotifId = -1;

    /**
     * 立即取消"紧急救援/请尽快卸载"通知（护盾关闭时由 MainActivity 调用）。
     * 救援通知 ID 范围固定为 2000-2999，遍历取消即可全部清掉。
     */
    public static void cancelRescueNotifications(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                for (int id = 2000; id < 3000; id++) {
                    nm.cancel(id);
                }
            }
        } catch (Exception ignored) {}
    }

    // ===== Shizuku 断连监听：自动降级为基础模式 =====
    private final Stellar.OnBinderDeadListener shizukuDeadListener = () -> {
        SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        String currentMode = prefs.getString("shield_mode", "basic");
        if ("super".equals(currentMode) || "extreme".equals(currentMode)
                || "final".equals(currentMode) || "daily".equals(currentMode)) {
            prefs.edit().putString("shield_mode", "basic").apply();
            Log.w(TAG, "Shizuku服务已断开，自动从超级/极强/终结/日常拦截降级为基础模式");
            h.post(() -> Toast.makeText(ProtectService.this,
                    "Shizuku已断开，已自动降级为基础模式", Toast.LENGTH_LONG).show());
        }
    };

    // ===== 摇动检测（原生 SensorEventListener，服务级别，后台可靠）=====
    private SensorManager sensorManager;
    private Vibrator vibrator;
    private float lastAccelX, lastAccelY, lastAccelZ;
    private long lastShakeTs = 0;
    private int shakeHitCount = 0;
    private long lastShakeRescueTime = 0;
    // ===== 摇一摇「触发力度」档位表（1=轻摇 2=标准 3=用力 4=猛摇）=====
    // 档位由用户在「高级设置 → 摇一摇触发」里选择，存 shield_prefs/shake_sensitivity，
    // onSensorChanged 每次采样都实时读取 → 改档位后无需重启服务即可生效。
    //   阈值   = 相邻两次采样的加速度变化量（m/s²）：越大越迟钝，需要摇得越猛
    //   命中数 = SHAKE_WINDOW 内需要达到的尖峰数：越大越不容易误触（走路/放口袋）
    // 数组下标 0..3 对应档位 1..4；默认档 2 与历史行为（阈值 10f / 3 次）完全一致。
    private static final float[] SHAKE_THRESHOLDS = { 7.0f, 10f, 13.5f, 18f };
    private static final int[] SHAKE_HITS_TABLE = { 3, 3, 4, 5 };
    public static final int SHAKE_LEVEL_MIN = 1;
    public static final int SHAKE_LEVEL_MAX = 4;
    public static final int SHAKE_LEVEL_DEFAULT = 4;   // 2026-10 改动（用户需求）：默认「猛摇」
    private static final long SHAKE_WINDOW = 1500;
    private static final long SHAKE_RESCUE_COOLDOWN_MS = 6000;

    /** 读取当前摇一摇力度档位（1~4）；缺省或脏数据一律回落到默认档 */
    static int readShakeLevel(SharedPreferences prefs) {
        int lv = prefs.getInt("shake_sensitivity", SHAKE_LEVEL_DEFAULT);
        if (lv < SHAKE_LEVEL_MIN) return SHAKE_LEVEL_MIN;
        if (lv > SHAKE_LEVEL_MAX) return SHAKE_LEVEL_MAX;
        return lv;
    }
    // ===== 终结模式：force-stop 反复循环控制 =====
    private volatile boolean finalForceStopLoop = false;
    // 终结模式：首次 pm list packages 解析出的待 force-stop 包名列表缓存。
    // 仅在首次执行时用于全量拦截全部应用；进入"选择可疑应用"界面后，
    // 循环每轮重新执行 pm list packages -3 检测最新第三方应用并逐个 force-stop。
    private volatile java.util.List<String> finalForceStopPkgs = null;

    // ===== 日常模式：force-stop 反复循环控制 =====
    // 弹窗出现即开始，每轮重新执行 pm list packages -3，把检出的全部第三方应用
    // 串联成 am force-stop 包名1;am force-stop 包名2;... 反复循环执行
    private volatile boolean dailyForceStopLoop = false;

    // ===== 2026-10 新增（用户需求）：运行期权限降级状态 =====
    // 记录上一轮"无障碍是否可用"，只在**状态发生变化**时才提示与切换，
    // 否则每 4 秒一轮的巡检会不停弹 Toast、反复覆盖用户的音量键开关设置。
    private Boolean lastA11yAvailable = null;
    // 无障碍丢失时若用户原本开着音量键触发，记下来，权限恢复后还原
    private boolean volTriggerSuspendedForA11y = false;
    // 无障碍丢失时若摇一摇原本是关的，是"我们替用户自动打开"的 → 记下来，
    // 权限恢复后按用户原本的选择还回去（见 monitorPermissionDegrade）。
    private boolean shakeTriggerAutoOnByDegrade = false;
    // 上一轮"悬浮窗是否可用"
    private Boolean lastOverlayAvailable = null;
    // 卸载期后台 force-stop 循环的**哨兵文件**。
    // shell 循环跑在另一个进程里，读不到上面的 Java 变量，所以用文件通信：
    // 文件存在 → 继续循环；被删除 → 循环最迟 0.5 秒后退出。
    // 见 startUninstallForceStopLoop() / stopUninstallForceStopLoop()。
    private volatile java.io.File uninstallForceStopFlag = null;
    // 日常模式覆盖层是否已挂载（同时作为 force-stop 循环的存活标志）
    private volatile boolean dailyOverlayShowing = false;
    // 用户是否已主动关闭日常模式流程（系统 API 卸载后据此判断要不要重新挂载覆盖层）
    private volatile boolean dailyAborted = false;
    // 日常模式覆盖层布局参数：系统 API 卸载需临时摘除悬浮窗（否则会遮挡系统卸载确认框），
    // 卸载结束后用这份参数重新挂载覆盖层并展示重启提示
    private WindowManager.LayoutParams dailyOverlayLp;
    // 日常模式覆盖层根视图引用：用于校验视图是否仍挂在 WindowManager 上
    // （若已脱离而 dailyOverlayShowing 仍为 true，会导致后续再也弹不出来）
    private volatile View dailyOverlayRoot;

    // ===== 病毒库识别（远程 ①~⑤ 列表 + 本地缓存 + 后台循环检测）=====
    // 每检测 VIRUS_RECHECK_EVERY 轮 → 重新拉取服务器最新数据
    private static final int VIRUS_RECHECK_EVERY = 50;
    private int virusCheckCount = 0;
    private int virusAppCacheTick = 0;
    // 待检测应用清单缓存 {包名, 应用名称}（每 10 轮或安装变化时重建）
    private volatile java.util.List<String[]> virusAppCache = null;
    // 已处理（弹窗/卸载）过的包名，避免每轮重复弹窗
    private final Set<String> virusHandledPkgs =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    // 病毒库弹窗是否正在显示（同一时间只允许一个）
    private volatile boolean virusPopupShowing = false;
    // 是否正在批量卸载（避免并发执行）
    private volatile boolean virusUninstallRunning = false;
    private View virusOverlayView = null;
    private WindowManager virusOverlayWm = null;

    @Override
    public void onCreate() {
        super.onCreate();

        // ==================================================================
        // 2026-10-05 修复（真机 ANR/闪退根因）
        // ------------------------------------------------------------------
        // 系统规定：调用 startForegroundService() 之后，服务必须在 **5 秒内**
        // 调用 startForeground()，否则系统直接抛
        //     RemoteServiceException$ForegroundServiceDidNotStartInTimeException
        // 让整个应用崩溃（用户看到的就是"划着划着突然闪退"）。
        //
        // 旧实现的问题：下面"所有开关都关"的早退路径里只调了 stopSelf() 就 return，
        // **没有调用 startForeground()** —— 系统照样在等，5 秒后必崩。
        // 服务被反复拉起（保活闹钟 / 哨兵互拉 / 开机自启）时，这个坑会反复触发。
        //
        // 修法：**进入 onCreate 的第一件事就是 startForeground()**，
        // 把前台通知挂上（用一条最简通知），后面无论走哪条分支都不会再违规。
        // 真要继续守护时，后续会用完整通知覆盖它。
        // ==================================================================
        boolean foregroundRaised = false;
        try {
            createChannels();
            startForeground(NOTIFY_ID, buildMinimalNotify());
            foregroundRaised = true;
            Log.i(TAG, "onCreate：已先行挂上前台通知（避免 5 秒超时崩溃）");
        } catch (Throwable t) {
            // 连前台通知都挂不上（极少见：通知权限被禁等），至少留档
            Log.e(TAG, "先行挂前台通知失败", t);
            CrashLogger.event("[守护服务] 先行挂前台通知失败: " + t);
        }

        // ===== 若所有功能开关均未开启，立即停止自身 =====
        // 注意：摇动触发 / 音量触发是独立功能，不依赖"防锁机守护"主开关
        SharedPreferences bootPrefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        boolean protectOn = bootPrefs.getBoolean("protect_on", false);
        boolean shakeOn = bootPrefs.getBoolean("shake_trigger_on", false);
        boolean volOn = bootPrefs.getBoolean("volume_trigger_on", false);
        if (!protectOn && !shakeOn && !volOn) {
            Log.w(TAG, "守护/摇动/音量触发均未开启，停止服务");
            CrashLogger.event("[逃生] 守护服务启动即退出：所有触发开关都是关的");
            // 注意：这里不能只 stopSelf() 就走 —— 已经挂过前台通知，先摘掉再停，
            // 否则会留一条"正在守护"的通知挂在通知栏上（用户以为还开着）。
            if (foregroundRaised) {
                try {
                    stopForeground(true);
                } catch (Throwable ignored) {
                }
            }
            stopSelf();
            return;
        }
        // 2026-10：启动即记一条轨迹，把当前四个开关的真实取值写进日志。
        // 排查"按了没反应"时，这是判断"开关没开"还是"逻辑没跑到"的第一手依据。
        CrashLogger.event("[逃生] 守护服务已启动：主开关=" + protectOn
                + " 摇一摇=" + shakeOn + " 音量键=" + volOn
                + " 自动拦截=" + bootPrefs.getBoolean("auto_block_on", true)
                + " 模式=" + bootPrefs.getString("shield_mode", "basic")
                + " 无障碍=" + isAccessibilityServiceEnabled());

        // ===== 守护起始时间：沿用用户真正"开启守护"时刻（服务重启/被拉起不重新计时）=====
        // 旧实现每次 onCreate 都覆写 protect_start_time，导致通知栏与界面上的
        // "已守护多久"反复从 0 开始。
        SharedPreferences spTime = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        startTime = spTime.getLong("protect_start_time", 0L);
        if (startTime <= 0L) {
            startTime = System.currentTimeMillis();
            spTime.edit().putLong("protect_start_time", startTime).apply();
        }
        h = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        // 初始化各音频流音量基线（音量键触发检测 + 按键方向推断用）
        if (audioManager != null) {
            StringBuilder sb = new StringBuilder("音量基线: ");
            for (int stream : VOLUME_STREAMS) {
                int v = audioManager.getStreamVolume(stream);
                lastVolumes.put(stream, v);
                pressBaseVolumes.put(stream, v);
                sb.append(streamName(stream)).append("=").append(v).append(" ");
            }
            Log.i(TAG, sb.toString());
        }

        loadWhitelist();
        loadBlacklist();

        // ===== 病毒库：每次进入先用本地缓存立即生效，再后台拉取服务器最新数据 =====
        VirusDb.loadFromCache(this);
        VirusDb.refreshAsync(this);

        createChannels();
        startForeground(NOTIFY_ID, buildNotify(null));

        // ===== WakeLock 保活 =====
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":WakeLock");
            if (wakeLock != null) wakeLock.acquire();
        }

        // ===== AlarmManager 定时唤醒保活（每2分钟检查一次）=====
        setupKeepAliveAlarm();

        // ===== 通知栏：显示"已开启守护多久"、当前拦截模式、拦截次数 =====
        // 每 60 秒刷新一次（分钟级精度足够），首次 8 秒后先刷一次，
        // 避免开局停在"不到 1 分钟"太久
        notifyUpdater = () -> {
            updateNotify(null);
            h.postDelayed(notifyUpdater, 60000);
        };
        h.postDelayed(notifyUpdater, 8000);

        // ===== 音量键连按触发 — 三层保险 =====
        // 三层独立触发，统一通过 countVolumePress(dir) 计数（自带 500ms 去重）
        // 触发按键（音量- / 音量+）与次数（2~10）均由用户在高级设置里自定义

        // 第1层：无障碍服务 onVolumeChanged 回调（API 26+，无论音量是否变化都会触发）
        AdSkipService.setVolumeChangeListener(volumeListener);

        // 第2层：VOLUME_CHANGED_ACTION 广播（API 22+）
        // 广播 extras 里同时带"当前音量"与"上一次音量"，可直接算出升降方向；
        // 部分 ROM 不带 extras → 传 0，交给方向推断
        volRcvr = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int dir = 0;
                try {
                    if (i != null) {
                        int cur = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                        int prev = i.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1);
                        if (cur >= 0 && prev >= 0) dir = cur > prev ? 1 : (cur < prev ? -1 : 0);
                    }
                } catch (Exception ignored) {}
                Log.v(TAG, "收到 VOLUME_CHANGED_ACTION 广播 dir=" + dir);
                countVolumePress(dir);
            }
        };
        registerReceiver(volRcvr, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));

        // 第3层：主线程轮询兜底（120ms，仅检测音量实际降低）
        volPollRunnable = new Runnable() {
            @Override
            public void run() {
                try { checkVolumeStreams(); } catch (Exception e) {
                    Log.e(TAG, "轮询异常", e);
                }
                // 先移除旧回调再投递新的，防止主线程拥堵时堆积
                h.removeCallbacks(this);
                h.postDelayed(this, 120);
            }
        };
        h.postDelayed(volPollRunnable, 120);
        Log.i(TAG, "音量轮询已启动 (主线程, 120ms间隔)");

        // ===== 无障碍前台监听（仅检查用户黑名单）=====
        AdSkipService.setForegroundChangeListener(fgPkg -> {
            if (fgPkg == null || fgPkg.equals(getPackageName())) return;
            Log.d(TAG, "无障碍检测到前台变化: " + fgPkg);
            if (isSys(fgPkg)) return;
            if (isWhitelisted(fgPkg)) return;
            if (blacklistCache.contains(fgPkg)) {
                if (fgPkg.equals(lastWarnPkg) &&
                        System.currentTimeMillis() - lastWarnTime < 60000) return;
                Log.w(TAG, "无障碍检测到黑名单应用: " + fgPkg);
                warnPopup(fgPkg, 1, "检测到黑名单应用（" + fgPkg + "）", false);
            }
        });

        // ===== 新包安装检测 =====
        pkgRcvr = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String d = i.getDataString();
                if (d != null && d.startsWith("package:")) {
                    final String p = d.substring(8);
                    h.postDelayed(() -> {
                        if (p.equals(getPackageName())) return;
                        loadBlacklist();
                        // 病毒库：安装清单变化 → 重建缓存并允许对该应用重新检测
                        virusAppCache = null;
                        virusHandledPkgs.remove(p);
                        if (!isSys(p) && !isWhitelisted(p) && blacklistCache.contains(p)) {
                            warnPopup(p, 1, "检测到黑名单应用已安装", false);
                        }
                    }, 3000);
                }
            }
        };
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgRcvr, pf);

        // ===== 主循环 — 每 4 秒 =====
        // ⚠️ 2026-10-05 修复（真机"划着就卡"）：这一轮里**重活必须放后台线程**。
        //   原来整个 run() 都跑在主线程，而它包含：
        //     · virusScanOnce()：遍历**全部已安装应用**做病毒库匹配（实测 143 个），
        //       还要读列表缓存、比对多个库；
        //     · scanBlacklistedApps()：同样遍历全部应用；
        //     · loadWhitelist/loadBlacklist：各读一次 SharedPreferences（磁盘 IO）。
        //   每 4 秒在主线程干一遍这些，用户滑动时就表现为"周期性一顿一顿"。
        //   现在：轻量判断留在主线程（探活/拉起哨兵），重活丢到单线程池，
        //   并用一个标志防止上一轮没跑完就叠下一轮。
        tick = new Runnable() {
            @Override
            public void run() {
                // ---- 轻量部分（主线程，毫秒级）----
                // 哨兵机制：兄弟被杀 → 立即拉起（纯状态查询 + 一次 startService）
                if (anyTriggerOn() && !isServiceRunning(ForegroundService.class)) {
                    Log.w(TAG, "哨兵进程 ForegroundService 已停止，主进程拉起...");
                    try {
                        ContextCompat.startForegroundService(ProtectService.this,
                                new Intent(ProtectService.this, ForegroundService.class));
                    } catch (Exception ignored) {}
                }

                // ---- 重活部分（后台线程，避免阻塞界面）----
                runTickHeavyWorkAsync();

                h.postDelayed(this, 4000);
            }
        };
        h.postDelayed(tick, 2000);

        // ===== 摇动检测传感器（前台服务级别，后台也可靠）=====
        setupShakeSensor();

        // ===== Shizuku 断连兜底：超级拦截模式自动降级 =====
        Stellar.INSTANCE.addBinderDeadListener(shizukuDeadListener, null);
        Log.d(TAG, "Shizuku断连监听已注册");

        // ===== 无障碍权限丢失检测：丢失后只要 Shizuku 在仍可正常守护 =====
        // ⚠️ 2026-10-05 修复（真机"一弹提示就卡死"）：
        //   这段原来在**主线程**（h.postDelayed 的 Runnable）里直接调
        //   StellarUtils.isStellarAvailable() / hasStellarPermission() ——
        //   这两个方法会与特权服务端通信、服务端没起来时会等待数秒到十几秒，
        //   主线程一堵界面立刻卡死。而"无障碍没开"的用户**每次服务启动都会走到这里**，
        //   所以表现为"那个服务一弹出提示就卡死"。
        //   现在把特权查询挪到后台线程，取到结果再回主线程弹提示；
        //   同时给提示做节流，避免服务反复重启时反复弹。
        h.postDelayed(() -> {
            if (isAccessibilityServiceEnabled()) return;
            Log.w(TAG, "检测到无障碍权限已丢失，若 Shizuku 已连接仍可正常守护");
            new Thread(() -> {
                boolean hasPriv;
                try {
                    hasPriv = StellarUtils.isStellarAvailable()
                            && StellarUtils.hasStellarPermission();
                } catch (Throwable t) {
                    hasPriv = false;
                }
                final boolean hasPrivFinal = hasPriv;
                h.post(() -> showA11yLostToastOnce(hasPrivFinal));
            }, "a11y-lost-check").start();
        }, 1500);

        Log.d(TAG, "ProtectService started");
    }

    // ======================================================================
    // 2026-10 新增（用户需求）：运行期权限降级
    // ----------------------------------------------------------------------
    // 场景一：无障碍权限丢了
    //   → 触发方式自动切换为「摇一摇」（摇一摇不依赖无障碍，靠加速度传感器）
    //   → Toast 提示用户「已切换为摇一摇，摇动手机即可」
    //   → 顺带把音量键触发关掉：音量键检测依赖无障碍按键事件，
    //     无障碍没了它其实点不动，留着开关是骗用户
    //   → 权限恢复后，若原先开着音量键触发，自动还原
    //
    // 场景二：悬浮窗权限丢了
    //   → Toast 提示用户「弹窗无法显示，触发时会直接跳转到应用」
    //   → 触发流程本身不变（见 launchAppForRescue()：直接跳转到本应用）
    //
    // 两个都丢 → 额外再补一句合并提示。
    //
    // 只在**状态发生变化**时提示/切换，避免每 4 秒刷一次 Toast。
    // ======================================================================
    private void monitorPermissionDegrade() {
        final boolean a11yOk = isAccessibilityServiceEnabled();
        final boolean overlayOk = canDrawOverlays();
        final SharedPreferences sp = getSharedPreferences("shield_prefs", MODE_PRIVATE);

        boolean overlayChanged = (lastOverlayAvailable != null && lastOverlayAvailable != overlayOk);
        lastOverlayAvailable = overlayOk;

        boolean a11yChanged = (lastA11yAvailable != null && lastA11yAvailable != a11yOk);

        if (a11yChanged) {
            if (!a11yOk) {
                // —— 无障碍丢失：把摇一摇当兜底打开，但绝不"顺手关掉"用户的音量键 ——
                // 2026-10 修复：旧实现在这里无条件把 volume_trigger_on 置 false，
                // 于是状态变成「摇一摇=true、音量键=false」，而 countVolumePress 里
                // 又有一道"摇一摇开着就屏蔽音量键"的闸门 —— 两件事叠加，音量键逃生
                // 被彻底堵死，且不弹任何东西（用户只能重开 App / 重开守护）。
                // 现在：不再关闭音量键开关（无障碍丢了它本来也不会误触发），
                // 同时记下"摇一摇是我们自动开的"，权限恢复时再还回去。
                Log.w(TAG, "权限降级：无障碍已丢失，触发方式自动切换为摇一摇");
                shakeTriggerAutoOnByDegrade = !sp.getBoolean("shake_trigger_on", false);
                if (shakeTriggerAutoOnByDegrade) {
                    sp.edit().putBoolean("shake_trigger_on", true).apply();
                }
                // 记文件轨迹：本机（vivo）logcat 拿不到本应用的日志，出问题只能靠这条轨迹。
                // 这条同时记下"音量键开关有没有被我们动过"，一眼就能看出逃生通道是否完整。
                CrashLogger.event("[权限] 无障碍丢失：自动打开摇一摇=" + shakeTriggerAutoOnByDegrade
                        + "，音量键开关保持=" + sp.getBoolean("volume_trigger_on", false));
                final String msg = "无障碍权限已丢失，已自动切换为摇一摇触发（摇动手机即可）。"
                        + "音量键逃生仍保持原来的开关状态，权限恢复后一切照旧";
                h.post(() -> Toast.makeText(ProtectService.this, msg, Toast.LENGTH_LONG).show());
            } else {
                // —— 无障碍恢复：把权限丢失期间自动打开的东西还回去 ——
                Log.i(TAG, "权限恢复：无障碍已重新开启");
                CrashLogger.event("[权限] 无障碍已恢复：摇一摇=" + sp.getBoolean("shake_trigger_on", false)
                        + " 音量键=" + sp.getBoolean("volume_trigger_on", false));
                if (shakeTriggerAutoOnByDegrade) {
                    shakeTriggerAutoOnByDegrade = false;
                    // 只在用户没有把音量键当唯一触发方式时，才收掉自动打开的摇一摇；
                    // 否则保留摇一摇，避免出现"两个触发都被关掉、逃生彻底没了"的空窗。
                    if (sp.getBoolean("volume_trigger_on", false)) {
                        sp.edit().putBoolean("shake_trigger_on", false).apply();
                        Log.i(TAG, "权限恢复：已还原权限丢失前自动打开的摇一摇（用户选择的是音量键）");
                    } else {
                        Log.i(TAG, "权限恢复：保留摇一摇触发（用户未开启音量键，避免逃生方式全空）");
                    }
                }
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
                Log.w(TAG, "权限降级：悬浮窗已丢失，覆盖层无法显示（触发时改为直接跳转本应用）");
                h.post(() -> Toast.makeText(ProtectService.this,
                        "悬浮窗权限已丢失：拦截弹窗将无法显示，触发时会直接跳转到游龙安全护盾",
                        Toast.LENGTH_LONG).show());
            } else {
                Log.i(TAG, "权限恢复：悬浮窗已重新开启");
                h.post(() -> Toast.makeText(ProtectService.this,
                        "悬浮窗权限已恢复，拦截弹窗可正常显示", Toast.LENGTH_LONG).show());
            }
        }

        // 两个都没了 → 再补一句合并提示，别让用户漏看其中一条
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
     *   2. 失败则发一个**全屏 Intent 通知**（高优先级，锁屏/后台也能拉起界面），
     *      点它即进入本应用；
     *   3. 通知同时保留普通入口，保证用户一定能进来。
     *
     * <p>无论走哪条路，进的都是 MainActivity —— 也就是用户要的
     * 「跳转到我们的安全护盾里」，进去后接着走原来的流程。
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

        // ---- 2) 全屏 Intent 通知兜底 ----
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
                        .bigText("悬浮窗权限不可用，无法直接弹出拦截窗口。\n"
                                + "点击本通知进入「游龙安全护盾」继续处理。\n触发原因：" + reason))
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
    private boolean isAccessibilityServiceEnabled() {
        try {
            int enabled = android.provider.Settings.Secure.getInt(
                    getContentResolver(), android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled != 1) return false;
            String services = android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return services != null && services.contains(getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    // ===== 扫描已安装应用（仅检查用户黑名单）=====
    private void scanBlacklistedApps() {
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);

            for (ApplicationInfo app : apps) {
                String pkg = app.packageName;
                if (pkg == null || pkg.equals(getPackageName())) continue;

                if (isSys(pkg)) continue;
                if (isWhitelisted(pkg)) continue;

                // 仅检查用户黑名单
                if (blacklistCache.contains(pkg)) {
                    // ⚠️ 本方法现在跑在后台线程（见 runTickHeavyWorkAsync）。
                    //   而 warnIfNotRecent → warnPopup → showWarnOverlay 会 new View 并
                    //   直接 wm.addView(...)，那是**必须**在主线程做的事（后台线程没有
                    //   Looper，ViewRootImpl 构造会抛 "Can't create handler inside thread
                    //   that has not called Looper.prepare()"，表现为闪退）。
                    //   所以这里只把"发现黑名单"的结果丢回主线程，弹窗仍在主线程完成。
                    final String hitPkg = pkg;
                    h.post(() -> warnIfNotRecent(hitPkg, "检测到黑名单应用（" + hitPkg + "）"));
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "scanBlacklistedApps err", e);
        }
    }

    // ===== 带防重复的弹窗（单个应用）=====
    private void warnIfNotRecent(String pkg, String reason) {
        if (pkg.equals(lastWarnPkg) && System.currentTimeMillis() - lastWarnTime < 60000) return;
        Log.w(TAG, reason);
        warnPopup(pkg, 1, reason, false);
    }

    // ===== 加载白名单 =====
    private void loadWhitelist() {
        whitelistCache.clear();
        // 内置信任应用（不可拦截，与白名单管理页一致）
        whitelistCache.addAll(WhitelistActivity.DEFAULT_TRUSTED_PKGS);
        // 兼容旧版硬编码跳过列表，统一并入白名单管理
        whitelistCache.add("com.larus.nova");
        whitelistCache.add("com.smile.gifmaker");
        // 用户自定义白名单
        SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        String raw = prefs.getString("whitelist_pkgs", "");
        if (!raw.isEmpty()) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) whitelistCache.add(t);
            }
        }
    }

    // ===== 加载用户黑名单 =====
    private void loadBlacklist() {
        blacklistCache.clear();
        SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        String raw = prefs.getString("blacklist_pkgs", "");
        if (!raw.isEmpty()) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) blacklistCache.add(t);
            }
        }
    }

    // ===== 获取前台应用（仅音量键等场景兜底使用）=====
    private String getFgSimple() {
        // 方法1: dumpsys window
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                int u0idx = line.indexOf("u0 ");
                if (u0idx < 0) continue;
                String afterU0 = line.substring(u0idx + 3);
                int slash = afterU0.indexOf('/');
                if (slash > 0) {
                    String pkg = afterU0.substring(0, slash).trim();
                    // 用包名白名单校验，而不是仅 contains(".")：
                    // 该值来自 dumpsys 文本切片，格式随 ROM/系统版本而变，
                    // 下游会被拼进 shell 命令（pm uninstall / am force-stop），
                    // 因此必须确保其中不含任何 shell 元字符。见 PkgGuard。
                    if (PkgGuard.isValid(pkg)) { r.close(); return pkg; }
                    Log.w(TAG, "getFgSimple：dumpsys 解析出非法包名，已忽略: " + pkg);
                }
            }
            r.close();
        } catch (Exception e) {
            Log.v(TAG, "getFgSimple dumpsys方法失败", e);
        }

        // 方法2: RunningAppProcessInfo
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (ActivityManager.RunningAppProcessInfo p : procs) {
                        if (p.processName.equals(getPackageName())) continue;
                        if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                                && p.pkgList != null && p.pkgList.length > 0) {
                            return p.pkgList[0];
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.v(TAG, "getFgSimple RunningAppProcessInfo方法失败", e);
        }
        return null;
    }

    // ===== Shizuku 前台检测（无障碍丢失时的兜底通道） =====
    // 通过 Shizuku 执行 dumpsys window 获取当前焦点窗口，解析前台应用包名。
    // 无需无障碍/使用情况权限，只要 Shizuku 服务在即可正常识别前台应用。
    private String getFgViaStellar() {
        try {
            if (!StellarUtils.isStellarAvailable() || !StellarUtils.hasStellarPermission()) {
                return null;
            }
            String out = StellarUtils.runCommand(
                    "dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'", 8000);
            if (out == null || out.startsWith("ERROR:")) return null;

            BufferedReader r = new BufferedReader(new StringReader(out));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                int u0idx = line.indexOf("u0 ");
                if (u0idx < 0) continue;
                String afterU0 = line.substring(u0idx + 3);
                int slash = afterU0.indexOf('/');
                if (slash > 0) {
                    String pkg = afterU0.substring(0, slash).trim();
                    // 同上：dumpsys 文本切片 → 白名单校验后再返回
                    if (PkgGuard.isValid(pkg)) {
                        Log.d(TAG, "Shizuku检测前台: " + pkg);
                        return pkg;
                    }
                    Log.w(TAG, "getFgViaStellar：dumpsys 解析出非法包名，已忽略: " + pkg);
                }
            }
        } catch (Exception e) {
            Log.v(TAG, "getFgViaStellar 失败", e);
        }
        return null;
    }

    // ===== 音量检测：流音量降低检测（轮询调用）=====
    // 跟踪多个音频流，默认无媒体播放时音量键控制的是 STREAM_RING
    private final int[] VOLUME_STREAMS = {
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM
    };
    private final SparseIntArray lastVolumes = new SparseIntArray();

    /** 将音频流 ID 转为可读名称（日志用） */
    private String streamName(int stream) {
        switch (stream) {
            case AudioManager.STREAM_MUSIC: return "媒体";
            case AudioManager.STREAM_RING: return "铃声";
            case AudioManager.STREAM_NOTIFICATION: return "通知";
            case AudioManager.STREAM_ALARM: return "闹钟";
            case AudioManager.STREAM_SYSTEM: return "系统";
            case AudioManager.STREAM_VOICE_CALL: return "通话";
            case AudioManager.STREAM_DTMF: return "DTMF";
            default: return "流" + stream;
        }
    }

    /** 检查音频流音量是否实际变化（轮询检测，仅补充计数） */
    private void checkVolumeStreams() {
        if (audioManager == null) return;

        int changedDir = 0;   // 本轮流第一个发生变化的流的方向
        for (int stream : VOLUME_STREAMS) {
            int cur = audioManager.getStreamVolume(stream);
            int last = lastVolumes.get(stream, -1);
            if (last >= 0 && cur != last) {
                if (changedDir == 0) changedDir = cur > last ? 1 : -1;
                Log.v(TAG, "音量变化: " + streamName(stream) + "(" + stream + ") "
                        + last + "→" + cur);
            }
            lastVolumes.put(stream, cur);
        }

        if (changedDir != 0) {
            // 轮询检测到音量变化 → 通过共用去重计数器计入一次按键（自带方向）
            // countVolumePress 内部有 500ms 去重层，不会与广播/无障碍重复计数
            countVolumePress(changedDir);
        }
    }

    /**
     * 共用按键计数器：三层独立触发，统一在此计数 + 去重 + 方向判定 + 阈值判断。
     *
     * @param dir 按键方向：-1 = 音量减，+1 = 音量加，0 = 无法判断（自行推断）
     */
    private void countVolumePress(int dir) {
        // ===== 2026-10 修复（bug：紧急逃生只能触发一次）=====
        // 旧实现在这里有一道「shake_trigger_on == true 就直接 return」的闸门：
        // 摇一摇开关一旦为真，音量键逃生被整体屏蔽，且不弹任何提示、日志里只有一行
        // "音量键触发暂时不可用"，用户看到的就是"逃生突然没反应了，重开 App 才好"。
        //
        // 触发这道闸门的现实路径（都在本文件里）：
        //   1) monitorPermissionDegrade()：无障碍一丢就 putBoolean("shake_trigger_on", true)，
        //      并且把 volume_trigger_on 置 false —— 状态变成"摇一摇开、音量键关"；
        //   2) MainActivity.startProtect()：每次"开启守护"都强制写
        //      shake_trigger_on=true / volume_trigger_on=false。
        // 之后即使音量键开关被还原成 true，只要摇一摇还开着，音量键就永远进不来。
        //
        // 现在改成：只有"用户自己"把触发方式设成摇一摇时才保留这条互斥（尊重用户选择，
        // 也避免误按音量键误触救援）；而**权限降级自动打开**的摇一摇不再屏蔽音量键 ——
        // 那正是本 bug 的关键：自动降级把状态变成"摇一摇开、音量键也被关"，
        // 逃生通道就此静默消失，只能重开 App。
        final SharedPreferences volPrefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        final boolean volOn = volPrefs.getBoolean("volume_trigger_on", false);
        final boolean shakeOn = volPrefs.getBoolean("shake_trigger_on", false);
        if (shakeOn && !shakeTriggerAutoOnByDegrade && !volOn) {
            Log.v(TAG, "用户选择的是摇一摇触发，本次音量按键忽略");
            return;
        }
        if (!volOn) {
            // 2026-10：记轨迹。音量键按了没反应时，这一条能立刻区分
            // "开关没开"和"开关开着但没弹窗"，省掉来回猜。
            CrashLogger.event("[逃生] 收到音量按键但已忽略：音量键触发开关是关的"
                    + "（摇一摇=" + shakeOn + "）");
            return; // 用户明确关掉了音量键触发
        }
        if (shakeOn) {
            // 摇一摇与音量键同时为真时不再屏蔽音量键，只留一条日志方便事后排查
            Log.v(TAG, "摇一摇与音量键同时开启，音量键逃生照常计数（不再互相屏蔽）");
        }
        final boolean traceThisPress = volPresses.isEmpty(); // 只在"新一轮第一次按键"记轨迹
        if (traceThisPress) {
            CrashLogger.event("[逃生] 收到音量按键（模式="
                    + volPrefs.getString("shield_mode", "basic")
                    + " 摇一摇=" + shakeOn + "）");
        }
        long now = System.currentTimeMillis();
        // 去重：同一物理按键在 500ms 内可能从三层（无障碍/广播/轮询）重复进入。
        // 但"方向"可能先到的是"未知"，后到的是"明确" —— 此时不重复计数，
        // 只把方向补全到同一条记录上（各层回调顺序不固定，不补全会丢失准确方向）。
        if (!volPresses.isEmpty() && now - volPresses.get(volPresses.size() - 1).ts < VOL_PRESS_DEDUP_MS) {
            if (dir != 0) {
                VolPress last = volPresses.get(volPresses.size() - 1);
                if (last.dir == 0) {
                    last.dir = dir;
                    Log.v(TAG, "音量按键去重：补全方向=" + dir);
                }
            }
            Log.v(TAG, "音量按键去重忽略（500ms 内已有记录）");
            return;
        }

        // 注意：不再主动调整音量（旧实现的 ADJUST_RAISE 会引发回调反馈环路，
        // 长期运行导致音量跳变）。物理按键层在音量已是顶/底时仍会上报方向，
        // 所以无需我们干预音量。
        if (dir == 0) dir = inferVolumeDirection();

        volPresses.add(new VolPress(now, dir));

        // 清理超过窗口期的老记录（按键次数越多，允许的间隔窗口越宽）
        final long window = volumePressWindowMs();
        for (int i = volPresses.size() - 1; i >= 0; i--) {
            if (now - volPresses.get(i).ts > window) volPresses.remove(i);
        }

        // ===== 阈值判断：只看"用户选定方向"的按键 =====
        // 方向未知的按键也计入（部分 ROM 拿不到方向，宁可灵敏也不丢触发）
        final int wantDir = isVolumeUpTrigger() ? 1 : -1;
        int matched = 0;
        for (VolPress p : volPresses) {
            if (p.dir == wantDir || p.dir == 0) matched++;
        }
        final int need = getVolumeTriggerCount();
        Log.d(TAG, "音量按键计数=" + matched + "/" + need
                + " 目标=" + (wantDir > 0 ? "音量+" : "音量-")
                + " 窗口=" + window + "ms");

        if (matched >= need) {
            volPresses.clear();
            // 2026-10：记一条运行轨迹。逃生一旦"没反应"，用户复制日志就能看到
            // 到底是没计到数、还是计到数了却没弹出（后者看 triggerVolumeRescue 的轨迹）。
            CrashLogger.event("[逃生] 音量键达到触发条件（" + matched + "/" + need
                    + "，目标=" + (wantDir > 0 ? "音量+" : "音量-") + "）");
            triggerVolumeRescue();
        }
    }

    /**
     * 音量键监听的存活巡检 + 自愈（每 4 秒由 tick 调用）。
     *
     * <p>为什么需要它：音量键逃生唯一可靠的入口是
     * {@link AdSkipService#setVolumeChangeListener} 挂在无障碍服务上的静态回调。
     * 而 {@code ProtectService.onDestroy()} 会把它清成 null —— 一旦出现"清了却没重挂"
     * 的时序（系统回收后重建、异常退出、其它代码误清），音量键逃生就会**静默失效**：
     * 按键有反应、界面全无、日志一片空白，用户只能重开 App 或重开守护才能恢复。
     *
     * <p>这里做两件事：
     * <ol>
     *   <li>发现回调丢了 → 原样挂回去（逃生立即恢复，不需要用户重启）；</li>
     *   <li>把"丢过"这件事写进运行轨迹（CrashLogger），下次再出问题有据可查。</li>
     * </ol>
     *
     * <p>只在"用户确实开着音量键触发 + 无障碍服务在跑"时才补挂：用户明确关掉了
     * 音量键触发、或无障碍本身被关掉时，不该由我们私自把它打开。
     */
    private void ensureVolumeTriggerListener() {
        if (!getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getBoolean("volume_trigger_on", false)) {
            return; // 用户没开音量键触发，不需要这个监听
        }
        if (AdSkipService.isVolumeListenerRegistered()) return; // 监听还挂着，正常
        if (!isAccessibilityServiceEnabled()) {
            // 无障碍本身不在，挂上也收不到按键事件；不刷日志，等权限回来再补挂
            return;
        }
        if (AdSkipService.ensureVolumeListener(volumeListener)) {
            Log.w(TAG, "音量键监听曾丢失，已自动重新挂上（逃生恢复可用，无需重启）");
            CrashLogger.event("[逃生] 音量键监听曾丢失，已自愈重挂");
        }
    }

    // ===== 用户自定义的音量触发配置（HTML 高级设置写入，实时生效，无需重启服务）=====
    private static final String KEY_VOL_TRIGGER_KEY = "volume_trigger_key";
    private static final String KEY_VOL_TRIGGER_COUNT = "volume_trigger_count";
    private static final int VOL_TRIGGER_COUNT_MIN = 2;
    private static final int VOL_TRIGGER_COUNT_MAX = 10;
    private static final int VOL_TRIGGER_COUNT_DEFAULT = 3;
    // 同一物理按键被多层重复上报的去重窗口
    private static final long VOL_PRESS_DEDUP_MS = 500;
    // 按键计数的时间窗口下限（次数多时自动放宽，见 volumePressWindowMs）
    private static final long VOL_PRESS_WINDOW_BASE_MS = 2500;

    /** 用户自定义的触发次数（2~10，默认 3） */
    private int getVolumeTriggerCount() {
        int n = getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getInt(KEY_VOL_TRIGGER_COUNT, VOL_TRIGGER_COUNT_DEFAULT);
        if (n < VOL_TRIGGER_COUNT_MIN) n = VOL_TRIGGER_COUNT_MIN;
        if (n > VOL_TRIGGER_COUNT_MAX) n = VOL_TRIGGER_COUNT_MAX;
        return n;
    }

    /** 用户自定义的触发按键：true = 音量+键，false = 音量-键（默认） */
    private boolean isVolumeUpTrigger() {
        return "up".equals(getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getString(KEY_VOL_TRIGGER_KEY, "down"));
    }

    /**
     * 计数窗口：至少 2.5 秒，并要求"平均每次按键间隔不少于 700ms"。
     * 否则用户把次数调到 10 次时，2.5 秒内根本按不完，功能会形同虚设。
     */
    private long volumePressWindowMs() {
        return Math.max(VOL_PRESS_WINDOW_BASE_MS, getVolumeTriggerCount() * 700L);
    }

    /**
     * 推断本次音量按键的方向（无法确定时返回 0）。
     *
     * 依据各音频流当前音量与上一次按键判定时的基线对比。
     * 音量已在顶/底时按压不会改变数值 → 返回 0，此时依赖物理按键层
     * （AdSkipService.onKeyEvent）给出的明确方向。
     */
    private int inferVolumeDirection() {
        if (audioManager == null) return 0;
        int dir = 0;
        for (int stream : VOLUME_STREAMS) {
            int cur = audioManager.getStreamVolume(stream);
            int base = pressBaseVolumes.get(stream, -1);
            if (base < 0) {
                pressBaseVolumes.put(stream, cur);
                continue;
            }
            if (cur != base) {
                if (dir == 0) dir = cur > base ? 1 : -1;
                pressBaseVolumes.put(stream, cur);
            }
        }
        return dir;
    }

    /** 触发音量键连按救援（按键与次数由用户在高级设置里自定义） */
    private void triggerVolumeRescue() {
        Log.w(TAG, "逃生触发！启动救援");

        // ===== 读取当前拦截模式：极强/终结拦截模式不受位置限制 =====
        SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        final boolean isExtreme = "extreme".equals(prefs.getString("shield_mode", "basic"));
        final boolean isFinalMode = "final".equals(prefs.getString("shield_mode", "basic"));
        final boolean isDailyMode = "daily".equals(prefs.getString("shield_mode", "basic"));
        final boolean isStrictMode = isExtreme || isFinalMode || isDailyMode;

        // 2026-10：把开关状态一并记进轨迹。排查"逃生没反应"时，第一眼就能看出
        // 是不是音量键开关在某个路径里被静默关掉了（而不是逃生逻辑本身出问题）。
        CrashLogger.event("[逃生] 开始救援：模式=" + prefs.getString("shield_mode", "basic")
                + " 音量键触发=" + prefs.getBoolean("volume_trigger_on", false)
                + " 摇一摇=" + prefs.getBoolean("shake_trigger_on", false)
                + " 无障碍=" + isAccessibilityServiceEnabled());

        // ===== 链式前台检测（按可靠性排序） =====
        // 优先级1: 无障碍服务实时追踪（最可靠）
        String fgPkg = AdSkipService.getForegroundPkg();
        if (fgPkg == null || fgPkg.isEmpty()) {
            // 优先级2: UsageStatsManager（系统级API，Android 5.0+通用）
            fgPkg = getFgViaUsageStats();
        }
        if (fgPkg == null || fgPkg.isEmpty()) {
            // 优先级3: Shizuku shell dumpsys（无障碍丢失时有 Shizuku 仍可正常识别前台）
            fgPkg = getFgViaStellar();
        }
        if (fgPkg == null || fgPkg.isEmpty()) {
            // 优先级4: dumpsys + RunningAppProcessInfo（兼容旧设备）
            fgPkg = getFgSimple();
        }

        Log.i(TAG, "救援前台检测结果: " + (fgPkg != null ? fgPkg : "(null)"));

        // ===== 日常模式：不受任何前台界面限制 =====
        // 系统界面（桌面/设置/关机菜单/锁屏）、白名单应用、甚至识别不出包名时，
        // 都必须能弹出覆盖层 —— 否则用户在病毒应用里根本叫不出救援窗。
        // 注意：此处仅跳过"前台判定"，应用内不可卸载自身/宠物/工具的保护仍然生效。
        if (isDailyMode) {
            String safePkg = (fgPkg == null || fgPkg.isEmpty()) ? "(无法识别的界面)" : fgPkg;
            Log.i(TAG, "日常模式：不受前台/白名单/系统应用限制，直接弹出覆盖层（前台=" + safePkg + "）");
            warnPopup(safePkg, 2, "逃生触发", true);
            return;
        }

        // 自己的包名 → 不拦截
        if ("com.youlong.hd".equals(fgPkg)) {
            Log.i(TAG, "前台是游龙安全护盾自身，跳过");
            return;
        }

        // 白名单应用：照样弹出救援窗。
        // 用户是「主动」触发救援（音量三连击），界面必须出来 —— 只弹个 Toast 等于什么都没做。
        // 安全性不受影响：弹窗内的应用列表、卸载/冻结逻辑都会把白名单应用排除在外，
        // 即「窗口照弹，但绝不动这个应用」。
        if (fgPkg != null && !fgPkg.isEmpty() && isWhitelisted(fgPkg)) {
            Log.i(TAG, "前台应用在白名单中：仍弹出救援窗（列表自动排除白名单应用）: " + fgPkg);
            warnPopup(fgPkg, 2, "逃生触发（白名单应用）", true);
            return;
        }

        // 系统应用不触发救援（极强拦截模式：系统界面也触发）
        if (fgPkg != null && !fgPkg.isEmpty() && isSys(fgPkg)) {
            if (isStrictMode) {
                Log.i(TAG, "极强/终结拦截模式：系统应用界面也触发拦截: " + fgPkg);
            } else {
                Log.i(TAG, "前台应用是系统应用，不触发救援: " + fgPkg);
                // 2026-10：基础模式下站在桌面/设置里按音量键是"故意"不弹窗的，
                // 但用户往往理解为"逃生又失效了" —— 记一条轨迹，避免误判成 bug。
                CrashLogger.event("[逃生] 已跳过：基础模式下前台是系统应用（" + fgPkg + "）");
                return;
            }
        }

        // 无论 fgPkg 是否为空都弹窗（为空时覆盖层显示"无法识别"并提供打开全部应用列表）
        warnPopup(fgPkg, 2, "逃生触发", true);
    }

    // ===== UsageStatsManager 前台检测（Android 5.0+，极其可靠）=====
    private String getFgViaUsageStats() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return null;

            long now = System.currentTimeMillis();
            // 查最近10秒内使用过的应用
            List<UsageStats> stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 10000, now);
            if (stats == null || stats.isEmpty()) return null;

            // 取最近使用的那个
            UsageStats top = null;
            for (UsageStats s : stats) {
                if (s.getLastTimeUsed() <= 0) continue;
                if (top == null || s.getLastTimeUsed() > top.getLastTimeUsed()) {
                    top = s;
                }
            }

            if (top != null) {
                Log.d(TAG, "UsageStats检测前台: " + top.getPackageName()
                        + " lastUsed=" + (now - top.getLastTimeUsed()) + "ms ago");
                return top.getPackageName();
            }
        } catch (Exception e) {
            Log.e(TAG, "UsageStats前台检测失败", e);
        }
        return null;
    }

    // ===== 直接 WindowManager 覆盖层弹窗 =====
    // 最高优先级，直接从 Service 弹窗，不受 Android 10+ 后台启动 Activity 限制
    // 不提前检查 canDrawOverlays() — 直接 try，国产 ROM 上权限状态可能不准确
    private void showWarnOverlay(String pkg, String reason, boolean isVolumeRescue, final String shieldMode) {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;

            final boolean isSuper = "super".equals(shieldMode);
            final boolean isExtreme = "extreme".equals(shieldMode);
            final boolean isFinal = "final".equals(shieldMode);
            final boolean isDaily = "daily".equals(shieldMode);
            final boolean isShizukuMode = isSuper || isExtreme || isFinal || isDaily;

            // 终结模式：独立完整流程（终结全部应用 → 选择可疑应用 → 强力清除 → 重启）
            if (isFinal) {
                showFinalOverlay(wm, pkg, reason, isVolumeRescue);
                return;
            }

            // 日常模式：独立完整流程（威胁确认 → 循环终止 → 选择应用 → 卸载 → 重启）
            if (isDaily) {
                showDailyOverlay(wm, pkg, reason, isVolumeRescue);
                return;
            }

            // 最高级别窗口类型 — TYPE_SYSTEM_ERROR(2010) 覆盖状态栏/导航栏/系统对话框之上
            int type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8+ 不支持 TYPE_SYSTEM_ERROR，用 APPLICATION_OVERLAY + 额外标记
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                // Android 7 及以下：TYPE_SYSTEM_ERROR 是最高级别窗口(值2010)，覆盖一切
                type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
            }

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }

            final boolean pkgUnknown = (pkg == null || pkg.isEmpty());
            final String pkgF = pkg;

            // ===== 构建覆盖层 UI =====
            final LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(isShizukuMode ? 0xDD0D1B3D : 0xDD1B0000);
            root.setPadding(40, 60, 40, 60);

            // 沉浸式隐藏状态栏+导航栏 — 最高级别弹窗覆盖所有系统UI
            root.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
            root.setFitsSystemWindows(false);

            // 图标
            TextView icon = new TextView(this);
            icon.setText(isShizukuMode ? "\uD83D\uDEE1\uFE0F" : "\u26A0\uFE0F");
            icon.setTextSize(56);
            icon.setGravity(Gravity.CENTER);
            icon.setPadding(0, 0, 0, 8);
            root.addView(icon);

            // 标题
            TextView title = new TextView(this);
            if (isSuper) {
                title.setText("超级拦截 · 强制停止");
            } else if (isExtreme) {
                title.setText("极强拦截 · 全面停止");
            } else {
                title.setText(isVolumeRescue ? "\u2757 紧急救援" : "\u2757 检测到高风险应用！");
            }
            title.setTextColor(Color.WHITE);
            title.setTextSize(22);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, 0, 0, 10);
            root.addView(title);

            // 包名
            final TextView pkgTv = new TextView(this);
            pkgTv.setText(pkgUnknown ? "⚠ 无法识别前台应用" : pkg);
            pkgTv.setTextColor(pkgUnknown ? 0xFFFF8888 : 0xFFFFCC00);
            pkgTv.setTextSize(16);
            pkgTv.setGravity(Gravity.CENTER);
            pkgTv.setPadding(0, 0, 0, 6);
            root.addView(pkgTv);

            // 原因
            final TextView reasonTv = new TextView(this);
            if (isSuper) {
                reasonTv.setText("通过 Shizuku 执行 am force-stop 强制停止应用\n如不操作将在10秒后自动执行");
            } else if (isExtreme) {
                reasonTv.setText("通过 Shizuku 获取全部第三方应用并逐个强制停止\n如不操作将在5秒后自动执行（自动跳过游龙安全护盾）");
            } else {
                reasonTv.setText(pkgUnknown ? reason + " — 请手动选择要卸载的应用" : reason);
            }
            reasonTv.setTextColor(isShizukuMode ? 0xFFFFCCCC : 0xFFFF8888);
            reasonTv.setTextSize(14);
            reasonTv.setGravity(Gravity.CENTER);
            reasonTv.setPadding(0, 0, 0, 24);
            root.addView(reasonTv);

            // ===== 倒计时 =====
            final TextView countdownTv = new TextView(this);
            countdownTv.setGravity(Gravity.CENTER);
            countdownTv.setPadding(0, 0, 0, 16);
            root.addView(countdownTv);

            // ===== 按钮容器 =====
            final LinearLayout btnContainer = new LinearLayout(this);
            btnContainer.setOrientation(LinearLayout.VERTICAL);
            btnContainer.setGravity(Gravity.CENTER);
            root.addView(btnContainer);

            // ===== 主操作按钮 =====
            final Button btnMain = new Button(this);
            btnMain.setTextColor(Color.WHITE);
            btnMain.setTextSize(18);
            btnMain.setBackgroundColor(0xFFD32F2F);
            btnMain.setPadding(40, 24, 40, 24);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            mp.bottomMargin = 12;
            btnMain.setLayoutParams(mp);
            btnContainer.addView(btnMain);

            // ===== 关闭按钮 =====
            final Button btnClose = new Button(this);
            btnClose.setText("关闭");
            btnClose.setTextColor(0xFFAAAAAA);
            btnClose.setTextSize(15);
            btnClose.setBackgroundColor(0xFF444444);
            btnClose.setPadding(30, 18, 30, 18);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            btnClose.setLayoutParams(cp);
            btnContainer.addView(btnClose);

            // ==================== 极强拦截模式 ====================
            if (isExtreme) {
                final boolean[] stageDone = {false};

                // 第1阶段 UI
                countdownTv.setText("5 秒后将自动全面拦截");
                countdownTv.setTextColor(0xFFFFCC00);
                countdownTv.setTextSize(16);
                btnMain.setText("立即全面拦截");

                final Runnable[] countdownTask = new Runnable[1];
                final java.util.concurrent.atomic.AtomicInteger countdown =
                        new java.util.concurrent.atomic.AtomicInteger(5);

                // === 执行全面拦截：pm list packages -3 → 逐个 am force-stop ===
                final Runnable doExtremeStop = new Runnable() {
                    @Override
                    public void run() {
                        if (countdownTask[0] != null) h.removeCallbacks(countdownTask[0]);
                        stageDone[0] = true;
                        countdownTv.setText("正在获取全部第三方应用...");
                        countdownTv.setTextColor(0xFFFFCC00);
                        btnMain.setEnabled(false);
                        btnClose.setEnabled(false);

                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                // 第1步：pm list packages -3 获取全部第三方包名
                                String listResult;
                                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                                    listResult = StellarUtils.runCommand("pm list packages -3", 15000);
                                } else {
                                    listResult = execShell("pm list packages -3");
                                }
                                Log.w(TAG, "极强拦截 pm list packages -3 结果长度: " + (listResult == null ? 0 : listResult.length()));

                                // 第2步：解析包名，过滤游龙安全护盾 + 桌面宠物（三重保险之一）
                                final java.util.List<String> pkgs = new ArrayList<>();
                                if (listResult != null) {
                                    String[] lines = listResult.split("\n");
                                    for (String line : lines) {
                                        String t = line.trim();
                                        if (t.startsWith("package:")) {
                                            String name = t.substring(8).trim();
                                            if (name.isEmpty()) continue;
                                            if (isProtectedFinalPkg(name)) continue; // 过滤自己 + 桌面宠物
                                            pkgs.add(name);
                                        }
                                    }
                                }

                                // 第3步：分批执行 am force-stop（每批 20 个，避免超长命令截断）
                                String result = "OK";
                                if (!pkgs.isEmpty()) {
                                    StringBuilder batch = new StringBuilder();
                                    int batchSize = 0;
                                    for (int i = 0; i < pkgs.size(); i++) {
                                        if (batchSize > 0) batch.append("; ");
                                        batch.append("am force-stop ").append(pkgs.get(i));
                                        batchSize++;
                                        if (batchSize >= 20 || i == pkgs.size() - 1) {
                                            String cmd = batch.toString();
                                            // 字符串级终极保险（三重保险之二）：命令中绝不允许出现保护包名
                                            if (cmd.contains("com.youlong.hd") || cmd.contains("com.youlong.zoo")
                                                    || cmd.contains("com.youlong.tool")
                                                    || cmd.contains(getPackageName())) {
                                                Log.w(TAG, "极强拦截 force-stop 命令包含保护包名，已拦截丢弃!");
                                            } else {
                                                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                                                    result = StellarUtils.runCommand(cmd, 60000);
                                                } else {
                                                    result = execShell(cmd);
                                                }
                                            }
                                            batch.setLength(0);
                                            batchSize = 0;
                                        }
                                    }
                                }
                                Log.w(TAG, "极强拦截全面 force-stop 结果: " + result + " (共" + pkgs.size() + "个应用)");

                                final int stopCount = pkgs.size();
                                h.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        // 全面停止完成提示
                                        if (stopCount == 0) {
                                            countdownTv.setText("已全面停止（未发现第三方应用）");
                                        } else {
                                            countdownTv.setText("已强制停止 " + stopCount + " 个第三方应用");
                                        }
                                        countdownTv.setTextColor(0xFF4CAF50);
                                        countdownTv.setTextSize(16);

                                        // 扫描到前台应用 → 若为受保护应用（自己/桌面宠物/游龙工具/白名单）直接跳过卸载/冻结询问
                                        if (!pkgUnknown && isProtectedFinalPkg(pkgF)) {
                                            // 更新包名显示（已全面停止）
                                            pkgTv.setText("已全面停止：" + pkgF);
                                            pkgTv.setTextColor(0xFF4CAF50);
                                            reasonTv.setText("前台应用为受保护应用，已自动跳过（绝不卸载/冻结）");
                                            reasonTv.setTextColor(0xFF4CAF50);
                                            countdownTv.setText("受保护应用已保护");
                                            countdownTv.setTextColor(0xFF4CAF50);
                                            btnMain.setText("关闭");
                                            btnMain.setEnabled(true);
                                            btnMain.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    lastShakeRescueTime = System.currentTimeMillis();
                                                    try { wm.removeView(root); } catch (Exception ignored) {}
                                                }
                                            });
                                            btnClose.setVisibility(View.GONE);
                                        } else if (!pkgUnknown) {
                                            // 更新包名显示（已全面停止）
                                            pkgTv.setText("已全面停止：" + pkgF);
                                            pkgTv.setTextColor(0xFF4CAF50);
                                            // 更新原因 → 询问卸载
                                            reasonTv.setText("已全面停止，是否卸载前台应用 " + pkgF + " ？");
                                            reasonTv.setTextColor(0xFFFFCC00);

                                            // 更新按钮：立即卸载 / 不卸载，关闭
                                            btnMain.setText("立即卸载");
                                            btnMain.setEnabled(true);
                                            btnClose.setText("不卸载，关闭");
                                            btnClose.setTextColor(0xFFFFFFFF);
                                            btnClose.setBackgroundColor(0xFF2E7D32);
                                            btnClose.setEnabled(true);
                                            btnClose.setVisibility(View.VISIBLE);

                                            // 第2阶段倒计时 5s → 自动卸载
                                            final java.util.concurrent.atomic.AtomicInteger cd2 =
                                                    new java.util.concurrent.atomic.AtomicInteger(5);
                                            final Runnable[] cd2Task = new Runnable[1];
                                            cd2Task[0] = new Runnable() {
                                                @Override
                                                public void run() {
                                                    int sec = cd2.decrementAndGet();
                                                    if (sec > 0) {
                                                        countdownTv.setText(sec + " 秒后将自动卸载");
                                                        if (sec <= 2) countdownTv.setTextColor(0xFFFF4444);
                                                        h.postDelayed(this, 1000);
                                                    } else {
                                                        countdownTv.setText("正在卸载...");
                                                        executeUninstallOverlay(pkgF, root, wm, countdownTv, btnMain, btnClose);
                                                    }
                                                }
                                            };
                                            countdownTask[0] = cd2Task[0];
                                            h.postDelayed(cd2Task[0], 1000);

                                            // 主按钮 → 立即卸载
                                            btnMain.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    if (cd2Task[0] != null) h.removeCallbacks(cd2Task[0]);
                                                    countdownTv.setText("正在卸载...");
                                                    executeUninstallOverlay(pkgF, root, wm, countdownTv, btnMain, btnClose);
                                                }
                                            });

                                            // 关闭按钮 → 不卸载 → 询问是否冻结此应用（20秒倒计时自动冻结）
                                            btnClose.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    if (cd2Task[0] != null) h.removeCallbacks(cd2Task[0]);
                                                    showFreezeAsk(pkgF, reasonTv, countdownTv, btnMain, btnClose, root, wm);
                                                }
                                            });
                                        } else {
                                            // 未扫描到前台应用 → 直接关闭
                                            btnMain.setText("关闭");
                                            btnMain.setEnabled(true);
                                            btnMain.setOnClickListener(new View.OnClickListener() {
                                                @Override
                                                public void onClick(View v) {
                                                    lastShakeRescueTime = System.currentTimeMillis();
                                                    try { wm.removeView(root); } catch (Exception ignored) {}
                                                }
                                            });
                                            btnClose.setVisibility(View.GONE);
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                };

                // 主按钮点击 → 立即全面拦截
                btnMain.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (stageDone[0]) return;
                        doExtremeStop.run();
                    }
                });

                // 关闭按钮 → 取消（重置摇一摇冷却，防止弹窗关闭后立即再次触发）
                btnClose.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (countdownTask[0] != null) h.removeCallbacks(countdownTask[0]);
                        lastShakeRescueTime = System.currentTimeMillis();
                        try { wm.removeView(root); } catch (Exception ignored) {}
                    }
                });

                // 第1阶段倒计时 5s → 自动全面拦截
                countdownTask[0] = new Runnable() {
                    @Override
                    public void run() {
                        int sec = countdown.decrementAndGet();
                        if (sec > 0) {
                            countdownTv.setText(sec + " 秒后将自动全面拦截");
                            if (sec <= 3) countdownTv.setTextColor(0xFFFF4444);
                            h.postDelayed(this, 1000);
                        } else {
                            doExtremeStop.run();
                        }
                    }
                };
                h.postDelayed(countdownTask[0], 1000);

                wm.addView(root, lp);
                Log.i(TAG, "极强拦截覆盖层已显示: " + pkg);
                return;
            }

            // ==================== 超级拦截模式 ====================
            if (isSuper) {
                final boolean[] stageDone = {false}; // false=第1阶段(force-stop), true=第2阶段(uninstall)

                // 第1阶段 UI
                countdownTv.setText("5 秒后将自动强制停止");
                countdownTv.setTextColor(0xFFFFCC00);
                countdownTv.setTextSize(16);
                btnMain.setText("立即强制停止");

                final Runnable[] countdownTask = new Runnable[1];
                final java.util.concurrent.atomic.AtomicInteger countdown =
                        new java.util.concurrent.atomic.AtomicInteger(5);

                // === 执行 force-stop ===
                final Runnable doForceStop = new Runnable() {
                    @Override
                    public void run() {
                        if (countdownTask[0] != null) h.removeCallbacks(countdownTask[0]);
                        stageDone[0] = true;
                        countdownTv.setText("正在通过 Shizuku 强制停止...");
                        countdownTv.setTextColor(0xFFFFCC00);
                        btnMain.setEnabled(false);
                        btnClose.setEnabled(false);

                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                String result;
                                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                                    result = StellarUtils.runCommand("am force-stop " + pkgF, 10000);
                                } else {
                                    try {
                                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "am force-stop " + pkgF}).waitFor();
                                        result = "OK(fallback)";
                                    } catch (Exception e) {
                                        result = "ERROR:" + e.getMessage();
                                    }
                                }
                                Log.w(TAG, "超级拦截 force-stop 结果: " + result);

                                // 回到主线程 → 第2阶段：卸载询问
                                h.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        // 更新包名颜色表示已停止
                                        pkgTv.setText("已强制停止：" + pkgF);
                                        pkgTv.setTextColor(0xFF4CAF50);
                                        // 更新原因
                                        reasonTv.setText("am force-stop 已执行，是否继续卸载此应用？");
                                        reasonTv.setTextColor(0xFFFFCC00);
                                        // 更新倒计时
                                        countdownTv.setText("5 秒后将自动卸载");
                                        countdownTv.setTextColor(0xFFFF4444);
                                        countdownTv.setTextSize(18);
                                        // 更新按钮
                                        btnMain.setText("立即卸载");
                                        btnMain.setEnabled(true);
                                        btnClose.setText("不卸载，关闭");
                                        btnClose.setTextColor(0xFFFFFFFF);
                                        btnClose.setBackgroundColor(0xFF2E7D32);
                                        btnClose.setEnabled(true);

                                        // 第2阶段倒计时 5s → 自动卸载
                                        final java.util.concurrent.atomic.AtomicInteger cd2 =
                                                new java.util.concurrent.atomic.AtomicInteger(5);
                                        final Runnable[] cd2Task = new Runnable[1];
                                        cd2Task[0] = new Runnable() {
                                            @Override
                                            public void run() {
                                                int sec = cd2.decrementAndGet();
                                                if (sec > 0) {
                                                    countdownTv.setText(sec + " 秒后将自动卸载");
                                                    if (sec <= 2) countdownTv.setTextColor(0xFFFF4444);
                                                    h.postDelayed(this, 1000);
                                                } else {
                                                    countdownTv.setText("正在卸载...");
                                                    executeUninstallOverlay(pkgF, root, wm, countdownTv, btnMain, btnClose);
                                                }
                                            }
                                        };
                                        countdownTask[0] = cd2Task[0];
                                        h.postDelayed(cd2Task[0], 1000);

                                        // 重设主按钮 → 执行卸载
                                        btnMain.setOnClickListener(new View.OnClickListener() {
                                            @Override
                                            public void onClick(View v) {
                                                if (cd2Task[0] != null) h.removeCallbacks(cd2Task[0]);
                                                countdownTv.setText("正在卸载...");
                                                executeUninstallOverlay(pkgF, root, wm, countdownTv, btnMain, btnClose);
                                            }
                                        });

                                        // 重设关闭按钮 → 不卸载 → 询问是否冻结此应用（20秒倒计时自动冻结）
                                        btnClose.setOnClickListener(new View.OnClickListener() {
                                            @Override
                                            public void onClick(View v) {
                                                if (cd2Task[0] != null) h.removeCallbacks(cd2Task[0]);
                                                showFreezeAsk(pkgF, reasonTv, countdownTv, btnMain, btnClose, root, wm);
                                            }
                                        });
                                    }
                                });
                            }
                        }).start();
                    }
                };

                // 主按钮点击 → force-stop
                btnMain.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (stageDone[0]) return;
                        doForceStop.run();
                    }
                });

                // 关闭按钮 → 取消（重置摇一摇冷却，防止弹窗关闭后立即再次触发）
                btnClose.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (countdownTask[0] != null) h.removeCallbacks(countdownTask[0]);
                        lastShakeRescueTime = System.currentTimeMillis();
                        try { wm.removeView(root); } catch (Exception ignored) {}
                    }
                });

                // 第1阶段倒计时 5s → 自动 force-stop
                countdownTask[0] = new Runnable() {
                    @Override
                    public void run() {
                        int sec = countdown.decrementAndGet();
                        if (sec > 0) {
                            countdownTv.setText(sec + " 秒后将自动强制停止");
                            if (sec <= 3) countdownTv.setTextColor(0xFFFF4444);
                            h.postDelayed(this, 1000);
                        } else {
                            doForceStop.run();
                        }
                    }
                };
                h.postDelayed(countdownTask[0], 1000);

                wm.addView(root, lp);
                Log.i(TAG, "超级拦截覆盖层已显示: " + pkg);
                return;
            }

            // ==================== 基础模式（原有逻辑） ====================
            final Runnable[] countdownTask = new Runnable[1];
            if (isVolumeRescue) {
                countdownTv.setText("5 秒后自动跳转到应用设置");
                countdownTv.setTextColor(0xFFFFFF00);
                countdownTv.setTextSize(16);

                final java.util.concurrent.atomic.AtomicInteger countdown =
                        new java.util.concurrent.atomic.AtomicInteger(5);
                countdownTask[0] = new Runnable() {
                    @Override
                    public void run() {
                        int sec = countdown.decrementAndGet();
                        if (sec > 0) {
                            countdownTv.setText(sec + " 秒后自动跳转到应用设置");
                            h.postDelayed(this, 1000);
                        } else {
                            countdownTv.setText("正在跳转...");
                            lastShakeRescueTime = System.currentTimeMillis();
                            try { wm.removeView(root); } catch (Exception ignored) {}
                            jumpToAppSettings(pkgF, pkgUnknown);
                        }
                    }
                };
                h.postDelayed(countdownTask[0], 1000);
            } else {
                countdownTv.setVisibility(View.GONE);
            }

            btnMain.setText(pkgUnknown ? "打开全部应用列表" : "卸载此应用");
            btnMain.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (countdownTask[0] != null) {
                        h.removeCallbacks(countdownTask[0]);
                    }
                    lastShakeRescueTime = System.currentTimeMillis();
                    try { wm.removeView(root); } catch (Exception ignored) {}
                    jumpToAppSettings(pkgF, pkgUnknown);
                }
            });

            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (countdownTask[0] != null) {
                        h.removeCallbacks(countdownTask[0]);
                    }
                    lastShakeRescueTime = System.currentTimeMillis();
                    try { wm.removeView(root); } catch (Exception ignored) {}
                }
            });

            wm.addView(root, lp);
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
        }
    }

    // ===== 终结模式覆盖层 =====
    // 流程：pm list packages → 串联 am force-stop（过滤游龙安全护盾）
    //       → 全屏悬浮窗"请选择可疑应用，我们将强力清除他"
    //       → 用户勾选 → pm uninstall --user 0 串联卸载
    //       → 自动检测高危权限应用 → pm disable-user --user 0 串联冻结
    //       → 提示重启 → reboot
    private void showFinalOverlay(final WindowManager wm, final String pkg,
                                  final String reason, final boolean isVolumeRescue) {
        try {
            int type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
            }

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }

            // ===== 根布局 =====
            final LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(0xDD0D1B3D);
            root.setPadding(40, 60, 40, 60);
            root.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
            root.setFitsSystemWindows(false);

            // 内容容器（阶段切换时清空重建）
            final LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            root.addView(content, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));

            wm.addView(root, lp);
            Log.i(TAG, "终结模式覆盖层已显示: " + pkg);

            // ===== 第1阶段：终结确认（5秒倒计时自动执行） =====
            showFinalStage1(wm, root, content, pkg, reason, isVolumeRescue);
        } catch (SecurityException e) {
            Log.w(TAG, "终结模式覆盖层权限不足(SYSTEM_ALERT_WINDOW)", e);
        } catch (Exception e) {
            Log.e(TAG, "showFinalOverlay 失败", e);
        }
    }

    // ===== 终结模式第1阶段：弹出弹窗的同时自动执行全面 force-stop（反复循环）=====
    private void showFinalStage1(final WindowManager wm, final LinearLayout root,
                                 final LinearLayout content, final String pkg,
                                 final String reason, final boolean isVolumeRescue) {
        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDEE1\uFE0F");
        icon.setTextSize(48);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 6);
        content.addView(icon);

        TextView title = new TextView(this);
        title.setText("终结模式 · 强力清除");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 10);
        content.addView(title);

        // 转圈动画（indeterminate 圆形进度条，持续旋转，让用户知道仍在工作中）
        final ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                dp(72), dp(72));
        sp.gravity = Gravity.CENTER;
        sp.topMargin = 6;
        sp.bottomMargin = 10;
        spinner.setLayoutParams(sp);
        spinner.setIndeterminate(true);
        content.addView(spinner);

        // 模拟百分比：让用户看到"有进度"而不着急，实际 force-stop 循环同步进行
        final TextView percentTv = new TextView(this);
        percentTv.setText("正在终止所有系统应用  0%");
        percentTv.setTextColor(0xFF7CFF8A);
        percentTv.setTextSize(20);
        percentTv.setGravity(Gravity.CENTER);
        percentTv.setPadding(0, 0, 0, 8);
        content.addView(percentTv);

        final TextView statusTv = new TextView(this);
        statusTv.setText("正在获取全部应用并强制停止...");
        statusTv.setTextColor(0xFFFFCC00);
        statusTv.setTextSize(15);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, 0, 0, 10);
        content.addView(statusTv);

        final TextView tipTv = new TextView(this);
        tipTv.setText("正在终止所有系统应用\n（已自动保护游龙安全护盾）");
        tipTv.setTextColor(0xFFFFCCCC);
        tipTv.setTextSize(14);
        tipTv.setGravity(Gravity.CENTER);
        tipTv.setPadding(0, 0, 0, 10);
        content.addView(tipTv);

        // 覆盖层存活标志：进程被杀或界面销毁时立即停止循环，避免循环无法停止
        final java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        // 模拟进度（百分比）与首轮终结数量（全部主线程操作，避免线程竞争）
        final java.util.concurrent.atomic.AtomicInteger percent = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger firstCount = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicBoolean firstDone = new java.util.concurrent.atomic.AtomicBoolean(false);

        // ===== 关闭按钮：误触时可立即停止终结循环并退出，防止不可逆伤害 =====
        final LinearLayout stage1Btn = new LinearLayout(this);
        stage1Btn.setOrientation(LinearLayout.VERTICAL);
        stage1Btn.setGravity(Gravity.CENTER);
        stage1Btn.setPadding(0, 8, 0, 0);
        content.addView(stage1Btn);

        Button btnStage1Close = new Button(this);
        btnStage1Close.setText("关闭（停止并退出）");
        btnStage1Close.setTextColor(Color.WHITE);
        btnStage1Close.setTextSize(15);
        btnStage1Close.setBackgroundColor(0xFF555555);
        btnStage1Close.setPadding(30, 16, 30, 16);
        LinearLayout.LayoutParams s1p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnStage1Close.setLayoutParams(s1p);
        stage1Btn.addView(btnStage1Close);
        btnStage1Close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finalForceStopLoop = false; // 立即停止 force-stop 循环
                alive.set(false);           // 停止百分比模拟与阶段切换检查
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });

        // 百分比模拟器：独立于 force-stop 循环，弹窗一出现就每 350ms +1%，
        // 不受首轮 force-stop（可能耗时 1-2 分钟）阻塞影响，用户立刻看到进度在走
        final Runnable[] percentTicker = new Runnable[1];
        percentTicker[0] = new Runnable() {
            @Override
            public void run() {
                if (!alive.get()) return;
                if (percent.get() < 99) {
                    percent.incrementAndGet();
                    percentTv.setText("正在终止所有系统应用  " + percent.get() + "%");
                }
                h.postDelayed(percentTicker[0], 350);
            }
        };

        // 进度到位检查：首轮 force-stop 完成 且 百分比>=92% 时，切换到"选择可疑应用"界面（只切换一次）
        final Runnable[] switchCheck = new Runnable[1];
        switchCheck[0] = new Runnable() {
            @Override
            public void run() {
                if (!alive.get()) {
                    finalForceStopLoop = false;
                    return;
                }
                if (firstDone.get() && percent.get() >= 92) {
                    percentTv.setText("正在终止所有系统应用  99%");
                    showFinalSelectStage(wm, root, content, firstCount.get());
                    return; // 只切换一次，不再继续轮询
                }
                h.postDelayed(switchCheck[0], 400);
            }
        };

        // 后台线程：弹出弹窗的同时循环终结全部应用（严格过滤 com.youlong.hd）
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 终结模式依赖 Shizuku 执行 pm/am 命令，不可用时明确提示而非静默失败
                if (!StellarUtils.isStellarAvailable() || !StellarUtils.hasStellarPermission()) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            spinner.setVisibility(View.GONE);
                            percentTv.setVisibility(View.GONE);
                            statusTv.setText("⚠ 需要 Shizuku 权限");
                            statusTv.setTextColor(0xFFFF6B6B);
                            tipTv.setText("请先在 Shizuku 应用中授权本应用\n授权后重新触发终结模式");
                            tipTv.setTextColor(0xFFFF6B6B);
                        }
                    });
                    return;
                }

                finalForceStopLoop = true;
                finalForceStopPkgs = null; // 新一轮终结：重新解析并缓存包名列表

                // 立即启动百分比模拟与切换检查（UI 马上动起来，不与 force-stop 抢线程）
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive.get()) {
                            finalForceStopLoop = false;
                            return;
                        }
                        h.postDelayed(percentTicker[0], 120);
                        h.postDelayed(switchCheck[0], 500);
                    }
                });

                // 第1次：pm list packages 获取全部应用 → am force-stop（过滤 com.youlong.hd）
                int stopCount = doFinalForceStopOnce();
                firstCount.set(stopCount);
                firstDone.set(true);

                if (!alive.get()) {
                    finalForceStopLoop = false;
                    return;
                }

                // 首轮完成：用真实数量校准百分比（不低于当前模拟值，反映实际清理规模）
                percent.set(Math.max(percent.get(), Math.min(60, Math.max(15, firstCount.get() / 10))));
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive.get()) {
                            finalForceStopLoop = false;
                            return;
                        }
                        percentTv.setText("正在终止所有系统应用  " + percent.get() + "%");
                        statusTv.setText("已终结 " + firstCount.get() + " 个应用");
                    }
                });

                // 反复循环：进入"选择可疑应用"界面后仍持续 force-stop，防止病毒自启
                // 每轮重新执行 pm list packages -3 获取最新第三方包名，逐个 am force-stop，
                // 反复循环直到用户确认清除时 finalForceStopLoop=false 退出
                while (finalForceStopLoop && alive.get()) {
                    try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
                    if (!finalForceStopLoop || !alive.get()) break;
                    doFinalForceStopOnce();
                }
            }
        }).start();
    }

    // ===== 终结模式：单次终结应用（首次全量 + 后续每轮重新检测第三方应用）=====
    // 首次执行：pm list packages → 解析过滤 → 缓存包名列表 → 全量 am force-stop 一遍（拦截全部应用）。
    // 后续循环（进入"选择可疑应用"界面后仍持续执行）：每轮重新执行 pm list packages -3 获取
    // 最新第三方应用包名，然后逐个 am force-stop，反复循环，防止病毒/自启应用逃逸。
    private int doFinalForceStopOnce() {
        try {
            if (!finalForceStopLoop) return 0;

            // 1. 首次：pm list packages 获取全部应用包名并解析过滤，缓存结果，全量 force-stop
            if (finalForceStopPkgs == null) {
                String listResult;
                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                    listResult = StellarUtils.runCommand("pm list packages", 15000);
                } else {
                    listResult = execShell("pm list packages");
                }
                if (listResult == null || listResult.startsWith("ERROR:")) {
                    Log.w(TAG, "终结模式 pm list packages 失败: " + listResult);
                    return 0;
                }

                // 解析包名，过滤 com.youlong.hd + com.youlong.zoo + 系统关键组件（会搞崩系统）
                final java.util.List<String> pkgs = new ArrayList<>();
                for (String line : listResult.split("\n")) {
                    String t = line.trim();
                    if (!t.startsWith("package:")) continue;
                    String name = cleanPkgName(t.substring(8));
                    if (name.isEmpty()) continue;
                    if (isProtectedFinalPkg(name)) continue;      // 排除安全护盾 + 桌面宠物
                    if (isFinalSystemCriticalPkg(name)) continue; // 排除系统关键组件（防自杀）
                    pkgs.add(name);
                }
                Log.w(TAG, "终结模式首轮解析出 " + pkgs.size() + " 个应用待 force-stop（已缓存，首轮全量执行）");
                finalForceStopPkgs = pkgs;
                // 首次全量执行一遍（保证进入"选择可疑应用"界面时全部应用已被杀）
                forceStopPkgRange(pkgs, 0, pkgs.size());
                return pkgs.size();
            }

            // 2. 后续循环：每轮重新执行 pm list packages -3 获取最新第三方应用列表，
            //    然后对本次检出的全部包名逐个 am force-stop（反复循环，防止逃逸）
            String listResult;
            if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                listResult = StellarUtils.runCommand("pm list packages -3", 15000);
            } else {
                listResult = execShell("pm list packages -3");
            }
            if (listResult == null || listResult.startsWith("ERROR:")) {
                Log.w(TAG, "终结模式循环 pm list packages -3 失败: " + listResult);
                return 0;
            }

            // 解析第三方包名，过滤受保护包 + 系统关键组件（会搞崩系统）
            final java.util.List<String> thirdPkgs = new ArrayList<>();
            for (String line : listResult.split("\n")) {
                String t = line.trim();
                if (!t.startsWith("package:")) continue;
                String name = cleanPkgName(t.substring(8));
                if (name.isEmpty()) continue;
                if (isProtectedFinalPkg(name)) continue;      // 排除安全护盾 + 桌面宠物
                if (isFinalSystemCriticalPkg(name)) continue; // 排除系统关键组件（防自杀）
                thirdPkgs.add(name);
            }
            // 对本次检出的全部第三方应用逐个 am force-stop（每 10 个一批执行）
            forceStopPkgRange(thirdPkgs, 0, thirdPkgs.size());
            Log.i(TAG, "终结模式循环中：pm list packages -3 检出 " + thirdPkgs.size()
                    + " 个第三方应用，已全部 force-stop");
            return thirdPkgs.size();
        } catch (Exception e) {
            Log.e(TAG, "终结模式 force-stop 失败", e);
            return 0;
        }
    }

    // ===== 终结模式：对缓存列表的 [start, end) 区间执行 am force-stop（每批 10 个命令）=====
    private void forceStopPkgRange(java.util.List<String> pkgs, int start, int end) {
        StringBuilder batch = new StringBuilder();
        int cnt = 0;
        for (int i = start; i < end && i < pkgs.size(); i++) {
            String p = pkgs.get(i);
            if (isProtectedFinalPkg(p)) continue;      // 组装前二次过滤（双保险）
            if (isFinalSystemCriticalPkg(p)) continue; // 系统关键组件二次过滤
            if (cnt > 0) batch.append("; ");
            batch.append("am force-stop ").append(p);
            cnt++;
            if (cnt >= 10) {
                runFinalCommand(batch.toString());
                batch.setLength(0);
                cnt = 0;
            }
        }
        if (cnt > 0) runFinalCommand(batch.toString());
    }

    // ===== 终结模式：统一执行 Shizuku / execShell 命令 =====
    private String runFinalCommand(String cmd) {
        // 字符串级终极保险（三重保险之三）：命令中绝不允许出现保护包名（自己/桌面宠物/游龙工具/白名单无此判断）
        if (cmd != null && (cmd.contains("com.youlong.hd") || cmd.contains("com.youlong.zoo")
                || cmd.contains("com.youlong.tool") || cmd.contains(getPackageName()))) {
            Log.w(TAG, "终结模式命令包含保护包名，已拦截丢弃: " + cmd);
            return "BLOCKED(protected)";
        }
        if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
            return StellarUtils.runCommand(cmd, 60000);
        }
        return execShell(cmd);
    }

    // ===== 终结模式：判断是否受保护包名（自己 + 桌面宠物），任何终结操作前都必须过滤 =====
    private boolean isProtectedFinalPkg(String name) {
        if (name == null || name.isEmpty()) return true;
        if (name.equals(getPackageName())) return true;   // 游龙安全护盾自身
        if (name.equals("com.youlong.hd")) return true;  // 硬编码包名（双保险）
        if (name.equals("com.youlong.zoo")) return true; // 桌面宠物保留
        if (name.equals("com.youlong.tool")) return true; // 游龙工具（自家应用保留）
        // 白名单应用不终结（用户手动添加的白名单）
        if (isWhitelisted(name)) {
            Log.i(TAG, "终结模式：白名单应用跳过，不终结: " + name);
            return true;
        }
        return false;
    }

    // ===== 终结模式：判断是否为系统关键组件 =====
    // force-stop 这些组件会导致 SystemUI 崩溃重启，系统检测到异常会把持有弹窗的
    // 本进程一起杀掉（日志确认 uid 10494 被杀、弹窗自动关闭），因此必须跳过。
    // 注意：只跳过会搞崩系统/连锁反应的核心组件，其余系统应用照杀不误。
    private boolean isFinalSystemCriticalPkg(String name) {
        if (name == null || name.isEmpty()) return false;
        // SystemUI 本体与内部组件
        if (name.equals("com.android.systemui")) return true;
        if (name.startsWith("com.android.internal.systemui.")) return true;
        if (name.startsWith("com.android.systemui.")) return true;
        // 厂商 SystemUI 插件/相关
        if (name.contains("systemuiplugin") || name.contains("SystemUI")) return true;
        if (name.equals("com.vivo.minscreen")) return true; // vivo 小屏/负一屏，SystemUI 联动
        // 系统内容提供者：被 force-stop 会触发系统大量组件重启
        if (name.startsWith("com.android.providers.")) return true;
        // 核心系统服务
        if (name.equals("com.android.shell")) return true;
        if (name.equals("com.android.incallui")) return true;
        if (name.equals("com.android.emergency")) return true;
        if (name.equals("com.android.mtp")) return true;
        // 输入法（键盘）：被停会导致全系统输入崩溃
        if (name.startsWith("com.android.inputmethod")) return true;
        // WebView：被停会导致大量应用连锁崩溃
        if (name.equals("com.google.android.webview")) return true;
        return false;
    }

    // ===== 终结模式：dp 转 px（用于悬浮窗控件尺寸）=====
    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // ===== 终结模式：清理包名（去除 BOM/回车/空白/非法字符），防止过滤比较失败 =====
    private String cleanPkgName(String raw) {
        if (raw == null) return "";
        String s = raw.replace("\uFEFF", "").replace("\r", "").trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ===== 终结模式第2阶段：请选择可疑应用 =====
    private void showFinalSelectStage(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content, final int stopCount) {
        content.removeAllViews();

        // 图标
        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD28");
        icon.setTextSize(52);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        // 标题：请选择可疑应用，我们将强力清除他
        TextView title = new TextView(this);
        title.setText("请选择可疑应用，我们将强力清除他");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 8);
        content.addView(title);

        TextView sub = new TextView(this);
        sub.setText("已终结 " + stopCount + " 个应用（已排除游龙安全护盾，仍在循环终结中）\n请勾选可疑应用，高敏感权限应用优先");
        sub.setTextColor(0xFFFFCC00);
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, 12);
        content.addView(sub);

        // 动态提示（空列表引导 / 错误提示，弹窗内显示，避免被覆盖层遮挡的 Toast）
        final TextView hintTv = new TextView(this);
        hintTv.setText("");
        hintTv.setTextColor(0xFFFF6B6B);
        hintTv.setTextSize(14);
        hintTv.setGravity(Gravity.CENTER);
        hintTv.setPadding(0, 0, 0, 8);
        content.addView(hintTv);

        // 可滚动应用列表（先显示加载中，后台加载完成后填充）
        final ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.bottomMargin = 12;
        scroll.setLayoutParams(slp);

        final LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setGravity(Gravity.CENTER);
        scroll.addView(listContainer);

        TextView loadingTv = new TextView(this);
        loadingTv.setText("正在加载应用列表...");
        loadingTv.setTextColor(0xFFAAAAAA);
        loadingTv.setTextSize(15);
        loadingTv.setGravity(Gravity.CENTER);
        loadingTv.setPadding(0, 20, 0, 20);
        listContainer.addView(loadingTv);
        content.addView(scroll);

        // 按钮容器（加载完成后填充按钮）
        final LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.VERTICAL);
        btnContainer.setGravity(Gravity.CENTER);
        content.addView(btnContainer);

        // 后台线程加载应用列表（getFinalThirdPartyApps 内部有 pm shell 命令与权限扫描，
        // 耗时长，若在 UI 线程执行会 ANR 卡死，必须异步）
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<String[]> apps = getFinalThirdPartyApps();
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        // 覆盖层可能已被用户关闭
                        if (root.getParent() == null) return;
                        buildFinalSelectList(wm, root, content, stopCount, listContainer,
                                btnContainer, hintTv, apps);
                    }
                });
            }
        }).start();
    }

    // ===== 终结模式第2阶段（续）：后台加载完成后构建应用列表与按钮 =====
    private void buildFinalSelectList(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content, final int stopCount,
                                      final LinearLayout listContainer,
                                      final LinearLayout btnContainer,
                                      final TextView hintTv,
                                      final java.util.List<String[]> apps) {
        listContainer.removeAllViews();
        btnContainer.removeAllViews();
        hintTv.setText("");

        final java.util.List<CheckBox> boxes = new ArrayList<>();
        if (apps.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("未发现第三方应用");
            emptyTv.setTextColor(0xFFAAAAAA);
            emptyTv.setTextSize(15);
            emptyTv.setGravity(Gravity.CENTER);
            emptyTv.setPadding(0, 20, 0, 20);
            listContainer.addView(emptyTv);
        } else {
            for (final String[] app : apps) {
                final CheckBox cb = new CheckBox(this);
                String label = app[0];
                String apkg = app[1];
                boolean highRisk = app.length > 2 && "1".equals(app[2]);
                cb.setText((highRisk ? "⚠ " : "") + label + "\n" + apkg);
                cb.setTextColor(highRisk ? 0xFFFF6B6B : Color.WHITE);
                cb.setTextSize(14);
                cb.setPadding(12, 8, 12, 8);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, 6, 0, 6);
                cb.setLayoutParams(clp);
                listContainer.addView(cb);
                boxes.add(cb);
            }
        }

        // 确定按钮
        final Button btnConfirm = new Button(this);
        btnConfirm.setText("确定");
        btnConfirm.setTextColor(Color.WHITE);
        btnConfirm.setTextSize(18);
        btnConfirm.setBackgroundColor(0xFFD32F2F);
        btnConfirm.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cbp.bottomMargin = 12;
        btnConfirm.setLayoutParams(cbp);
        btnContainer.addView(btnConfirm);

        // 确定按钮：收集勾选的可疑应用 → 强力清除（防重复点击）
        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnConfirm.setEnabled(false);
                final java.util.List<String> selected = new ArrayList<>();
                for (int i = 0; i < boxes.size(); i++) {
                    if (boxes.get(i).isChecked()) {
                        selected.add(apps.get(i)[1]);
                    }
                }
                if (selected.isEmpty()) {
                    hintTv.setText("请先勾选可疑应用");
                    btnConfirm.setEnabled(true);
                    return;
                }
                // 用户勾选的可疑应用 → 直接卸载 pm uninstall --user 0（真实卸载，重启不恢复）
                // 第三方应用卸载后彻底消失，不会出现"假卸载/自动恢复"
                showFinalClearingStage(wm, root, content, selected, "正在卸载所选应用...", true);
            }
        });

        // 我不小心误触了，立即重启恢复：误触终结模式时一键 reboot 恢复手机
        final Button btnRecover = new Button(this);
        btnRecover.setText("我不小心误触了，立即重启恢复");
        btnRecover.setTextColor(0xFFDDDDDD);
        btnRecover.setTextSize(14);
        btnRecover.setBackgroundColor(0xFF555555);
        btnRecover.setPadding(30, 16, 30, 16);
        LinearLayout.LayoutParams rcp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rcp.topMargin = 16;
        btnRecover.setLayoutParams(rcp);
        btnContainer.addView(btnRecover);

        // 误触恢复：点击直接执行 reboot（先停 force-stop 循环，避免命令并发冲突）
        btnRecover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnRecover.setEnabled(false);
                btnRecover.setText("正在重启...");
                btnConfirm.setEnabled(false);
                finalForceStopLoop = false; // 停止 force-stop 循环
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String r;
                        if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                            r = StellarUtils.runCommand("reboot", 10000);
                        } else {
                            r = execShell("reboot");
                        }
                        Log.w(TAG, "终结模式误触恢复 reboot 结果: " + r);
                    }
                }).start();
            }
        });

        // ===== 关闭按钮：仅关闭覆盖层、不执行任何操作（停止循环，防止误触不可逆伤害）=====
        final Button btnFinalClose = new Button(this);
        btnFinalClose.setText("关闭（退出，不执行任何操作）");
        btnFinalClose.setTextColor(0xFFAAAAAA);
        btnFinalClose.setTextSize(14);
        btnFinalClose.setBackgroundColor(0xFF3A3A3A);
        btnFinalClose.setPadding(30, 16, 30, 16);
        LinearLayout.LayoutParams fcp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fcp.topMargin = 10;
        btnFinalClose.setLayoutParams(fcp);
        btnContainer.addView(btnFinalClose);
        btnFinalClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finalForceStopLoop = false; // 停止 force-stop 循环
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });
    }

    // ===== 终结模式第3阶段：执行强力清除 =====
    // uninstall=true → 用户勾选的可疑应用直接卸载 pm uninstall --user 0（真实卸载，重启不恢复）
    // uninstall=false → 自动检测的高危应用冻结 pm disable-user --user 0（持久禁用）
    private void showFinalClearingStage(final WindowManager wm, final LinearLayout root,
                                        final LinearLayout content,
                                        final java.util.List<String> targets,
                                        final String statusText,
                                        final boolean uninstall) {
        // 停止 force-stop 反复循环
        finalForceStopLoop = false;

        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD25");
        icon.setTextSize(52);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        final TextView statusTv = new TextView(this);
        statusTv.setText(statusText);
        statusTv.setTextColor(0xFFFFCC00);
        statusTv.setTextSize(16);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, 0, 0, 16);
        content.addView(statusTv);

        // ===== 关闭按钮：仅关闭覆盖层，不执行任何操作（防止误触不可逆伤害）=====
        final Button btnClearingClose = new Button(this);
        btnClearingClose.setText("关闭（退出，不执行任何操作）");
        btnClearingClose.setTextColor(0xFFAAAAAA);
        btnClearingClose.setTextSize(14);
        btnClearingClose.setBackgroundColor(0xFF3A3A3A);
        btnClearingClose.setPadding(30, 16, 30, 16);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnClearingClose.setLayoutParams(clp);
        content.addView(btnClearingClose);
        btnClearingClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });

        // 后台执行
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 等待 force-stop 循环退出（用户已做出选择，最多等 8 秒避免命令并发冲突；
                // 循环每轮约 4~5 秒，需留足时间让当前轮执行完）
                long waitUntil = System.currentTimeMillis() + 8000;
                while (finalForceStopLoop && System.currentTimeMillis() < waitUntil) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }

                // 用户勾选的可疑应用 → 直接卸载 pm uninstall --user 0（第三方应用真实卸载，重启不恢复）
                // 自动检测的高危应用 → 冻结 pm disable-user --user 0（持久禁用）
                StringBuilder cmdSb = new StringBuilder();
                boolean first = true;
                java.util.List<String> safeTargets = new ArrayList<>();
                for (String t : targets) {
                    if (isProtectedFinalPkg(t)) continue; // 绝不清除自己 + 桌面宠物
                    safeTargets.add(t);
                    if (!first) cmdSb.append("; ");
                    if (uninstall) {
                        cmdSb.append("pm uninstall --user 0 ").append(t);
                    } else {
                        cmdSb.append("pm disable-user --user 0 ").append(t);
                    }
                    first = false;
                }
                Log.w(TAG, "终结模式将" + (uninstall ? "卸载" : "冻结") + " " + safeTargets.size() + " 个: " + safeTargets);
                String finalResult = runFinalCommand(cmdSb.toString());
                Log.w(TAG, "终结模式 " + (uninstall ? "uninstall" : "disable-user") + " 结果: " + finalResult + " (共" + safeTargets.size() + "个)");

                h.post(new Runnable() {
                    @Override
                    public void run() {
                        // 覆盖层可能已被用户关闭，不再切换
                        if (root.getParent() == null) return;
                        // 第4阶段：提示重启
                        showFinalRebootStage(wm, root, content, targets.size(), uninstall);
                    }
                });
            }
        }).start();
    }

    // ===== 终结模式第4阶段：提示重启手机 → 执行 reboot =====
    private void showFinalRebootStage(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content, final int clearedCount,
                                      final boolean uninstall) {
        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD04");
        icon.setTextSize(56);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        TextView title = new TextView(this);
        title.setText("清除完成，需要重启手机");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 10);
        content.addView(title);

        TextView desc = new TextView(this);
        if (uninstall) {
            // 用户勾选的应用已真实卸载
            desc.setText("已卸载 " + clearedCount + " 个可疑应用\n重启后生效，卸载状态持久保持");
        } else {
            // 自动检测的高危应用已冻结
            desc.setText("已冻结 " + clearedCount + " 个可疑应用\n重启后生效，冻结状态持久保持");
        }
        desc.setTextColor(0xFFFFCC00);
        desc.setTextSize(15);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, 0, 0, 24);
        content.addView(desc);

        // 按钮容器
        final LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.VERTICAL);
        btnContainer.setGravity(Gravity.CENTER);
        content.addView(btnContainer);

        // 立即重启
        final Button btnReboot = new Button(this);
        btnReboot.setText("立即重启");
        btnReboot.setTextColor(Color.WHITE);
        btnReboot.setTextSize(18);
        btnReboot.setBackgroundColor(0xFFD32F2F);
        btnReboot.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = 12;
        btnReboot.setLayoutParams(rp);
        btnContainer.addView(btnReboot);

        // 稍后重启
        final Button btnLater = new Button(this);
        btnLater.setText("稍后重启");
        btnLater.setTextColor(0xFFAAAAAA);
        btnLater.setTextSize(15);
        btnLater.setBackgroundColor(0xFF444444);
        btnLater.setPadding(30, 18, 30, 18);
        LinearLayout.LayoutParams lap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLater.setLayoutParams(lap);
        btnContainer.addView(btnLater);

        btnReboot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnReboot.setEnabled(false);
                btnReboot.setText("正在重启...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String r;
                        if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                            r = StellarUtils.runCommand("reboot", 10000);
                        } else {
                            r = execShell("reboot");
                        }
                        Log.w(TAG, "终结模式 reboot 结果: " + r);
                    }
                }).start();
            }
        });

        btnLater.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lastShakeRescueTime = System.currentTimeMillis();
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });
    }

    // ======================================================================
    // ===== 日常模式：威胁确认 → 循环终止 → 选择应用 → 卸载 → 重启 ==========
    // ======================================================================
    // 流程：
    //   1) 弹窗"你是否遭遇病毒威胁"（是 / 否，误触了；10 秒无响应自动选"是"），
    //      弹窗出现的同时后台不断循环 pm list packages -3 → 串联 am force-stop 全部第三方应用
    //   2) 选"是" → 列出全部第三方应用（系统 API 获取，带无障碍/悬浮窗的排在前面，可多选）
    //   3) "我已选择完成，立刻卸载" → Shizuku 执行 pm uninstall 串联卸载；
    //      Shizuku 服务丢失 → 提示后改用系统 API（Intent.ACTION_DELETE）卸载
    //   4) 卸载完成 → 提示重启（重启 / 不重启）；Shizuku 权限丢失 → 提示用户手动重启
    private void showDailyOverlay(final WindowManager wm, final String pkg,
                                  final String reason, final boolean isVolumeRescue) {
        if (dailyOverlayShowing) {
            // 视图若已脱离 WindowManager（异常路径/被系统清除），标记会一直卡住
            // → 日常模式从此再也弹不出来。此处校验并把卡住的标记复位。
            View r = dailyOverlayRoot;
            if (r == null || r.getParent() == null) {
                Log.w(TAG, "日常模式：旧覆盖层已脱离窗口，重置标记后重新弹出");
                dailyOverlayShowing = false;
                dailyOverlayRoot = null;
            } else {
                Log.w(TAG, "日常模式覆盖层已在显示，忽略重复触发");
                return;
            }
        }
        try {
            int type;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
            }

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            dailyOverlayLp = lp;

            // ===== 根布局 =====
            final LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(0xDD0D1B3D);
            root.setPadding(40, 60, 40, 60);
            root.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
            root.setFitsSystemWindows(false);

            // 内容容器（阶段切换时清空重建）
            final LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            root.addView(content, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));

            wm.addView(root, lp);
            dailyOverlayShowing = true;
            dailyOverlayRoot = root;
            dailyAborted = false;
            Log.i(TAG, "日常模式覆盖层已显示: " + pkg + " / " + reason);

            // ===== 第1阶段：你是否遭遇病毒威胁（10 秒无响应自动选"是"）=====
            showDailyThreatStage(wm, root, content);
        } catch (SecurityException e) {
            Log.w(TAG, "日常模式覆盖层权限不足(SYSTEM_ALERT_WINDOW)", e);
        } catch (Exception e) {
            Log.e(TAG, "showDailyOverlay 失败", e);
        }
    }

    // ===== 日常模式：关闭覆盖层并停止一切后台动作 =====
    private void closeDailyOverlay(final WindowManager wm, final LinearLayout root) {
        dailyAborted = true;
        // 用 stopUninstallForceStopLoop() 而不是直接置 dailyForceStopLoop=false：
        // 卸载期那个后台 shell 循环跑在另一个进程里，读不到这个 Java 变量，
        // 必须靠删除哨兵文件才能真正让它停下来（否则它会继续跑满 5 分钟去停应用）。
        stopUninstallForceStopLoop();
        dailyOverlayShowing = false;
        dailyOverlayRoot = null;
        lastShakeRescueTime = System.currentTimeMillis(); // 防止摇一摇立即再次触发
        try { wm.removeView(root); } catch (Exception ignored) {}
    }

    // ===== 日常模式第1阶段：你是否遭遇病毒威胁（同时开始循环终止全部第三方应用）=====
    private void showDailyThreatStage(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content) {
        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83E\uDDA0");
        icon.setTextSize(52);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        TextView title = new TextView(this);
        title.setText("你是否遭遇病毒威胁");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 14);
        content.addView(title);

        final TextView statusTv = new TextView(this);
        statusTv.setText("正在终止全部第三方应用...");
        statusTv.setTextColor(0xFFFFCC00);
        statusTv.setTextSize(14);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, 0, 0, 8);
        content.addView(statusTv);

        final TextView countdownTv = new TextView(this);
        countdownTv.setText("10 秒后自动选择「是」");
        countdownTv.setTextColor(0xFF7CFF8A);
        countdownTv.setTextSize(15);
        countdownTv.setGravity(Gravity.CENTER);
        countdownTv.setPadding(0, 0, 0, 22);
        content.addView(countdownTv);

        // ===== 是 / 否，误触了 =====
        final LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.VERTICAL);
        btnContainer.setGravity(Gravity.CENTER);
        content.addView(btnContainer);

        final Button btnYes = new Button(this);
        btnYes.setText("是");
        btnYes.setTextColor(Color.WHITE);
        btnYes.setTextSize(20);
        btnYes.setBackgroundColor(0xFFD32F2F);
        btnYes.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        yp.bottomMargin = 12;
        btnYes.setLayoutParams(yp);
        btnContainer.addView(btnYes);

        final Button btnNo = new Button(this);
        btnNo.setText("否，误触了");
        btnNo.setTextColor(0xFFDDDDDD);
        btnNo.setTextSize(16);
        btnNo.setBackgroundColor(0xFF555555);
        btnNo.setPadding(30, 18, 30, 18);
        btnNo.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        btnContainer.addView(btnNo);

        final java.util.concurrent.atomic.AtomicBoolean answered =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        // 进入第2阶段（防止按钮与倒计时重复进入）
        final Runnable[] goSelect = new Runnable[1];
        goSelect[0] = new Runnable() {
            @Override
            public void run() {
                if (!answered.compareAndSet(false, true)) return;
                if (!dailyOverlayShowing || dailyAborted) return;
                showDailySelectStage(wm, root, content);
            }
        };

        btnYes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goSelect[0].run();
            }
        });
        btnNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answered.set(true);
                closeDailyOverlay(wm, root);
            }
        });

        // ===== 10 秒倒计时：用户无响应自动选"是" =====
        final java.util.concurrent.atomic.AtomicInteger cd =
                new java.util.concurrent.atomic.AtomicInteger(10);
        final Runnable[] cdTask = new Runnable[1];
        cdTask[0] = new Runnable() {
            @Override
            public void run() {
                if (!dailyOverlayShowing || dailyAborted || answered.get()) return;
                int left = cd.decrementAndGet();
                if (left <= 0) {
                    countdownTv.setText("未响应，已自动选择「是」");
                    goSelect[0].run();
                    return;
                }
                countdownTv.setText(left + " 秒后自动选择「是」");
                h.postDelayed(cdTask[0], 1000);
            }
        };
        h.postDelayed(cdTask[0], 1000);

        // ===== 后台线程：弹窗出现即开始，反复循环 force-stop 全部第三方应用 =====
        final boolean shizukuOk =
                StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission();
        if (!shizukuOk) {
            statusTv.setText("Shizuku 未连接，无法自动终止应用（卸载功能不受影响）");
            statusTv.setTextColor(0xFFFF9500);
        }
        dailyForceStopLoop = true;
        // ⚠️ 2026-10 新增（用户需求）：拦截刚触发时先立刻做一次无障碍服务收窄，
        //    不必等后台循环的第一轮（见 runDailyAccessibilityHarden 的说明）。
        //    放在后台线程里执行，避免阻塞覆盖层的渲染与倒计时。
        new Thread(new Runnable() {
            @Override
            public void run() {
                runDailyAccessibilityHarden();
            }
        }, "daily-a11y-harden").start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (dailyForceStopLoop && dailyOverlayShowing && !dailyAborted) {
                    // ⚠️ 2026-10 新增（用户需求）：
                    // 日常模式一旦触发拦截（也就是走到这里 —— 检测到威胁、覆盖层弹出、
                    // 开始循环终止第三方应用），就在每一轮循环里顺带清理无障碍服务：
                    // 只保留本应用自己注册的服务，把其它应用（尤其是恶意/勒索类应用
                    // 注册的无障碍服务）从 enabled_accessibility_services 里摘掉，
                    // 防止它们在被 force-stop 之后立刻靠无障碍服务自启并继续操作界面。
                    // 幂等操作：settings put 写同样的值等价于无操作，
                    // 因此每轮重复执行是安全的，且能覆盖「被停的应用又重新注册」的情况。
                    runDailyAccessibilityHarden();
                    final int n = doDailyForceStopOnce();
                    if (n > 0) {
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!dailyOverlayShowing || dailyAborted) return;
                                statusTv.setText("已终止 " + n + " 个第三方应用（持续循环中）");
                                statusTv.setTextColor(0xFFFFCC00);
                            }
                        });
                    }
                    try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
                }
            }
        }).start();
    }

    // ======================================================================
    // 日常模式：清理无障碍服务（2026-10 新增，用户需求）
    // ----------------------------------------------------------------------
    // 触发时机：日常模式触发拦截后，那个「循环停止全部第三方应用」的后台线程里，
    //           每一轮都会调用一次本方法（见 showDailyThreatStage 里的循环）。
    //
    // 目的：
    //   恶意 / 勒索类应用常常同时注册一个无障碍服务。单纯 am force-stop 只能把
    //   它停住，系统仍可能因为无障碍服务被绑定而把它拉起来，继续读取屏幕、
    //   自动点击（这正是勒索锁屏类应用维持自身的手段）。
    //   把 enabled_accessibility_services 收窄到**只保留本应用自己的服务**，
    //   可以从根上掐掉它们靠无障碍续命的能力。
    //
    // 脚本（用户给定，此处做了健壮性加固，语义完全一致）：
    //     T=com.youlong.hd; C=$(settings get secure enabled_accessibility_services); \
    //     N=$(echo "$C" | tr ':' '\n' | grep "^$T/" | tr '\n' ':' | sed 's/:$//'); \
    //     settings put secure enabled_accessibility_services "$N"; \
    //     settings put secure accessibility_enabled 1; \
    //     echo "当前启用：$(settings get secure enabled_accessibility_services)"
    //
    //   加固点（都不改变语义）：
    //     ① T 用 getPackageName() 动态取，不写死包名；
    //     ② grep 模式写成 "^$T/"（与用户给的脚本逐字一致）——
    //        ⚠️ 这里**不能**用 "grep -F -x \"$T/*\""：
    //           -F 会把 "*" 当成字面星号（那不是 glob 展开），于是模式变成
    //           "com.youlong.hd/*"，永远匹配不到真正的
    //           "com.youlong.hd/com.youlong.hd.AdSkipService"（已在真机上实测确认）；
    //     ③ settings 是普通 shell 命令（不需要 root），但仍套一层 timeout，
    //        万一某些 ROM 上 settings 卡住，也不会把整轮 force-stop 循环拖死。
    //
    // 安全性：幂等 —— 反复写同样的值等价于无操作；即使 C 为空（settings 返回 null），
    //         过滤结果就是空串，写回等于「清空无障碍服务列表」，不报错、不崩溃。
    // 失败处理：任何异常/超时都只记日志，绝不影响 force-stop 主流程。
    // ======================================================================
    private void runDailyAccessibilityHarden() {
        try {
            final String self = getPackageName();
            if (self == null || self.isEmpty()) return;

            StringBuilder sb = new StringBuilder();
            sb.append("T=").append(self).append("; ");
            sb.append("C=$(settings get secure enabled_accessibility_services 2>/dev/null); ");
            sb.append("N=$(echo \"$C\" | tr ':' '\\n' | grep \"^$T/\" | tr '\\n' ':' | sed 's/:$//'); ");
            sb.append("timeout 5 settings put secure enabled_accessibility_services \"$N\" 2>/dev/null; ");
            sb.append("timeout 5 settings put secure accessibility_enabled 1 2>/dev/null; ");
            sb.append("echo \"当前启用：$(settings get secure enabled_accessibility_services 2>/dev/null)\"");

            final String script = sb.toString();
            final String out;
            if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                // settings 需要在有权限的 shell 身份下执行；超时给 12s（内含两处 timeout 5）
                out = StellarUtils.runCommand(script, 12000);
            } else {
                out = execShell(script);
            }
            Log.i(TAG, "日常模式：无障碍服务已收窄到仅保留自身 —— "
                    + (out == null ? "null" : out.trim().replace('\n', ' ')));
        } catch (Throwable tr) {
            // 只记录，不抛出：这一步是「锦上添花」的加固，
            // 失败也不能影响正在进行的 force-stop / 卸载流程。
            Log.w(TAG, "日常模式：清理无障碍服务失败（已忽略）", tr);
        }
    }

    // ===== 日常模式：单轮终止 —— pm list packages -3 → 串联 am force-stop 全部第三方应用 =====
    // 每轮都重新执行 pm list packages -3 获取最新第三方应用列表，
    // 然后按 "am force-stop 应用包名1;am force-stop 应用包名2;..." 的形式串联执行，
    // 执行完一轮后由调用方继续下一轮（反复循环）。
    private int doDailyForceStopOnce() {
        try {
            if (!dailyForceStopLoop) return 0;
            String listResult;
            if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                listResult = StellarUtils.runCommand("pm list packages -3", 15000);
            } else {
                listResult = execShell("pm list packages -3");
            }
            if (listResult == null || listResult.startsWith("ERROR:")) {
                Log.w(TAG, "日常模式 pm list packages -3 失败: " + listResult);
                return 0;
            }

            final java.util.List<String> pkgs = new ArrayList<>();
            for (String line : listResult.split("\n")) {
                String t = line.trim();
                if (!t.startsWith("package:")) continue;
                String name = cleanPkgName(t.substring(8));
                if (name.isEmpty()) continue;
                if (isProtectedFinalPkg(name)) continue;      // 排除安全护盾 + 桌面宠物 + 白名单
                if (isFinalSystemCriticalPkg(name)) continue; // 排除系统关键组件（防自杀）
                pkgs.add(name);
            }
            if (pkgs.isEmpty()) return 0;

            // 串联 am force-stop 应用包名1;am force-stop 应用包名2;...（内部每 10 条一批下发）
            forceStopPkgRange(pkgs, 0, pkgs.size());
            Log.i(TAG, "日常模式：本轮已 force-stop " + pkgs.size() + " 个第三方应用");
            return pkgs.size();
        } catch (Exception e) {
            Log.e(TAG, "日常模式 force-stop 失败", e);
            return 0;
        }
    }

    // ===== 日常模式第2阶段：系统 API 列出全部第三方应用，多选待卸载 =====
    private void showDailySelectStage(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content) {
        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDCF1");
        icon.setTextSize(46);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 6);
        content.addView(icon);

        TextView title = new TextView(this);
        title.setText("请选择要卸载的应用");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 8);
        content.addView(title);

        TextView sub = new TextView(this);
        sub.setText("带无障碍 / 悬浮窗的应用已排在前面（可多选）\n卸载完成后需要重启手机");
        sub.setTextColor(0xFFFFCC00);
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, 10);
        content.addView(sub);

        final TextView hintTv = new TextView(this);
        hintTv.setText("");
        hintTv.setTextColor(0xFFFF6B6B);
        hintTv.setTextSize(14);
        hintTv.setGravity(Gravity.CENTER);
        hintTv.setPadding(0, 0, 0, 8);
        content.addView(hintTv);

        final ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.bottomMargin = 12;
        scroll.setLayoutParams(slp);

        final LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer);

        TextView loadingTv = new TextView(this);
        loadingTv.setText("正在读取全部第三方应用...");
        loadingTv.setTextColor(0xFFAAAAAA);
        loadingTv.setTextSize(15);
        loadingTv.setGravity(Gravity.CENTER);
        loadingTv.setPadding(0, 20, 0, 20);
        listContainer.addView(loadingTv);
        content.addView(scroll);

        final LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.VERTICAL);
        btnContainer.setGravity(Gravity.CENTER);
        content.addView(btnContainer);

        // 系统 API 读取 + 权限扫描较慢，必须异步，避免阻塞主线程
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<String[]> apps = getDailyThirdPartyApps();
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!dailyOverlayShowing || dailyAborted) return;
                        buildDailySelectList(wm, root, content, listContainer, btnContainer, hintTv, apps);
                    }
                });
            }
        }).start();
    }

    // ===== 日常模式第2阶段（续）：构建应用列表（多选）与操作按钮 =====
    private void buildDailySelectList(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content,
                                      final LinearLayout listContainer,
                                      final LinearLayout btnContainer,
                                      final TextView hintTv,
                                      final java.util.List<String[]> apps) {
        listContainer.removeAllViews();
        btnContainer.removeAllViews();
        hintTv.setText("");

        final java.util.List<CheckBox> boxes = new ArrayList<>();
        if (apps.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("未发现第三方应用");
            emptyTv.setTextColor(0xFFAAAAAA);
            emptyTv.setTextSize(15);
            emptyTv.setGravity(Gravity.CENTER);
            emptyTv.setPadding(0, 20, 0, 20);
            listContainer.addView(emptyTv);
        } else {
            for (String[] app : apps) {
                final CheckBox cb = new CheckBox(this);
                String label = app[0];
                String apkg = app[1];
                String mark = app.length > 2 && app[2] != null ? app[2] : "";
                boolean marked = !mark.isEmpty();
                cb.setText((marked ? "⚠ " : "") + label
                        + (marked ? "（" + mark + "）" : "") + "\n" + apkg);
                cb.setTextColor(marked ? 0xFFFF6B6B : Color.WHITE);
                cb.setTextSize(14);
                cb.setPadding(12, 8, 12, 8);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, 6, 0, 6);
                cb.setLayoutParams(clp);
                listContainer.addView(cb);
                boxes.add(cb);
            }
        }

        // ① 我已选择完成，立刻卸载
        final Button btnUninstall = new Button(this);
        btnUninstall.setText("我已选择完成，立刻卸载");
        btnUninstall.setTextColor(Color.WHITE);
        btnUninstall.setTextSize(18);
        btnUninstall.setBackgroundColor(0xFFD32F2F);
        btnUninstall.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        up.bottomMargin = 12;
        btnUninstall.setLayoutParams(up);
        btnContainer.addView(btnUninstall);

        // ② 我误触了，关闭
        final Button btnClose = new Button(this);
        btnClose.setText("我误触了，关闭");
        btnClose.setTextColor(0xFFDDDDDD);
        btnClose.setTextSize(15);
        btnClose.setBackgroundColor(0xFF555555);
        btnClose.setPadding(30, 16, 30, 16);
        btnClose.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        btnContainer.addView(btnClose);

        btnUninstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final java.util.List<String> selected = new ArrayList<>();
                for (int i = 0; i < boxes.size() && i < apps.size(); i++) {
                    if (boxes.get(i).isChecked()) selected.add(apps.get(i)[1]);
                }
                if (selected.isEmpty()) {
                    hintTv.setText("请先选择要卸载的应用");
                    return;
                }
                btnUninstall.setEnabled(false);
                btnClose.setEnabled(false);
                showDailyUninstallStage(wm, root, content, selected);
            }
        });

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDailyOverlay(wm, root);
            }
        });
    }

    // ===== 日常模式第3阶段：Shizuku 串联卸载；服务丢失则改用系统 API 卸载 =====
    private void showDailyUninstallStage(final WindowManager wm, final LinearLayout root,
                                         final LinearLayout content,
                                         final java.util.List<String> targets) {
        dailyForceStopLoop = false; // 停止 force-stop 循环，避免命令并发冲突

        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD25");
        icon.setTextSize(52);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        final TextView statusTv = new TextView(this);
        statusTv.setText("正在卸载所选应用...");
        statusTv.setTextColor(0xFFFFCC00);
        statusTv.setTextSize(16);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, 0, 0, 12);
        content.addView(statusTv);

        TextView tipTv = new TextView(this);
        tipTv.setText("请勿关闭屏幕，卸载完成后会提示重启手机");
        tipTv.setTextColor(0xFFAAAAAA);
        tipTv.setTextSize(13);
        tipTv.setGravity(Gravity.CENTER);
        tipTv.setPadding(0, 0, 0, 18);
        content.addView(tipTv);

        // 解除设备管理员时输出脚本的实时结果。
        // 这是权限操作（dpm remove-active-admin），用户应当看得见做了些什么，
        // 尤其是哪个应用的管理员被摘掉、哪个失败了。
        final TextView adminLogTv = new TextView(this);
        adminLogTv.setTextColor(0xFF9BE29B);
        adminLogTv.setTextSize(11);
        adminLogTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        adminLogTv.setGravity(Gravity.START);
        adminLogTv.setPadding(8, 8, 8, 8);
        adminLogTv.setTextIsSelectable(true); // 出问题时可长按复制回传
        android.widget.ScrollView adminScroll = new android.widget.ScrollView(this);
        adminScroll.setBackgroundColor(0xFF141414);
        adminScroll.setPadding(6, 6, 6, 6);
        adminScroll.addView(adminLogTv);
        LinearLayout.LayoutParams adminLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        adminLp.bottomMargin = 12;
        adminScroll.setLayoutParams(adminLp);
        adminScroll.setVisibility(View.GONE); // 有内容时才占位显示
        content.addView(adminScroll);

        // 逃生出口：仅关闭覆盖层，不再继续后续流程
        final Button btnExit = new Button(this);
        btnExit.setText("关闭（退出，不执行后续操作）");
        btnExit.setTextColor(0xFFAAAAAA);
        btnExit.setTextSize(14);
        btnExit.setBackgroundColor(0xFF3A3A3A);
        btnExit.setPadding(30, 16, 30, 16);
        btnExit.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(btnExit);
        btnExit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDailyOverlay(wm, root);
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                // 等待 force-stop 循环退出（最多 8 秒，让当前轮执行完，避免命令并发）
                long waitUntil = System.currentTimeMillis() + 8000;
                while (dailyForceStopLoop && System.currentTimeMillis() < waitUntil) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }

                final java.util.List<String> safe = new ArrayList<>();
                for (String t : targets) {
                    if (isProtectedFinalPkg(t)) continue; // 绝不清除自己 + 桌面宠物 + 白名单
                    safe.add(t);
                }
                if (safe.isEmpty()) {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            if (dailyOverlayShowing && !dailyAborted) {
                                showDailyRebootStage(wm, root, content, 0);
                            }
                        }
                    });
                    return;
                }

                // ==========================================================
                // 【前置步骤】先解除目标应用可能持有的「设备管理员」身份
                // ----------------------------------------------------------
                // 为什么必须放在卸载之前：
                //   持有设备管理员（DeviceAdmin）的应用，pm uninstall 会被系统拒绝
                //   （DevicePolicyManager 会阻止卸载受管应用），从而静默卸载失败。
                //   先 dpm remove-active-admin 摘掉它的管理员身份，卸载才能成功。
                //
                // 原需求给的是 `rish -c '...'`，但本应用已改用 Stellar 内核，
                // 而 Stellar 已移除 rish（上游 README 明确写了移除 rish）。
                // 等价做法：用 Stellar 的特权进程跑 sh，权限相同且更直接。
                //
                // ⚠️ 脚本实体已移到 assets/uninstall_helpers.sh，运行时先写进应用私有
                //    目录再 `sh <文件> <目标包名...>` 执行。放在文件里（而不是拼成超长
                //    命令行 stdio 灌进去）的好处：
                //      · 不会因为 stdin 提前关闭、命令过长而卡在半途；
                //      · 脚本内部每条命令都套了 timeout，见该文件顶部说明。
                // ==========================================================

                // ⚠️ 启动「后台持续 force-stop」——
                //    必须在解除管理员与批量卸载的**整个过程中**持续运行：
                //    目标应用若在这期间被拉起，会重新注册/干扰卸载，导致 pm uninstall 失败。
                //    循环会在 dailyForceStopLoop 被置 false（卸载结束）后自行退出。
                startUninstallForceStopLoop(safe);

                String result = runPreUninstallAdminRevoke(safe);
                Log.i(TAG, "日常模式：预卸载解除设备管理员 -> " + result);
                final String adminResult = result;

                // 把脚本输出显示给用户（有内容才展开那个滚动区）
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (adminResult != null && adminResult.trim().length() > 0) {
                                adminLogTv.setText(adminResult.trim());
                                adminScroll.setVisibility(View.VISIBLE);
                            }
                            statusTv.setText("已解除设备管理员限制，开始卸载所选应用（后台持续强制停止中）...");
                        } catch (Exception ignored) {}
                    }
                });

                boolean shizukuOk =
                        StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission();
                if (shizukuOk) {
                    // 串联：pm uninstall 应用包名1;pm uninstall 应用包名2;...
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < safe.size(); i++) {
                        if (i > 0) sb.append("; ");
                        sb.append("pm uninstall ").append(safe.get(i));
                    }
                    String uninstallOut = StellarUtils.runCommand(sb.toString(), 120000);
                    Log.w(TAG, "日常模式 Stellar 卸载结果: " + uninstallOut + " (共" + safe.size() + "个)");
                    // 执行过程中特权服务丢失 → 降级为系统 API 卸载
                    if (uninstallOut == null || uninstallOut.startsWith("ERROR:")) shizukuOk = false;
                }

                if (!shizukuOk) {
                    // 服务丢失 → 无法再用特权命令卸载。
                    // ⚠️ 后台 force-stop 循环必须在这里停掉：接下来要走系统 API
                    // 逐个拉起卸载确认框，应用被反复 force-stop 会干扰那个流程。
                    stopUninstallForceStopLoop();
                    uninstallDailyViaSystemApi(wm, root, content, statusTv, safe);
                    return;
                }

                // 卸载已完成 → 立刻停掉后台 force-stop 循环（删哨兵文件，最迟 0.5s 退出）
                stopUninstallForceStopLoop();
                Log.i(TAG, "日常模式：批量卸载完成，已停止后台 force-stop 循环");

                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!dailyOverlayShowing || dailyAborted) return;
                        showDailyRebootStage(wm, root, content, safe.size());
                    }
                });
            }
        }).start();
    }

    /**
     * 日常模式【后台任务】：在解除管理员 + 批量卸载的整个期间，
     * **持续循环**强制停止目标应用及所有第三方应用，直到卸载结束。
     *
     * <p>为什么需要：
     * 卸载是一个可能持续数十秒到几分钟的过程。期间目标应用若被系统或用户重新拉起，
     * 它会重新注册设备管理员、重建自己的进程，导致：
     *   · {@code pm uninstall} 因「应用处于活动状态 / 又是设备管理员」而失败；
     *   · 刚摘掉的设备管理员又被重新注册。
     * 持续 force-stop 能把它们钉在停止状态，显著提高卸载成功率。
     *
     * <p>停止条件：{@link #dailyForceStopLoop} 被置为 false
     * （卸载完成、走系统 API 降级、或用户关闭覆盖层时都会置 false）。
     * 同时受 {@link #dailyAborted} 与 {@link #dailyOverlayShowing} 约束，
     * 用户点「关闭」后循环立刻退出，不会变成失控的后台任务。
     *
     * <p>实现上用的是 Stellar 特权进程执行 shell 循环，而不是 Java 侧一条条发命令：
     * 循环体在设备上跑，避免每个周期都走一次 Binder IPC。
     *
     * <p><b>跨进程停止信号</b>：shell 循环跑在另一个进程里，读不到 Java 的
     * {@code dailyForceStopLoop} 变量，所以用一个**哨兵文件**通信：
     * 启动前创建该文件，循环每轮检查它是否还在；Java 侧要停就删掉文件，
     * 循环最迟 0.5 秒后自行退出。这样卸载一完成就立刻停，
     * 不会白跑满整个超时窗口继续去停应用。
     * 另有 600 轮（≈5 分钟）硬上限与 runCommand 超时 destroyForcibly 双重兜底。
     *
     * @param targets 用户勾选、即将卸载的包名（会优先且每轮都强制停止）
     */
    private void startUninstallForceStopLoop(final java.util.List<String> targets) {
        dailyForceStopLoop = true;

        // 哨兵文件：存在 = 继续循环；被删除 = 立即退出
        final java.io.File flag = new java.io.File(getFilesDir(), "uninstall_fs_loop.flag");
        try {
            flag.getParentFile().mkdirs();
            flag.createNewFile();
        } catch (Throwable tr) {
            Log.w(TAG, "创建 force-stop 哨兵文件失败，循环将持续到超时", tr);
        }
        uninstallForceStopFlag = flag;

        // 把目标包名拼进脚本里的 TARGETS 变量（只由包名组成，无注入风险）
        StringBuilder quoted = new StringBuilder();
        if (targets != null) {
            for (String p : targets) {
                if (p == null || p.isEmpty()) continue;
                if (!p.matches("[A-Za-z0-9_.]+")) continue; // 只接受合法包名字符
                quoted.append(p).append(' ');
            }
        }
        final String targetList = quoted.toString().trim();

        StringBuilder sb = new StringBuilder();
        sb.append("TARGETS=\"").append(targetList).append("\"\n");
        sb.append("FLAG=\"").append(flag.getAbsolutePath()).append("\"\n");
        sb.append("i=0\n");
        // 硬上限 600 轮 × 0.5s ≈ 5 分钟；正常情况下由哨兵文件提前结束
        sb.append("while [ $i -lt 600 ]; do\n");
        // ← 哨兵：Java 侧删掉文件即停（最迟 0.5 秒后退出）
        sb.append("  [ -f \"$FLAG\" ] || break\n");
        // ① 目标应用：每轮都停，最优先
        sb.append("  for p in $TARGETS; do am force-stop $p 2>/dev/null; done\n");
        // ② 其余第三方应用：每 4 轮扫一次，避免过于频繁
        sb.append("  if [ $((i % 4)) -eq 0 ]; then\n");
        sb.append("    for p in $(pm list packages -3 2>/dev/null | cut -d: -f2); do\n");
        // 不碰自己、不碰自家应用（桌面宠物/游龙工具），否则会把护盾自己停掉
        sb.append("      case \"$p\" in com.youlong.hd|com.youlong.zoo|com.youlong.tool) continue ;; esac\n");
        sb.append("      case \" $TARGETS \" in *\" $p \"*) continue ;; esac\n");
        sb.append("      am force-stop $p 2>/dev/null\n");
        sb.append("    done\n");
        sb.append("  fi\n");
        sb.append("  i=$((i+1))\n");
        sb.append("  sleep 0.5\n");
        sb.append("done\n");
        sb.append("rm -f \"$FLAG\"\n");
        sb.append("echo 'force-stop 循环结束'\n");

        // 用一个独立线程发起，绝不阻塞卸载主线程
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 超时给足（5 分钟），与脚本内 600×0.5s 的上限对齐
                    String out = StellarUtils.runCommand(sb.toString(), 300000);
                    Log.i(TAG, "后台 force-stop 循环退出: "
                            + (out == null ? "null" : out.substring(0, Math.min(80, out.length()))));
                } catch (Throwable tr) {
                    Log.w(TAG, "后台 force-stop 循环异常退出", tr);
                }
            }
        }, "daily-force-stop-loop");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "日常模式：已启动后台 force-stop 循环（目标 " + targetList + "）");
    }

    /**
     * 删除 force-stop 循环的哨兵文件，让后台循环最迟 0.5 秒后自行退出。
     * 卸载完成、降级到系统 API 卸载、或用户关闭覆盖层时都应调用。
     */
    private void stopUninstallForceStopLoop() {
        dailyForceStopLoop = false;
        try {
            if (uninstallForceStopFlag != null && uninstallForceStopFlag.exists()) {
                uninstallForceStopFlag.delete();
            }
        } catch (Throwable ignored) {}
    }

    /**
    /**
     * 日常模式【前置步骤】：解除设备上第三方应用的「设备管理员」身份。
     *
     * <p>在批量卸载之前执行。原因：持有 DeviceAdmin 的应用无法被 pm uninstall
     * 卸载（系统会拒绝），必须先执行 dpm remove-active-admin 摘掉它的管理员身份。
     *
     * <p><b>脚本实体在 assets/uninstall_helpers.sh</b>，本方法只负责：
     * 取出脚本 → 落到应用私有目录 → 以 `sh 脚本 目标包名...` 执行。
     * 之所以不再把脚本拼成一个超长字符串经 stdin 灌进去：
     * <ul>
     *   <li>stdin 长命令在部分 ROM 上会被截断或提前关闭，表现为「卡住不动」；</li>
     *   <li>脚本作为文件由设备上的 sh 直接读取，行边界清晰，不会有转义歧义。</li>
     * </ul>
     *
     * <p><b>关于 rish</b>：需求原文是 rish -c '...'。rish 是上游 Shizuku 自带的
     * 特权 shell 工具，而本应用已把内核换成 Stellar —— Stellar 上游 README 明确写了
     * 「移除 rish」。因此这里改用 Stellar 的特权进程执行 sh，运行身份与 rish 完全相同
     * （shell / root），效果等价且不依赖已不存在的组件。
     *
     * <p><b>为什么不会卡住</b>（详见 assets/uninstall_helpers.sh 顶部注释）：
     * <ol>
     *   <li>脚本内每条 dpm 命令都套 `timeout 15`，超时就跳过继续往下走；</li>
     *   <li>脚本有总时长上限（约 150 秒）与循环次数上限；</li>
     *   <li>本方法再套一层 180 秒硬超时，{@link StellarUtils#runCommand} 到期会
     *       destroyForcibly 杀掉整个进程树。</li>
     * </ol>
     *
     * <p><b>关于本应用自身的设备管理员</b>：2026-10-01 起本应用已**彻底移除**
     * DeviceAdmin 能力（清单/类/xml 全删）。但历史上激活过的设备可能仍残留
     * 一条记录，而升级安装不会自动撤销它，所以脚本里仍会显式尝试清理
     * com.youlong.hd/.DeviceAdminReceiver 的各个槽位，避免残留记录拖累卸载与升级。
     *
     * @param targets 即将卸载的包名列表，原样透传给脚本用于日志展示
     * @return 脚本输出；失败时以 "ERROR:" 开头（与 StellarUtils.runCommand 约定一致）
     */
    private String runPreUninstallAdminRevoke(final java.util.List<String> targets) {
        // 目标包名列表（原样透传给脚本，仅用于日志展示）
        StringBuilder quoted = new StringBuilder();
        if (targets != null) {
            for (String p : targets) {
                if (p == null || p.isEmpty()) continue;
                if (!p.matches("[A-Za-z0-9_.]+")) continue;  // 只接受合法包名字符
                quoted.append(p).append(' ');
            }
        }
        final String targetList = quoted.toString().trim();

        // 脚本从 assets 取出、落到应用私有目录后以**文件**方式执行。
        // 这样脚本里的每一行都由设备上的 sh 直接读，不存在「超长命令行经
        // stdin 灌入时被截断 / stdin 提前关闭导致挂住」的风险；
        // 而脚本内部对每条 dpm 命令都套了 timeout，保证不会卡死。
        java.io.File scriptFile = new java.io.File(getFilesDir(), "uninstall_helpers.sh");
        try {
            java.io.InputStream in = getAssets().open("uninstall_helpers.sh");
            java.io.FileOutputStream out = new java.io.FileOutputStream(scriptFile);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
            }
        } catch (Throwable tr) {
            Log.e(TAG, "写出 uninstall_helpers.sh 失败", tr);
            return "ERROR:无法写出脚本: " + tr;
        }
        // 赋可执行权限（不是必须，because 用 sh 显式解释；但便于排查时手动跑）
        try { scriptFile.setReadable(true, false); scriptFile.setExecutable(true, false); } catch (Throwable ignored) {}

        // 整脚本硬超时 180000ms：脚本内部已有 timeout 与总时长上限，
        // 正常最多 2~3 分钟；这里再兜一层，超时 StellarUtils 会 destroyForcibly。
        String cmd = "sh " + scriptFile.getAbsolutePath()
                + (targetList.isEmpty() ? "" : " " + targetList);
        Log.i(TAG, "执行预卸载脚本: " + cmd);
        String out = StellarUtils.runCommand(cmd, 180000);
        // 输出较长时截断，避免日志/UI 卡顿（只保留尾部最有用的部分）
        if (out != null && out.length() > 4000) {
            out = "…（前部已省略）…\n" + out.substring(out.length() - 4000);
        }
        return out;
    }

    // ===== 日常模式：Shizuku 服务丢失 → 提示并改用系统 API 逐个卸载 =====
    // 注意：覆盖层是 TYPE_APPLICATION_OVERLAY，悬浮在一切 Activity 之上，
    // 若不摘除会把系统卸载确认框挡住导致无法点击，因此先摘除覆盖层，
    // 逐个拉起系统卸载界面，全部拉起后再重新挂载覆盖层展示重启提示。
    private void uninstallDailyViaSystemApi(final WindowManager wm, final LinearLayout root,
                                            final LinearLayout content, final TextView statusTv,
                                            final java.util.List<String> pkgs) {
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    statusTv.setText("Shizuku服务丢失，现在将调用系统API进行卸载");
                    statusTv.setTextColor(0xFFFF6B6B);
                } catch (Exception ignored) {}
            }
        });

        // 让用户看清提示文案
        try { Thread.sleep(2500); } catch (InterruptedException ignored) {}

        // 摘除悬浮窗（否则会遮挡系统卸载确认框）
        h.post(new Runnable() {
            @Override
            public void run() {
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

        for (String p : pkgs) {
            if (dailyAborted) break;
            try {
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + p));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Log.w(TAG, "日常模式系统API卸载: " + p);
            } catch (Exception e) {
                Log.e(TAG, "日常模式系统API卸载失败: " + p, e);
            }
            // 给用户留出确认系统卸载弹窗的时间，再拉起下一个
            try { Thread.sleep(3500); } catch (InterruptedException e) { break; }
        }

        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        // 重新挂载覆盖层 → 展示重启提示
        h.post(new Runnable() {
            @Override
            public void run() {
                if (dailyAborted) return;
                try {
                    if (root.getParent() == null && dailyOverlayLp != null) {
                        wm.addView(root, dailyOverlayLp);
                    }
                    dailyOverlayShowing = true;
                    dailyOverlayRoot = root;
                    showDailyRebootStage(wm, root, content, pkgs.size());
                } catch (Exception e) {
                    Log.e(TAG, "日常模式重新挂载覆盖层失败", e);
                }
            }
        });
    }

    // ===== 日常模式第4阶段：提示重启（重启 / 不重启）=====
    // Shizuku 权限丢失时无法执行 reboot → 提示用户手动重启
    private void showDailyRebootStage(final WindowManager wm, final LinearLayout root,
                                      final LinearLayout content, final int count) {
        content.removeAllViews();

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDD04");
        icon.setTextSize(56);
        icon.setGravity(Gravity.CENTER);
        icon.setPadding(0, 0, 0, 8);
        content.addView(icon);

        TextView title = new TextView(this);
        title.setText("卸载完成，需要重启手机");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 10);
        content.addView(title);

        TextView desc = new TextView(this);
        desc.setText("已卸载 " + count + " 个应用\n重启后生效");
        desc.setTextColor(0xFFFFCC00);
        desc.setTextSize(15);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, 0, 0, 20);
        content.addView(desc);

        final TextView hintTv = new TextView(this);
        hintTv.setText("");
        hintTv.setTextColor(0xFFFF6B6B);
        hintTv.setTextSize(15);
        hintTv.setGravity(Gravity.CENTER);
        hintTv.setPadding(0, 0, 0, 12);
        content.addView(hintTv);

        final Button btnReboot = new Button(this);
        btnReboot.setText("重启");
        btnReboot.setTextColor(Color.WHITE);
        btnReboot.setTextSize(18);
        btnReboot.setBackgroundColor(0xFFD32F2F);
        btnReboot.setPadding(40, 24, 40, 24);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = 12;
        btnReboot.setLayoutParams(rp);
        content.addView(btnReboot);

        final Button btnLater = new Button(this);
        btnLater.setText("不重启");
        btnLater.setTextColor(0xFFDDDDDD);
        btnLater.setTextSize(15);
        btnLater.setBackgroundColor(0xFF555555);
        btnLater.setPadding(30, 18, 30, 18);
        btnLater.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(btnLater);

        btnReboot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Shizuku 权限丢失 → 无法执行 reboot，提示用户手动重启
                if (!(StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission())) {
                    hintTv.setText("Shizuku 权限已丢失，请手动重启手机");
                    return;
                }
                btnReboot.setEnabled(false);
                btnReboot.setText("正在重启...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String r = StellarUtils.runCommand("reboot", 10000);
                        Log.w(TAG, "日常模式 reboot 结果: " + r);
                    }
                }).start();
            }
        });

        btnLater.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDailyOverlay(wm, root);
            }
        });
    }

    // ===== 日常模式：系统 API 获取全部第三方应用，带无障碍/悬浮窗的排在前面 =====
    // 返回元素：{应用名, 包名, 标记}，标记为 ""（普通）/ "无障碍" / "悬浮窗" / "无障碍(未启用)"
    private java.util.List<String[]> getDailyThirdPartyApps() {
        java.util.List<String[]> highRiskList = new ArrayList<>();
        java.util.List<String[]> normalList = new ArrayList<>();
        PackageManager pm = getPackageManager();

        // 1. 已启用无障碍服务的包
        java.util.Set<String> accessibilityPkgs = new java.util.HashSet<>();
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null) {
                for (String s : enabled.split(":")) {
                    if (s.contains("/")) accessibilityPkgs.add(s.substring(0, s.indexOf('/')));
                }
            }
        } catch (Exception ignored) {}

        // 2. 声明了无障碍服务的包（带有无障碍但未启用）
        java.util.Set<String> declaredA11yPkgs = new java.util.HashSet<>();
        try {
            Intent a11yIntent = new Intent(
                    android.accessibilityservice.AccessibilityService.SERVICE_INTERFACE);
            java.util.List<android.content.pm.ResolveInfo> ris = pm.queryIntentServices(a11yIntent, 0);
            if (ris != null) {
                for (android.content.pm.ResolveInfo ri : ris) {
                    if (ri != null && ri.serviceInfo != null && ri.serviceInfo.packageName != null) {
                        declaredA11yPkgs.add(ri.serviceInfo.packageName);
                    }
                }
            }
        } catch (Exception ignored) {}

        AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);

        // 系统 API：列出全部已安装应用，过滤掉系统应用后即为第三方应用
        java.util.List<ApplicationInfo> apps = null;
        try {
            apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (Exception e) {
            Log.e(TAG, "日常模式 getInstalledApplications 失败", e);
        }
        if (apps == null) return new ArrayList<>();

        for (ApplicationInfo ai : apps) {
            try {
                if (ai == null || ai.packageName == null || ai.packageName.isEmpty()) continue;
                // 仅保留第三方应用（排除系统应用 / 系统应用更新）
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                String pkg = ai.packageName;
                if (isProtectedFinalPkg(pkg)) continue; // 排除自己 + 桌面宠物 + 白名单

                String label = pkg;
                try {
                    CharSequence cs = ai.loadLabel(pm);
                    if (cs != null && cs.length() > 0) label = cs.toString();
                } catch (Exception ignored) {}

                // 排序依据：带无障碍或悬浮窗的放在前面
                String mark = "";
                if (accessibilityPkgs.contains(pkg)) {
                    mark = "无障碍";
                } else if (isDailyOverlayGranted(appOps, ai)) {
                    mark = "悬浮窗";
                } else if (declaredA11yPkgs.contains(pkg)) {
                    mark = "无障碍(未启用)";
                }

                if (mark.isEmpty()) {
                    normalList.add(new String[]{label, pkg, ""});
                } else {
                    highRiskList.add(new String[]{label, pkg, mark});
                }
            } catch (Exception ignored) {}
        }

        // 带无障碍 / 悬浮窗的应用优先显示
        java.util.List<String[]> all = new ArrayList<>(highRiskList);
        all.addAll(normalList);
        Log.i(TAG, "日常模式第三方应用列表：带无障碍/悬浮窗 " + highRiskList.size()
                + " 个，普通 " + normalList.size() + " 个");
        return all;
    }

    // ===== 日常模式：判断应用是否已获得悬浮窗权限（AppOps，可查询任意应用）=====
    private boolean isDailyOverlayGranted(AppOpsManager appOps, ApplicationInfo ai) {
        try {
            if (appOps == null || ai == null) return false;
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    ai.uid, ai.packageName);
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    // ======================================================================
    // ===== 病毒库识别：循环检测 → 强制卸载 / 询问卸载 =====================
    // ======================================================================
    // 【开源版说明】这一段依赖"病毒库"数据，而本开源包**不包含病毒库**
    // （数据源地址与 5 个列表文件均已移除，见 VirusDb / StrX 的说明）：
    //   · VirusDb.get(this) 永远返回空库 → db.isEmpty() 为真 → 本方法直接返回；
    //   · 因此开源版不会自动卸载任何应用，检测逻辑保留仅供接入自有数据源时复用。
    //
    // 原版的 5 个列表：①/③ = 100% 病毒（包名 / 应用名称），
    // ②/④/⑤ = 可能病毒（包名 / 应用名称 / 关键字）。
    // 检测：守护开启后每 4 秒一轮循环检测，每 50 轮重新拉取服务器最新数据。
    // 触发：命中 ① / ③ → 通过特权服务串联 pm uninstall 强制卸载，
    //       未连接则改用系统 API（Intent.ACTION_DELETE）卸载，
    //       卸载后弹窗提示"已强制卸载"；
    //       命中 ② / ④ / ⑤ → 弹窗询问"可能为伪病毒，是否卸载"，确认后流程同上。

    // ===== 病毒库：单轮检测 =====
    private void virusScanOnce() {
        // 【自动拦截总开关】病毒库页面新增，默认开启。
        // 关闭后本轮直接跳过：既不弹窗也不卸载，做到「关掉就真的不拦」。
        // 该值由 MainActivity.setAutoBlockEnabled 写入；每次开启实时守护（startProtect）
        // 都会被原生强制写回 true（用户要求：开守护必须自动开启自动拦截）。
        // 每轮实时读取，改开关无需重启服务即可生效。
        if (!getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getBoolean("auto_block_on", true)) {
            return;
        }

        final VirusDb.Data db = VirusDb.get(this);
        if (db.isEmpty()) return;

        // ===== 每检测 50 次 → 重新拉取服务器最新数据 =====
        virusCheckCount++;
        if (virusCheckCount >= VIRUS_RECHECK_EVERY) {
            virusCheckCount = 0;
            Log.i(TAG, "病毒库：已检测 " + VIRUS_RECHECK_EVERY + " 次，重新拉取服务器最新数据");
            VirusDb.refreshAsync(this);
        }

        // 正在弹窗 / 正在批量卸载 / 其他模式覆盖层占用 → 本轮跳过（不标记已处理，下轮继续）
        if (virusPopupShowing || virusUninstallRunning) return;
        if (dailyOverlayShowing || finalForceStopLoop) return;

        // ===== 待检测应用清单（缓存，每 10 轮重建一次）=====
        java.util.List<String[]> apps = virusAppCache;
        if (apps == null || virusAppCacheTick >= 10) {
            apps = buildVirusScanAppList();
            virusAppCache = apps;
            virusAppCacheTick = 0;
        }
        virusAppCacheTick++;

        // 已卸载的应用移出"已处理"记录，便于重装后再次检测
        for (java.util.Iterator<String> it = virusHandledPkgs.iterator(); it.hasNext(); ) {
            String p = it.next();
            if (!isAppInstalled(p)) it.remove();
        }

        final List<String> certain = new ArrayList<>();       // 100% 病毒（①/③）
        final List<String[]> suspect = new ArrayList<>();     // 可能病毒（②/④/⑤）{包名, 名称, 原因}
        for (String[] a : apps) {
            if (a == null || a.length < 2) continue;
            String pkg = a[0];
            String label = a[1];
            if (pkg == null || pkg.isEmpty()) continue;
            if (isProtectedFinalPkg(pkg)) continue;           // 自己 / 桌面宠物 / 游龙工具 / 白名单
            if (virusHandledPkgs.contains(pkg)) continue;

            if (db.isCertain(pkg, label)) {
                certain.add(pkg);
            } else {
                String why = db.matchSuspect(pkg, label);
                if (why != null) suspect.add(new String[]{pkg, label, why});
            }
        }

        if (!certain.isEmpty()) {
            Log.w(TAG, "病毒库：发现 " + certain.size() + " 个 100% 病毒应用，立即强制卸载");
            for (String p : certain) Log.w(TAG, "病毒库命中(100%): " + p);
            // ⚠️ 扫描部分现在在后台线程跑（见 runTickHeavyWorkAsync），
            //   但 startVirusUninstall/showVirusAskOverlay 要 new View + wm.addView，
            //   必须在主线程执行，否则后台线程无 Looper → ViewRootImpl 构造抛异常闪退。
            final List<String> certainMain = certain;
            h.post(() -> startVirusUninstall(certainMain, true));
            return;
        }
        if (!suspect.isEmpty()) {
            Log.w(TAG, "病毒库：发现 " + suspect.size() + " 个可疑应用，询问用户是否卸载");
            for (String[] s : suspect) Log.w(TAG, "病毒库命中(可疑): " + s[0] + " / " + s[1] + " / " + s[2]);
            final List<String[]> suspectMain = suspect;
            h.post(() -> showVirusAskOverlay(suspectMain));
        }
    }

    // ===== 病毒库：构建检测用应用清单（仅第三方应用：包名 + 应用名称）=====
    private java.util.List<String[]> buildVirusScanAppList() {
        java.util.List<String[]> out = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo ai : apps) {
                if (ai == null || ai.packageName == null) continue;
                // 仅第三方应用（系统应用 / 系统自带更新包不算）
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
                String label = "";
                try {
                    CharSequence cs = pm.getApplicationLabel(ai);
                    if (cs != null) label = cs.toString().trim();
                } catch (Exception ignored) {}
                out.add(new String[]{ai.packageName, label});
            }
        } catch (Exception e) {
            Log.e(TAG, "构建病毒检测清单失败", e);
        }
        return out;
    }

    // ===== 病毒库：开始卸载 =====
    // certain=true  → 100% 病毒（①/③），无需询问，直接强制卸载
    // certain=false → 用户已确认的可疑应用（②/④/⑤），卸载流程完全一致
    private void startVirusUninstall(final List<String> pkgs, final boolean certain) {
        if (virusUninstallRunning) return;
        if (pkgs == null || pkgs.isEmpty()) return;
        virusUninstallRunning = true;

        final List<String> targets = new ArrayList<>();
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            if (isProtectedFinalPkg(p)) continue;   // 受保护应用绝不卸载
            virusHandledPkgs.add(p);                // 标记已处理，避免每轮重复弹窗
            targets.add(p);
        }
        if (targets.isEmpty()) {
            virusUninstallRunning = false;
            return;
        }

        // 卸载前先摘掉自己的悬浮窗，避免遮挡系统卸载确认框
        h.post(new Runnable() {
            @Override
            public void run() {
                removeVirusOverlayIfAny();
            }
        });

        final boolean shizukuOk =
                StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission();

        if (shizukuOk) {
            // ===== 有 Shizuku：串联 pm uninstall 应用包名1;pm uninstall 应用包名2;... =====
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        StringBuilder sb = new StringBuilder();
                        int cnt = 0;
                        for (String p : targets) {
                            if (cnt > 0) sb.append("; ");
                            sb.append("pm uninstall ").append(p);
                            cnt++;
                            if (cnt >= 10) {
                                runFinalCommand(sb.toString());
                                sb.setLength(0);
                                cnt = 0;
                            }
                        }
                        if (cnt > 0) runFinalCommand(sb.toString());
                        Log.w(TAG, "病毒库：Shizuku 批量卸载完成 " + targets.size() + " 个应用");
                    } catch (Exception e) {
                        Log.e(TAG, "病毒库 Shizuku 卸载异常", e);
                    }
                    finishVirusUninstall(targets);
                }
            }).start();
        } else {
            // ===== 无 Shizuku：先弹窗告知并征得同意，再调用系统 API 逐个卸载 =====
            Log.w(TAG, "病毒库：Shizuku 未连接，先弹窗确认再系统卸载 " + targets.size() + " 个应用");
            h.post(new Runnable() {
                @Override
                public void run() {
                    showVirusNoShizukuConfirm(targets);
                }
            });
        }
    }

    // ===== 病毒库：未连接 Shizuku 时的卸载前置确认弹窗 =====
    // 未连接 Shizuku 只能用 Intent.ACTION_DELETE，系统会逐个弹出卸载确认框，
    // 必须先明确告知用户并取得同意，避免用户在不知情时被连续弹窗打扰/误点。
    private void showVirusNoShizukuConfirm(final List<String> targets) {
        try {
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) { virusUninstallRunning = false; return; }
            removeVirusOverlayIfAny();

            final LinearLayout card = buildVirusCard();

            TextView icon = new TextView(this);
            icon.setText("\uD83D\uDEE1\uFE0F");
            icon.setTextSize(42);
            icon.setGravity(Gravity.CENTER);
            card.addView(icon);

            TextView title = new TextView(this);
            title.setText("未连接 Shizuku");
            title.setTextColor(0xFFFFCC00);
            title.setTextSize(20);
            title.setGravity(Gravity.CENTER);
            card.addView(title);

            TextView sub = new TextView(this);
            sub.setText("检测到 " + targets.size() + " 个病毒应用。\n"
                    + "未连接 Shizuku 时无法静默卸载，\n"
                    + "需要你在系统卸载框中逐个点击「确定」。\n\n是否继续卸载？");
            sub.setTextColor(0xFFFFDDDD);
            sub.setTextSize(14);
            sub.setLineSpacing(dp(4), 1f);
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, dp(10), 0, dp(10));
            card.addView(sub);

            ScrollView sv = new ScrollView(this);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (String p : targets) {
                TextView item = new TextView(this);
                item.setText("• " + appLabelOf(p) + "\n    " + p);
                item.setTextColor(0xFFFFDDDD);
                item.setTextSize(13);
                item.setPadding(0, dp(4), 0, dp(4));
                list.addView(item);
            }
            sv.addView(list);
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int listH = Math.min((int) (screenH * 0.32f), dp(30) + targets.size() * dp(46));
            if (listH < dp(60)) listH = dp(60);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, listH));
            card.addView(sv);

            final Button btnGo = new Button(this);
            btnGo.setText("继续卸载");
            btnGo.setTextColor(Color.WHITE);
            btnGo.setTextSize(17);
            btnGo.setBackgroundColor(0xFFD32F2F);
            LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp1.topMargin = dp(14);
            btnGo.setLayoutParams(bp1);
            card.addView(btnGo);

            final Button btnLater = new Button(this);
            btnLater.setText("稍后处理");
            btnLater.setTextColor(0xFFDDDDDD);
            btnLater.setTextSize(15);
            btnLater.setBackgroundColor(0xFF555555);
            LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp2.topMargin = dp(8);
            btnLater.setLayoutParams(bp2);
            card.addView(btnLater);

            // 120 秒无响应兜底：按「稍后处理」，避免弹窗卡住导致后续检测永久暂停
            final Runnable[] timeout = new Runnable[1];

            btnGo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (timeout[0] != null) h.removeCallbacks(timeout[0]);
                    removeVirusOverlayIfAny();
                    runSystemUninstall(targets);
                }
            });
            btnLater.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (timeout[0] != null) h.removeCallbacks(timeout[0]);
                    removeVirusOverlayIfAny();
                    // 用户选择稍后处理：复位运行标志；已加入 virusHandledPkgs，不重复打扰
                    virusUninstallRunning = false;
                    Log.w(TAG, "病毒库：用户在「未连接 Shizuku」弹窗中选择稍后处理");
                }
            });

            addVirusOverlay(wm, card);

            timeout[0] = new Runnable() {
                @Override
                public void run() {
                    if (!virusPopupShowing || virusOverlayView != card) return;
                    Log.w(TAG, "病毒库：未连接 Shizuku 弹窗超时未响应，按稍后处理");
                    removeVirusOverlayIfAny();
                    virusUninstallRunning = false;
                }
            };
            h.postDelayed(timeout[0], 120000);
            Log.i(TAG, "病毒库：已弹出「未连接 Shizuku」确认弹窗，共 " + targets.size() + " 个应用");
        } catch (SecurityException e) {
            // 无悬浮窗权限 → 无法弹窗，退回原始行为直接系统卸载
            Log.w(TAG, "病毒库：无悬浮窗权限，跳过确认弹窗直接系统卸载", e);
            runSystemUninstall(targets);
        } catch (Exception e) {
            Log.e(TAG, "showVirusNoShizukuConfirm 失败", e);
            runSystemUninstall(targets);
        }
    }

    // ===== 病毒库：无 Shizuku 时的系统卸载（逐个 Intent.ACTION_DELETE，间隔 3.5 秒）=====
    private void runSystemUninstall(final List<String> targets) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < targets.size(); i++) {
                    final String p = targets.get(i);
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Intent del = new Intent(Intent.ACTION_DELETE,
                                        Uri.parse("package:" + p));
                                del.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                        | 0x00000004);
                                startActivity(del);
                                Log.i(TAG, "病毒库：已发起系统卸载 " + p);
                            } catch (Exception e) {
                                Log.e(TAG, "病毒库系统卸载失败: " + p, e);
                            }
                        }
                    });
                    if (i < targets.size() - 1) {
                        try { Thread.sleep(3500); } catch (InterruptedException e) { break; }
                    }
                }
                finishVirusUninstall(targets);
            }
        }).start();
    }

    // ===== 病毒库：取应用显示名（失败返回包名）=====
    private String appLabelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return cs == null ? pkg : cs.toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ===== 病毒库：卸载收尾（未卸载成功的移出记录，下轮继续；弹出告知弹窗）=====
    private void finishVirusUninstall(final List<String> targets) {
        try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
        for (String p : targets) {
            if (isAppInstalled(p)) {
                virusHandledPkgs.remove(p);   // 未卸载成功 → 下一轮继续尝试
                Log.w(TAG, "病毒库：应用仍未卸载，稍后继续尝试: " + p);
            }
        }
        virusAppCache = null;
        final List<String> ok = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        for (String p : targets) {
            if (isAppInstalled(p)) failed.add(p); else ok.add(p);
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                // 主线程内先复位再弹窗，避免下一轮检测重复发起卸载
                virusUninstallRunning = false;
                showVirusDoneOverlay(ok, failed);
            }
        });
    }

    // ===== 病毒库：弹出"好的"告知弹窗（我们发现了威胁病毒，已强制卸载成功）=====
    private void showVirusDoneOverlay(final List<String> ok, final List<String> failed) {
        try {
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;
            removeVirusOverlayIfAny();

            final LinearLayout card = buildVirusCard();

            TextView icon = new TextView(this);
            icon.setText("\uD83D\uDEE1");
            icon.setTextSize(44);
            icon.setGravity(Gravity.CENTER);
            card.addView(icon);

            TextView title = new TextView(this);
            title.setText("我们发现了威胁病毒，已强制卸载成功");
            title.setTextColor(Color.WHITE);
            title.setTextSize(19);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(8), 0, dp(10));
            card.addView(title);

            if (!ok.isEmpty()) {
                TextView tv = new TextView(this);
                tv.setText("已卸载：" + joinPkgList(ok, 6));
                tv.setTextColor(0xFF7CFF8A);
                tv.setTextSize(13);
                tv.setGravity(Gravity.CENTER);
                card.addView(tv);
            }
            if (!failed.isEmpty()) {
                TextView tv2 = new TextView(this);
                tv2.setText("未能卸载（可手动处理）：" + joinPkgList(failed, 6));
                tv2.setTextColor(0xFFFF9500);
                tv2.setTextSize(13);
                tv2.setGravity(Gravity.CENTER);
                tv2.setPadding(0, dp(6), 0, 0);
                card.addView(tv2);
            }

            final Button okBtn = new Button(this);
            okBtn.setText("好的");
            okBtn.setTextColor(Color.WHITE);
            okBtn.setTextSize(17);
            okBtn.setBackgroundColor(0xFFD32F2F);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(16);
            okBtn.setLayoutParams(bp);
            okBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lastShakeRescueTime = System.currentTimeMillis();
                    removeVirusOverlayIfAny();
                }
            });
            card.addView(okBtn);

            addVirusOverlay(wm, card);
            Log.i(TAG, "病毒库：已弹出卸载完成告知弹窗");
        } catch (SecurityException e) {
            Log.w(TAG, "病毒库弹窗权限不足(SYSTEM_ALERT_WINDOW)", e);
        } catch (Exception e) {
            Log.e(TAG, "showVirusDoneOverlay 失败", e);
        }
    }

    // ===== 病毒库：弹出"可能伪病毒，是否卸载"询问弹窗 =====
    private void showVirusAskOverlay(final java.util.List<String[]> suspect) {
        try {
            final WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;
            if (virusPopupShowing) return;
            removeVirusOverlayIfAny();

            final LinearLayout card = buildVirusCard();

            TextView icon = new TextView(this);
            icon.setText("\u26A0\uFE0F");
            icon.setTextSize(42);
            icon.setGravity(Gravity.CENTER);
            card.addView(icon);

            TextView title = new TextView(this);
            title.setText("检测到可疑软件");
            title.setTextColor(0xFFFF6B6B);
            title.setTextSize(20);
            title.setGravity(Gravity.CENTER);
            card.addView(title);

            TextView sub = new TextView(this);
            sub.setText("以下软件可能为伪病毒，是否卸载？");
            sub.setTextColor(0xFFFFCC00);
            sub.setTextSize(14);
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, dp(8), 0, dp(8));
            card.addView(sub);

            ScrollView sv = new ScrollView(this);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (String[] s : suspect) {
                TextView item = new TextView(this);
                item.setText("• " + (s[1] == null || s[1].isEmpty() ? s[0] : s[1])
                        + "\n    " + s[0] + "（" + s[2] + "）");
                item.setTextColor(0xFFFFDDDD);
                item.setTextSize(13);
                item.setPadding(0, dp(4), 0, dp(4));
                list.addView(item);
            }
            sv.addView(list);
            // 固定高度：内容少时按内容高度，内容多时限制在屏幕 40% 内可滚动
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int listH = Math.min((int) (screenH * 0.40f), dp(30) + suspect.size() * dp(46));
            if (listH < dp(70)) listH = dp(70);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, listH));
            card.addView(sv);

            final Button btnUninstall = new Button(this);
            btnUninstall.setText("卸载");
            btnUninstall.setTextColor(Color.WHITE);
            btnUninstall.setTextSize(17);
            btnUninstall.setBackgroundColor(0xFFD32F2F);
            LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp1.topMargin = dp(14);
            btnUninstall.setLayoutParams(bp1);
            card.addView(btnUninstall);

            final Button btnSkip = new Button(this);
            btnSkip.setText("不卸载");
            btnSkip.setTextColor(0xFFDDDDDD);
            btnSkip.setTextSize(15);
            btnSkip.setBackgroundColor(0xFF555555);
            LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bp2.topMargin = dp(8);
            btnSkip.setLayoutParams(bp2);
            card.addView(btnSkip);

            // 120 秒无响应兜底：视为"不卸载"，避免弹窗卡住导致后续检测永久暂停
            final Runnable[] timeout = new Runnable[1];

            btnUninstall.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (timeout[0] != null) h.removeCallbacks(timeout[0]);
                    final List<String> pkgs = new ArrayList<>();
                    for (String[] s : suspect) pkgs.add(s[0]);
                    removeVirusOverlayIfAny();
                    // 与 ①/③（100% 病毒）完全相同的卸载逻辑
                    startVirusUninstall(pkgs, false);
                }
            });
            btnSkip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (timeout[0] != null) h.removeCallbacks(timeout[0]);
                    for (String[] s : suspect) virusHandledPkgs.add(s[0]);  // 不再重复询问
                    lastShakeRescueTime = System.currentTimeMillis();
                    removeVirusOverlayIfAny();
                }
            });

            addVirusOverlay(wm, card);

            timeout[0] = new Runnable() {
                @Override
                public void run() {
                    if (!virusPopupShowing || virusOverlayView != card) return;
                    Log.w(TAG, "病毒库：可疑应用询问超时未响应，按不卸载处理");
                    for (String[] s : suspect) virusHandledPkgs.add(s[0]);
                    removeVirusOverlayIfAny();
                }
            };
            h.postDelayed(timeout[0], 120000);
            Log.i(TAG, "病毒库：已弹出可疑应用询问弹窗，共 " + suspect.size() + " 个");
        } catch (SecurityException e) {
            Log.w(TAG, "病毒库弹窗权限不足(SYSTEM_ALERT_WINDOW)", e);
        } catch (Exception e) {
            Log.e(TAG, "showVirusAskOverlay 失败", e);
        }
    }

    // ===== 病毒库：创建卡片样式容器 =====
    private LinearLayout buildVirusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(18));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF15182B);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(2), 0xFFD32F2F);
        card.setBackground(bg);
        return card;
    }

    // ===== 病毒库：挂载弹窗（卡片尺寸，不遮挡系统卸载确认框）=====
    private void addVirusOverlay(final WindowManager wm, final LinearLayout card) {
        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.86),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        wm.addView(card, lp);
        virusOverlayWm = wm;
        virusOverlayView = card;
        virusPopupShowing = true;
    }

    // ===== 病毒库：移除弹窗 =====
    private void removeVirusOverlayIfAny() {
        virusPopupShowing = false;
        try {
            if (virusOverlayWm != null && virusOverlayView != null) {
                virusOverlayWm.removeView(virusOverlayView);
            }
        } catch (Exception ignored) {}
        virusOverlayView = null;
        virusOverlayWm = null;
    }

    // ===== 病毒库：包名列表拼接（最多显示 limit 个）=====
    private String joinPkgList(java.util.List<String> list, int limit) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(list.size(), limit);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append("、");
            sb.append(list.get(i));
        }
        if (list.size() > limit) sb.append(" 等 ").append(list.size()).append(" 个");
        return sb.toString();
    }

    // ===== 终结模式：获取第三方应用列表（名称, 包名, 高敏感标记），高敏感权限应用优先 =====
    // 主通道使用 pm list packages -3（Shizuku，已验证可用），避免 PackageManager 在部分
    // ROM/环境下返回受限导致"未发现第三方应用"；PackageManager 仅用于补充名称与权限。
    private java.util.List<String[]> getFinalThirdPartyApps() {
        java.util.List<String[]> highRiskList = new ArrayList<>();
        java.util.List<String[]> normalList = new ArrayList<>();
        PackageManager pm = getPackageManager();

        // 已启用无障碍服务的包
        java.util.Set<String> accessibilityPkgs = new java.util.HashSet<>();
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null) {
                for (String s : enabled.split(":")) {
                    if (s.contains("/")) accessibilityPkgs.add(s.substring(0, s.indexOf('/')));
                }
            }
        } catch (Exception ignored) {}

        // 已启用通知监听的包
        java.util.Set<String> notifListenerPkgs = new java.util.HashSet<>();
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            if (enabled != null) {
                for (String s : enabled.split(":")) {
                    if (s.contains("/")) notifListenerPkgs.add(s.substring(0, s.indexOf('/')));
                }
            }
        } catch (Exception ignored) {}

        AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);

        // 主通道：pm list packages -3 仅列第三方应用
        java.util.List<String> pkgNames = getThirdPartyPackageNamesFromShell();

        // 兜底通道：PackageManager API（Shizuku 不可用或命令失败时）
        if (pkgNames.isEmpty()) {
            try {
                java.util.List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                if (apps != null) {
                    for (ApplicationInfo ai : apps) {
                        if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                        pkgNames.add(ai.packageName);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "getInstalledApplications 兜底失败", e);
            }
        }

        for (String pkg : pkgNames) {
            try {
                if (isProtectedFinalPkg(pkg)) continue; // 过滤自己 + 桌面宠物
                if (pkg.startsWith("com.android.") || pkg.startsWith("com.google.")) continue;

                // 补充名称与权限声明（PackageManager 失败时以包名兜底，保证列表不为空）
                ApplicationInfo ai = null;
                String label = pkg;
                String[] perms = null;
                try {
                    ai = pm.getApplicationInfo(pkg, 0);
                    if (ai != null) label = ai.loadLabel(pm).toString();
                } catch (Exception ignored) {}
                try {
                    perms = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions;
                } catch (Exception ignored) {}

                boolean highRisk = isFinalHighRiskApp(pkg, ai, perms, accessibilityPkgs,
                        notifListenerPkgs, appOps);
                if (highRisk) {
                    highRiskList.add(new String[]{label, pkg, "1"});
                } else {
                    normalList.add(new String[]{label, pkg, "0"});
                }
            } catch (Exception ignored) {}
        }

        // 高敏感权限应用优先显示
        java.util.List<String[]> all = new ArrayList<>(highRiskList);
        all.addAll(normalList);
        return all;
    }

    // ===== 终结模式：pm list packages -3 获取第三方包名（Shizuku 执行） =====
    private java.util.List<String> getThirdPartyPackageNamesFromShell() {
        java.util.List<String> names = new ArrayList<>();
        try {
            if (!(StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission())) {
                return names; // Shizuku 不可用，由调用方走 PackageManager 兜底
            }
            String r = StellarUtils.runCommand("pm list packages -3", 15000);
            if (r != null) {
                String[] lines = r.split("\n");
                for (String line : lines) {
                    String t = line.trim();
                    if (t.startsWith("package:")) {
                        String name = cleanPkgName(t.substring(8));
                        if (!name.isEmpty()) names.add(name);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "pm list packages -3 失败", e);
        }
        return names;
    }

    // ===== 终结模式：判断应用是否高敏感（悬浮窗/无障碍/通知监听/设备管理员/高危权限） =====
    private boolean isFinalHighRiskApp(String pkg, ApplicationInfo app, String[] perms,
                                       java.util.Set<String> accessibilityPkgs,
                                       java.util.Set<String> notifListenerPkgs,
                                       AppOpsManager appOps) {
        try {
            // 1. 已启用无障碍服务
            if (accessibilityPkgs.contains(pkg)) return true;
            // 2. 已启用通知监听
            if (notifListenerPkgs.contains(pkg)) return true;
            // 3. 已开启悬浮窗权限（AppOps）
            if (appOps != null && app != null) {
                try {
                    int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                            app.uid, pkg);
                    if (mode == AppOpsManager.MODE_ALLOWED) return true;
                } catch (Exception ignored) {}
            }
            // 4. 声明的高危权限（悬浮窗/无障碍/通知监听/设备管理员/开机自启）
            if (perms != null) {
                for (String p : perms) {
                    if (p == null) continue;
                    if (p.equals("android.permission.SYSTEM_ALERT_WINDOW")
                            || p.equals("android.permission.BIND_ACCESSIBILITY_SERVICE")
                            || p.equals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")
                            || p.equals("android.permission.BIND_DEVICE_ADMIN")
                            || p.equals("android.permission.RECEIVE_BOOT_COMPLETED")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "isFinalHighRiskApp 失败: " + pkg, e);
        }
        return false;
    }

    // ===== 终结模式：系统 API 检测高危权限应用（无障碍/悬浮窗/监听等） =====
    private java.util.List<String> detectFinalHighRiskApps() {
        java.util.List<String> risky = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();

            // 已启用无障碍服务的包
            java.util.Set<String> accessibilityPkgs = new java.util.HashSet<>();
            try {
                String enabled = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (enabled != null) {
                    for (String s : enabled.split(":")) {
                        if (s.contains("/")) accessibilityPkgs.add(s.substring(0, s.indexOf('/')));
                    }
                }
            } catch (Exception ignored) {}

            // 已启用通知监听的包
            java.util.Set<String> notifListenerPkgs = new java.util.HashSet<>();
            try {
                String enabled = Settings.Secure.getString(getContentResolver(),
                        "enabled_notification_listeners");
                if (enabled != null) {
                    for (String s : enabled.split(":")) {
                        if (s.contains("/")) notifListenerPkgs.add(s.substring(0, s.indexOf('/')));
                    }
                }
            } catch (Exception ignored) {}

            // 主通道：pm list packages -3 获取第三方包名；PackageManager 兜底
            java.util.List<String> pkgNames = getThirdPartyPackageNamesFromShell();
            if (pkgNames.isEmpty()) {
                try {
                    java.util.List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                    if (apps != null) {
                        for (ApplicationInfo ai : apps) {
                            if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                            pkgNames.add(ai.packageName);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "detectFinalHighRiskApps getInstalledApplications 兜底失败", e);
                }
            }

            // 一次性获取所有已安装应用的权限声明（避免逐个 getPackageInfo，大幅提速）
            java.util.Map<String, String[]> permsMap = new java.util.HashMap<>();
            try {
                java.util.List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
                if (pkgs != null) {
                    for (PackageInfo pi : pkgs) {
                        permsMap.put(pi.packageName, pi.requestedPermissions);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "detectFinalHighRiskApps getInstalledPackages 失败", e);
            }

            // 已开启悬浮窗权限的包（AppOps 检测任意应用）
            AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            java.util.Set<String> overlayPkgs = new java.util.HashSet<>();
            if (appOps != null) {
                for (String pkg : pkgNames) {
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                                ai.uid, pkg);
                        if (mode == AppOpsManager.MODE_ALLOWED) overlayPkgs.add(pkg);
                    } catch (Exception ignored) {}
                }
            }

            for (String pkg : pkgNames) {
                try {
                    // 跳过系统应用和自己
                    if (isProtectedFinalPkg(pkg)) continue; // 过滤自己 + 桌面宠物
                    if (pkg.startsWith("com.android.") || pkg.startsWith("com.google.")) continue;

                    boolean hasDanger = accessibilityPkgs.contains(pkg)
                            || notifListenerPkgs.contains(pkg)
                            || overlayPkgs.contains(pkg);

                    // 声明了高危权限
                    if (!hasDanger) {
                        String[] reqPerms = permsMap.get(pkg);
                        if (reqPerms != null) {
                            for (String perm : reqPerms) {
                                if (perm == null) continue;
                                if (perm.contains("BIND_DEVICE_ADMIN")
                                        || perm.contains("SYSTEM_ALERT_WINDOW")
                                        || perm.contains("BIND_ACCESSIBILITY_SERVICE")
                                        || perm.contains("BIND_NOTIFICATION_LISTENER_SERVICE")) {
                                    hasDanger = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (hasDanger) risky.add(pkg);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.e(TAG, "detectFinalHighRiskApps 失败", e);
        }
        return risky;
    }

    // ===== 超级拦截覆盖层：执行 pm uninstall =====
    private void executeUninstallOverlay(final String pkg, final LinearLayout root,
                                          final WindowManager wm, final TextView countdownTv,
                                          final Button btnMain, final Button btnClose) {
        // ===== 终极保险：受保护应用（自己/桌面宠物/游龙工具/白名单）绝不允许卸载 =====
        if (isProtectedFinalPkg(pkg)) {
            Log.w(TAG, "受保护应用，禁止卸载: " + pkg);
            h.post(new Runnable() {
                @Override
                public void run() {
                    countdownTv.setText("⚠ 受保护应用，禁止卸载: " + pkg);
                    countdownTv.setTextColor(0xFFFF4444);
                    btnMain.setText("关闭");
                    btnMain.setEnabled(true);
                    btnClose.setVisibility(View.GONE);
                    btnMain.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            lastShakeRescueTime = System.currentTimeMillis();
                            try { wm.removeView(root); } catch (Exception ignored) {}
                        }
                    });
                }
            });
            return;
        }
        btnMain.setEnabled(false);
        btnClose.setEnabled(false);
        countdownTv.setTextColor(0xFFFFCC00);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] result = new String[1];
                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                    result[0] = StellarUtils.runCommand("pm uninstall " + pkg, 15000);
                } else {
                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "pm uninstall " + pkg}).waitFor();
                        result[0] = "OK(fallback)";
                    } catch (Exception e) {
                        result[0] = "ERROR:" + e.getMessage();
                    }
                }
                Log.w(TAG, "超级拦截 pm uninstall 结果: " + result[0]);

                h.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean stillInstalled = isAppInstalled(pkg);
                        if (!stillInstalled || result[0].contains("Success")) {
                            countdownTv.setText("✅ 已成功卸载");
                            countdownTv.setTextColor(0xFF4CAF50);
                        } else {
                            countdownTv.setText("⚠ 卸载可能失败，请手动处理");
                            countdownTv.setTextColor(0xFFFF4444);
                        }
                        btnMain.setText("关闭");
                        btnMain.setEnabled(true);
                        btnMain.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                lastShakeRescueTime = System.currentTimeMillis();
                                try { wm.removeView(root); } catch (Exception ignored) {}
                            }
                        });
                        btnClose.setVisibility(View.GONE);
                    }
                });
            }
        }).start();
    }

    // ===== 冻结询问阶段（卸载被取消后）：20秒倒计时自动执行 pm disable-user =====
    private void showFreezeAsk(final String pkg, final TextView reasonTv, final TextView countdownTv,
                               final Button btnMain, final Button btnClose,
                               final LinearLayout root, final WindowManager wm) {
        // ===== 保护检查：受保护应用（自己/桌面宠物/游龙工具/白名单）绝不进入冻结询问 =====
        if (isProtectedFinalPkg(pkg)) {
            Log.w(TAG, "受保护应用，跳过冻结询问: " + pkg);
            h.post(new Runnable() {
                @Override
                public void run() {
                    reasonTv.setText("⚠ 受保护应用，禁止冻结: " + pkg);
                    reasonTv.setTextColor(0xFFFF4444);
                    countdownTv.setText("此应用为受保护应用（自己/桌面宠物/游龙工具/白名单）");
                    countdownTv.setTextColor(0xFFFF4444);
                    btnMain.setText("关闭");
                    btnMain.setEnabled(true);
                    btnClose.setVisibility(View.GONE);
                    btnMain.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            lastShakeRescueTime = System.currentTimeMillis();
                            try { wm.removeView(root); } catch (Exception ignored) {}
                        }
                    });
                }
            });
            return;
        }
        // 更新 UI → 询问是否冻结
        reasonTv.setText("是否冻结此应用 " + pkg + " ？（冻结后不可用，可在设置中解冻）");
        reasonTv.setTextColor(0xFFFFCC00);
        countdownTv.setText("20 秒后将自动冻结");
        countdownTv.setTextColor(0xFFFFCC00);
        countdownTv.setTextSize(16);
        btnMain.setText("立即冻结");
        btnMain.setEnabled(true);
        btnClose.setText("不冻结，关闭");
        btnClose.setTextColor(0xFFFFFFFF);
        btnClose.setBackgroundColor(0xFF2E7D32);
        btnClose.setEnabled(true);
        btnClose.setVisibility(View.VISIBLE);

        // 20 秒倒计时 → 自动冻结
        final java.util.concurrent.atomic.AtomicInteger cd3 = new java.util.concurrent.atomic.AtomicInteger(20);
        final Runnable[] cd3Task = new Runnable[1];
        cd3Task[0] = new Runnable() {
            @Override
            public void run() {
                int sec = cd3.decrementAndGet();
                if (sec > 0) {
                    countdownTv.setText(sec + " 秒后将自动冻结");
                    if (sec <= 3) countdownTv.setTextColor(0xFFFF4444);
                    h.postDelayed(this, 1000);
                } else {
                    countdownTv.setText("正在冻结...");
                    executeDisableOverlay(pkg, countdownTv, btnMain, btnClose, root, wm);
                }
            }
        };
        h.postDelayed(cd3Task[0], 1000);

        // 主按钮 → 立即冻结
        btnMain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cd3Task[0] != null) h.removeCallbacks(cd3Task[0]);
                countdownTv.setText("正在冻结...");
                executeDisableOverlay(pkg, countdownTv, btnMain, btnClose, root, wm);
            }
        });

        // 关闭按钮 → 不冻结（重置冷却防止循环触发）
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cd3Task[0] != null) h.removeCallbacks(cd3Task[0]);
                lastShakeRescueTime = System.currentTimeMillis();
                try { wm.removeView(root); } catch (Exception ignored) {}
            }
        });
    }

    // ===== 执行 pm disable-user（冻结）=====
    private void executeDisableOverlay(final String pkg, final TextView countdownTv,
                                       final Button btnMain, final Button btnClose,
                                       final LinearLayout root, final WindowManager wm) {
        // ===== 终极保险：受保护应用（自己/桌面宠物/游龙工具/白名单）绝不允许冻结 =====
        if (isProtectedFinalPkg(pkg)) {
            Log.w(TAG, "受保护应用，禁止冻结: " + pkg);
            h.post(new Runnable() {
                @Override
                public void run() {
                    countdownTv.setText("⚠ 受保护应用，禁止冻结: " + pkg);
                    countdownTv.setTextColor(0xFFFF4444);
                    btnMain.setText("关闭");
                    btnMain.setEnabled(true);
                    btnClose.setVisibility(View.GONE);
                    btnMain.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            lastShakeRescueTime = System.currentTimeMillis();
                            try { wm.removeView(root); } catch (Exception ignored) {}
                        }
                    });
                }
            });
            return;
        }
        btnMain.setEnabled(false);
        btnClose.setEnabled(false);
        countdownTv.setTextColor(0xFFFFCC00);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] result = new String[1];
                if (StellarUtils.isStellarAvailable() && StellarUtils.hasStellarPermission()) {
                    result[0] = StellarUtils.runCommand("pm disable-user --user 0 " + pkg, 15000);
                } else {
                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "pm disable-user --user 0 " + pkg}).waitFor();
                        result[0] = "OK(fallback)";
                    } catch (Exception e) {
                        result[0] = "ERROR:" + e.getMessage();
                    }
                }
                Log.w(TAG, "冻结 pm disable-user 结果: " + result[0]);

                h.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean disabled = isAppDisabled(pkg);
                        if (disabled || result[0].contains("disabled") || result[0].contains("Success")) {
                            countdownTv.setText("✅ 已冻结 " + pkg);
                            countdownTv.setTextColor(0xFF4CAF50);
                        } else {
                            countdownTv.setText("⚠ 冻结可能失败，请手动处理");
                            countdownTv.setTextColor(0xFFFF4444);
                        }
                        btnMain.setText("关闭");
                        btnMain.setEnabled(true);
                        btnMain.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                lastShakeRescueTime = System.currentTimeMillis();
                                try { wm.removeView(root); } catch (Exception ignored) {}
                            }
                        });
                        btnClose.setVisibility(View.GONE);
                    }
                });
            }
        }).start();
    }

    // ===== 检查应用是否被禁用 =====
    private boolean isAppDisabled(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return !ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    // ===== 跳转应用设置（从覆盖层弹窗中提取的公共方法） =====
    private void jumpToAppSettings(final String pkgF, boolean pkgUnknown) {
        int bgFlags = Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | 0x00000004;

        if (pkgUnknown) {
            // ===== 未知应用：直接打开全部应用列表 =====
            try {
                Intent s = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                s.addFlags(bgFlags);
                startActivity(s);
                Log.i(TAG, "未知包名，打开全部应用列表");
            } catch (Exception e) {
                Log.e(TAG, "打开全部应用列表失败", e);
            }
        } else {
            // ===== 已知应用：打开该应用的设置详情页 =====
            boolean opened = false;
            try {
                Intent s = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                s.setData(Uri.parse("package:" + pkgF));
                s.addFlags(bgFlags);
                startActivity(s);
                Log.i(TAG, "已打开应用设置页: " + pkgF);
                opened = true;
            } catch (Exception e) {
                Log.e(TAG, "打开应用设置页失败: " + pkgF, e);
            }
            // 设置页失败 → 全部应用列表
            if (!opened) {
                try {
                    Intent s = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                    s.addFlags(bgFlags);
                    startActivity(s);
                    Log.i(TAG, "打开全部应用列表(备胎)");
                } catch (Exception e2) {
                    Log.e(TAG, "全部应用列表也失败", e2);
                }
            }

            // ===== 5秒后检查是否卸载，未卸载则调用原生卸载 =====
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 检查应用是否还在
                        getPackageManager().getPackageInfo(pkgF, 0);
                        // 还在 → 调用原生卸载
                        Log.w(TAG, "5秒后应用仍未卸载，调用原生卸载: " + pkgF);
                        Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                        uninstall.setData(Uri.parse("package:" + pkgF));
                        uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(uninstall);
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.i(TAG, "应用已卸载，无需原生卸载: " + pkgF);
                    } catch (Exception e) {
                        Log.e(TAG, "原生卸载失败: " + pkgF, e);
                        // 兜底：尝试 ACTION_DELETE
                        try {
                            Intent del = new Intent(Intent.ACTION_DELETE);
                            del.setData(Uri.parse("package:" + pkgF));
                            del.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(del);
                        } catch (Exception e2) {
                            Log.e(TAG, "ACTION_DELETE 也失败", e2);
                        }
                    }
                }
            }, 5000);
        }
    }

    // ===== 极强拦截兜底：无 Shizuku 时用 Runtime 执行 shell 并返回输出 =====
    // 注意：必须同时读 stderr + 设置超时，否则 am force-stop 无权限时产生大量 stderr
    // 输出会填满管道导致 waitFor 永久阻塞（卡死）
    private String execShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            final StringBuilder out = new StringBuilder();
            final StringBuilder err = new StringBuilder();
            Thread t1 = new Thread(() -> {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String l;
                    while ((l = br.readLine()) != null) out.append(l).append("\n");
                } catch (Exception ignored) {}
            });
            Thread t2 = new Thread(() -> {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                    String l;
                    while ((l = br.readLine()) != null) err.append(l).append("\n");
                } catch (Exception ignored) {}
            });
            t1.start();
            t2.start();
            boolean done;
            try {
                done = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                done = false;
            }
            if (!done) {
                p.destroyForcibly();
                try { t1.join(500); } catch (Exception ignored) {}
                try { t2.join(500); } catch (Exception ignored) {}
                return "ERROR:执行超时(30s): " + cmd;
            }
            try { t1.join(1000); } catch (Exception ignored) {}
            try { t2.join(1000); } catch (Exception ignored) {}
            String o = out.toString().trim();
            String e = err.toString().trim();
            if (!o.isEmpty()) return o;
            if (!e.isEmpty()) return "ERROR:" + e;
            return "OK";
        } catch (Exception e) {
            Log.e(TAG, "execShell 失败: " + cmd, e);
            return "ERROR:" + e.getMessage();
        }
    }

    // ===== 弹出警告窗 =====
    private void warnPopup(String pkg, int warnCount, String warnReason, boolean isVolumeRescue) {
        lastWarnPkg = pkg;
        lastWarnTime = System.currentTimeMillis();

        // 拦截时弹出 Toast 提示
        h.post(() -> Toast.makeText(ProtectService.this, "游龙护盾正在拦截中", Toast.LENGTH_SHORT).show());

        // 读取当前拦截模式
        SharedPreferences prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        String shieldMode = prefs.getString("shield_mode", "basic");
        final boolean isSuper = "super".equals(shieldMode);
        final boolean isExtreme = "extreme".equals(shieldMode);
        final boolean isFinal = "final".equals(shieldMode);
        final boolean isDaily = "daily".equals(shieldMode);
        final boolean isShizukuMode = isSuper || isExtreme || isFinal || isDaily;

        // 1. 直接用 WindowManager 覆盖层（悬浮在一切之上，不受后台 Activity 限制）
        //    超级拦截模式同样使用覆盖层，在覆盖层内完成 force-stop + uninstall 两阶段
        showWarnOverlay(pkg, warnReason, isVolumeRescue, shieldMode);

        // 记录拦截历史
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
        try {
            org.json.JSONArray arr = new org.json.JSONArray(
                    prefs.getString("block_history", "[]"));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("description", "拦截: " + pkg);
            o.put("packageName", pkg);
            o.put("time", time);
            o.put("type", warnReason.contains("黑名单") ? "blacklist_block" : "manual_block");
            arr.put(o);
            if (arr.length() > 50) {
                org.json.JSONArray n = new org.json.JSONArray();
                for (int i = arr.length() - 50; i < arr.length(); i++) n.put(arr.get(i));
                arr = n;
            }
            prefs.edit().putString("block_history", arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "记录拦截历史失败", e);
        }

        // 超级/极强拦截模式：覆盖层已处理全部逻辑，不再启动 ShieldWarnActivity
        if (isShizukuMode) {
            Log.i(TAG, "超级/极强拦截模式，覆盖层已处理，跳过ShieldWarnActivity");
            return;
        }

        try {
            Intent wi = new Intent(this, ShieldWarnActivity.class);
            wi.putExtra("suspect_package", pkg);
            wi.putExtra("reason", warnReason);
            wi.putExtra("warn_count", warnCount);
            wi.putExtra("is_volume_rescue", isVolumeRescue);
            // 传递当前拦截模式给 ShieldWarnActivity
            wi.putExtra("shield_mode", shieldMode);
            // FLAG_ACTIVITY_FROM_BACKGROUND (0x00000004): 标记来自后台的启动请求，部分 ROM 会放行
            wi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | 0x00000004);

            PendingIntent fullScreenPi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                fullScreenPi = PendingIntent.getActivity(this,
                        (int) System.currentTimeMillis() % 100000,
                        wi,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } else {
                fullScreenPi = PendingIntent.getActivity(this,
                        (int) System.currentTimeMillis() % 100000,
                        wi,
                        PendingIntent.FLAG_UPDATE_CURRENT);
            }

            // 通知点击 → 直接打开 ShieldWarnActivity（更可靠）
            PendingIntent openShieldPi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                openShieldPi = PendingIntent.getActivity(this,
                        (int) (System.currentTimeMillis() % 100000) + 1,
                        wi,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } else {
                openShieldPi = PendingIntent.getActivity(this,
                        (int) (System.currentTimeMillis() % 100000) + 1,
                        wi,
                        PendingIntent.FLAG_UPDATE_CURRENT);
            }

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            // 通知按钮：卸载软件 → NotifyUninstallReceiver
            Intent uninstallIntent = new Intent(this, NotifyUninstallReceiver.class);
            uninstallIntent.putExtra("target_pkg", pkg);
            PendingIntent uninstallPi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                uninstallPi = PendingIntent.getBroadcast(this,
                        (int) (System.currentTimeMillis() % 100000) + 1000,
                        uninstallIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } else {
                uninstallPi = PendingIntent.getBroadcast(this,
                        (int) (System.currentTimeMillis() % 100000) + 1000,
                        uninstallIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
            }

            final boolean isVolumeRescueLocal = isVolumeRescue;

            Notification nf;
            if (isVolumeRescueLocal) {
                // 逃生救援模式：持续通知（不超时、不自动取消），直到应用卸载
                nf = new NotificationCompat.Builder(this, WARN_CHANNEL_ID)
                        .setContentTitle("⚠️ 紧急救援 — 请尽快卸载")
                        .setContentText("点击「卸载软件」按钮 → 系统卸载 → 自动跳转设置页")
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setFullScreenIntent(fullScreenPi, true)
                        .setContentIntent(openShieldPi)
                        .addAction(android.R.drawable.ic_menu_delete, "卸载软件", uninstallPi)
                        .addAction(android.R.drawable.ic_menu_info_details, "打开拦截面板", openShieldPi)
                        .setAutoCancel(false)
                        .setOngoing(true)
                        .build();
            } else {
                nf = new NotificationCompat.Builder(this, WARN_CHANNEL_ID)
                        .setContentTitle("检测到需拦截应用")
                        .setContentText(pkg + " — " + warnReason)
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setFullScreenIntent(fullScreenPi, true)
                        .setContentIntent(openShieldPi)
                        .addAction(android.R.drawable.ic_menu_delete, "卸载软件", uninstallPi)
                        .addAction(android.R.drawable.ic_menu_info_details, "打开拦截面板", openShieldPi)
                        .setAutoCancel(true)
                        .setOngoing(false)
                        .setTimeoutAfter(15000)
                        .build();
            }

            final int notifId = 2000 + (int)(System.currentTimeMillis() % 1000);
            if (nm != null) {
                nm.notify(notifId, nf);
            }

            // 逃生救援模式：持续重发通知，直到应用卸载
            // 注意：护盾关闭（三个开关全关）时必须停止循环并取消通知，
            //       否则即使用户关闭护盾也会一直收到"请尽快卸载"提示
            if (isVolumeRescueLocal) {
                final NotificationManager nmFinal = nm;
                final int finalNotifId = notifId;
                rescueNotifId = finalNotifId;
                rescueNotifLoop = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // 护盾已关闭 → 停止循环并取消通知
                            SharedPreferences sp2 = getSharedPreferences("shield_prefs", MODE_PRIVATE);
                            boolean pOn2 = sp2.getBoolean("protect_on", false);
                            boolean sOn2 = sp2.getBoolean("shake_trigger_on", false);
                            boolean vOn2 = sp2.getBoolean("volume_trigger_on", false);
                            if (!pOn2 && !sOn2 && !vOn2) {
                                Log.i(TAG, "护盾已关闭，停止紧急救援通知循环: " + pkg);
                                if (nmFinal != null) nmFinal.cancel(finalNotifId);
                                return; // 不再 postDelayed 自己 → 循环终止
                            }
                            // 检查应用是否还在
                            getPackageManager().getPackageInfo(pkg, 0);
                            // 还在 → 重复发通知
                            if (nmFinal != null) {
                                Notification repeat = new NotificationCompat.Builder(
                                        ProtectService.this, WARN_CHANNEL_ID)
                                        .setContentTitle("⚠️ 紧急救援 — 请尽快卸载")
                                        .setContentText("应用 " + pkg + " 仍未卸载，点击下方按钮操作")
                                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                                        .setFullScreenIntent(fullScreenPi, true)
                                        .setContentIntent(openShieldPi)
                                        .addAction(android.R.drawable.ic_menu_delete,
                                                "卸载软件", uninstallPi)
                                        .addAction(android.R.drawable.ic_menu_info_details,
                                                "打开拦截面板", openShieldPi)
                                        .setAutoCancel(false)
                                        .setOngoing(true)
                                        .build();
                                nmFinal.notify(finalNotifId, repeat);
                            }
                            h.postDelayed(this, 5000);
                        } catch (PackageManager.NameNotFoundException e) {
                            // 应用已卸载 → 清除通知
                            if (nmFinal != null) {
                                nmFinal.cancel(finalNotifId);
                            }
                            Log.i(TAG, "救援完成，应用已卸载: " + pkg);
                        } catch (Exception e) {
                            Log.e(TAG, "救援通知循环异常", e);
                        }
                    }
                };
                h.postDelayed(rescueNotifLoop, 5000);
            }

            // 直接启动 ShieldWarnActivity（Android 10+ 可能被系统阻止，作为补充手段）
            try {
                startActivity(wi);
                Log.i(TAG, "直接启动ShieldWarnActivity成功: " + pkg);
            } catch (Exception e) {
                Log.w(TAG, "直接启动ShieldWarnActivity失败(预期内，后台限制): " + pkg, e);
            }

            // 延迟再试一次（防止被病毒抢占窗口）
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivity(wi);
                        Log.i(TAG, "延迟启动ShieldWarnActivity成功: " + pkg);
                    } catch (Exception e) {
                        Log.w(TAG, "延迟启动ShieldWarnActivity失败(预期内，后台限制): " + pkg, e);
                    }
                }
            }, 500);
        } catch (Exception e) {
            Log.e(TAG, "warnPopup ShieldWarnActivity 启动阶段异常", e);
        }
    }

    // ===== 判断 =====
    /** 当前是否为日常模式（日常模式不受前台界面/白名单/系统应用限制） */
    private boolean isDailyMode() {
        return "daily".equals(getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getString("shield_mode", "basic"));
    }

    private boolean isSys(String pkg) {
        if (pkg == null) return true;
        if (pkg.equals(getPackageName())) return true;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return true;
            if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true;
            if (ai.sourceDir != null && ai.sourceDir.startsWith("/system/")) return true;
        } catch (Exception ignored) { return true; }
        return false;
    }

    private boolean isWhitelisted(String pkg) {
        return pkg != null && whitelistCache.contains(pkg);
    }

    private boolean isAppInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 每 4 秒巡检里的**重活**（后台线程执行，绝不占用主线程）。
     *
     * <p>搬到这里的东西都满足一个特征：**要遍历全部已安装应用或读写磁盘**。
     * 主线程上做这些，用户一滑动就会周期性卡顿（真机实测确认过）。
     *
     * <p>用 {@link #tickHeavyRunning} 做单飞保护：上一轮没跑完就直接跳过本轮，
     * 避免任务堆积（病毒库比对在应用多的机器上可能超过 4 秒）。
     */
    private void runTickHeavyWorkAsync() {
        if (!tickHeavyRunning.compareAndSet(false, true)) return;
        tickHeavyExecutor.execute(() -> {
            final long t0 = System.currentTimeMillis();
            try {
                // 1) 白/黑名单缓存（各一次 SharedPreferences 读取）
                loadWhitelist();
                loadBlacklist();

                // 2) 黑名单巡检（遍历全部已安装应用）
                scanBlacklistedApps();

                // 3) 病毒库循环检测（遍历全部第三方应用 + 本地库匹配）
                if (anyTriggerOn()) {
                    try {
                        virusScanOnce();
                    } catch (Exception e) {
                        Log.e(TAG, "病毒库检测异常", e);
                    }
                }

                // 4) 权限降级巡检（读设置项，轻量但统一放这里，保持主线程干净）
                try {
                    monitorPermissionDegrade();
                } catch (Exception e) {
                    Log.e(TAG, "权限降级巡检异常", e);
                }

                // 5) 音量监听自愈（见方法注释：逃生通道不能静默失效）
                try {
                    ensureVolumeTriggerListener();
                } catch (Exception e) {
                    Log.e(TAG, "音量监听自愈失败", e);
                }

                // 6) native 哨兵标记清理
                try {
                    File gd = new File(getFilesDir(), "sentinel_guard_dead");
                    if (gd.exists() && isServiceRunning(ForegroundService.class)) {
                        gd.delete();
                    }
                } catch (Exception ignored) {}
            } catch (Throwable t) {
                Log.e(TAG, "巡检后台任务异常", t);
                CrashLogger.event("[巡检] 本轮后台任务异常：" + t);
            } finally {
                // 只记"异常慢"的轮次：正常一轮只要几十毫秒，
                // 超过 800ms 说明磁盘/应用列表遍历出问题了，值得留一条轨迹。
                long cost = System.currentTimeMillis() - t0;
                if (cost > 800) {
                    CrashLogger.event("[巡检] 本轮耗时偏长：" + cost + "ms");
                }
                tickHeavyRunning.set(false);
            }
        });
    }

    /** 巡检重活的单飞标志（防止任务堆积）。 */
    private final java.util.concurrent.atomic.AtomicBoolean tickHeavyRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 巡检重活的单线程执行器：串行执行，天然避免并发写缓存。 */
    private final java.util.concurrent.ExecutorService tickHeavyExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "protect-tick");
                t.setDaemon(true);
                return t;
            });

    /**
     * 弹"无障碍已丢失"的提示，但**做节流**：同一次安装/进程内最多每 10 分钟一次。
     *
     * <p>为什么必须节流：服务会被保活闹钟、哨兵互拉、开机自启反复拉起，
     * 每次拉起都会走到这里；不节流的话用户会连续看到一串 Toast，
     * 而且每次都盖在界面上 —— 观感就是"被提示打断、界面卡住"。
     *
     * @param hasPrivilege 是否已连上特权服务（决定提示内容）
     */
    private void showA11yLostToastOnce(boolean hasPrivilege) {
        long now = System.currentTimeMillis();
        if (now - lastA11yToastTime < A11Y_TOAST_INTERVAL_MS) return;
        lastA11yToastTime = now;
        try {
            Toast.makeText(ProtectService.this, "检测到无障碍权限已丢失", Toast.LENGTH_LONG).show();
            Toast.makeText(ProtectService.this,
                    hasPrivilege
                            ? "已连接特权服务，守护功能仍可正常使用，无需担心"
                            : "音量键三连击/摇一摇仍可正常使用，无需担心",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    /** 无障碍丢失提示的节流间隔（10 分钟）。 */
    private static final long A11Y_TOAST_INTERVAL_MS = 10 * 60 * 1000L;
    /** 上次弹该提示的时间。 */
    private volatile long lastA11yToastTime = 0L;

    // ===== 通知 =====
    /**
     * 最小可用通知：只用于在 {@code onCreate} 最开始就抢先满足
     * "startForegroundService 后 5 秒内必须 startForeground" 的系统硬性要求。
     *
     * <p>它刻意不做任何取数（不读偏好、不数拦截次数、不查模式），
     * 因为这些都可能失败或耗时；一旦这里抛异常，5 秒超时崩溃就躲不掉了。
     * 真正的守护通知由 {@link #buildNotify(String)} 在后续流程里覆盖上去。
     */
    private Notification buildMinimalNotify() {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("游龙安全护盾")
                .setContentText("正在启动守护…")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0x5A2, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setContentIntent(pi);
        } catch (Throwable ignored) {
        }
        return b.build();
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "游龙安全护盾", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("游龙安全护盾正在后台守护中");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);

            NotificationChannel warnCh = new NotificationChannel(
                    WARN_CHANNEL_ID, "安全警告", NotificationManager.IMPORTANCE_HIGH);
            warnCh.setDescription("黑名单应用拦截弹窗");
            warnCh.setBypassDnd(true);
            nm.createNotificationChannel(warnCh);
        }
    }

    // ===== 通知：标题固定，正文显示"已开启多久 + 当前模式" =====
    // extra 可选附加一行（如病毒库状态），展开后可见完整信息
    private Notification buildNotify(String extra) {
        long elapsed = startTime > 0 ? System.currentTimeMillis() - startTime : 0L;
        String duration = formatDuration(elapsed);
        String modeName = currentModeName();
        int blocked = getBlockCount();

        String content = "已开启 " + duration + " · " + modeName;
        StringBuilder big = new StringBuilder();
        big.append("已开启守护：").append(duration)
                .append("\n拦截模式：").append(modeName)
                .append("\n拦截次数：").append(blocked).append(" 次");
        if (extra != null && !extra.isEmpty()) big.append("\n").append(extra);

        PendingIntent pi = null;
        try {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pi = PendingIntent.getActivity(this, 0x5A2, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception ignored) {}

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("游龙安全护盾 · 守护中")
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(big.toString()))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setOnlyAlertOnce(true)   // 每分钟刷新不重复响铃/弹横幅
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (pi != null) b.setContentIntent(pi);
        return b.build();
    }

    /** 守护时长格式化：X天X小时X分钟 / X小时X分钟 / X分钟 */
    private String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long totalMin = ms / 60000L;
        if (totalMin <= 0) return "不到 1 分钟";
        long days = totalMin / 1440L;
        long hours = (totalMin % 1440L) / 60L;
        long mins = totalMin % 60L;
        if (days > 0) return days + "天" + hours + "小时" + mins + "分钟";
        if (hours > 0) return hours + "小时" + mins + "分钟";
        return mins + "分钟";
    }

    /** 当前拦截模式中文名（通知栏展示用） */
    private String currentModeName() {
        String m = getSharedPreferences("shield_prefs", MODE_PRIVATE)
                .getString("shield_mode", "basic");
        if ("super".equals(m)) return "超级拦截";
        if ("extreme".equals(m)) return "极强拦截";
        if ("final".equals(m)) return "终结模式";
        if ("daily".equals(m)) return "日常模式";
        return "基础拦截";
    }

    /** 已有拦截记录条数（通知栏展示用） */
    private int getBlockCount() {
        try {
            String raw = getSharedPreferences("shield_prefs", MODE_PRIVATE)
                    .getString("block_history", "[]");
            return new org.json.JSONArray(raw).length();
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateNotify(String txt) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFY_ID, buildNotify(txt));
    }

    /** 任一触发开关开启？ */
    private boolean anyTriggerOn() {
        SharedPreferences sp = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        return sp.getBoolean("protect_on", false)
                || sp.getBoolean("shake_trigger_on", false)
                || sp.getBoolean("volume_trigger_on", false);
    }

    /** 服务是否在运行（跨进程，同 uid 可见） */
    private boolean isServiceRunning(Class<?> cls) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            for (ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
                if (cls.getName().equals(s.service.getClassName())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // ===== 兜底检查：若所有开关全部关闭，立即停止自身并移除通知 =====
        // 防止任何路径（保活闹钟/START_STICKY/开机自启/自动重启）在用户关闭护盾后
        // 又把服务拉起来并重新发出"正在守护"通知。
        SharedPreferences sp = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        boolean pOn = sp.getBoolean("protect_on", false);
        boolean sOn = sp.getBoolean("shake_trigger_on", false);
        boolean vOn = sp.getBoolean("volume_trigger_on", false);
        if (!pOn && !sOn && !vOn) {
            Log.i(TAG, "护盾已关闭（所有开关全关），兜底停止服务并移除通知");
            try { stopForeground(true); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }

        // 摇动触发弹窗
        if (intent != null && "com.youlong.hd.SHAKE_TRIGGER".equals(intent.getAction())) {
            if (sp.getBoolean("shake_trigger_on", false)) {
                h.postDelayed(() -> {
                    String fg = AdSkipService.getForegroundPkg();
                    if (fg == null) fg = getFgViaUsageStats();
                    if (fg == null) fg = getFgViaStellar();
                    if (fg == null) fg = getFgSimple();

                    // ===== 日常模式：不受任何前台界面限制 =====
                    // 系统界面 / 白名单应用 / 无法识别包名 → 一律照常弹出救援窗
                    if (isDailyMode()) {
                        String safePkg = (fg == null || fg.isEmpty())
                                ? "(无法识别的界面)" : fg;
                        Log.i(TAG, "摇动触发（日常模式）：不受前台/白名单限制，前台=" + safePkg);
                        warnPopup(safePkg, 1, "检测到猛烈摇动", true);
                        return;
                    }

                    if (fg != null && !fg.equals(getPackageName())) {
                        // 白名单应用界面摇动 → 同样弹出救援窗（主动触发必须看得见界面），
                        // 弹窗中的应用列表/卸载逻辑仍会排除白名单应用，不会动它
                        boolean wl = isWhitelisted(fg);
                        Log.i(TAG, "摇动触发：前台=" + fg
                                + (wl ? "（白名单应用，仍弹窗）" : ""));
                        warnPopup(fg, 1,
                                wl ? "检测到猛烈摇动（白名单应用）" : "检测到猛烈摇动", true);
                    }
                }, 200);
            }
        }
        return START_STICKY;
    }

    /**
     * 用户从"最近任务"划掉本应用 —— Android 只在任务真的被划掉时回调这里，
     * 是防终结自启唯一可靠的信号（也不能靠 getAppTasks()：开启"隐藏后台"后
     * 任务不在最近列表，会被误判为被划掉，导致用户用别的软件时界面被反复拉回）。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.w(TAG, "检测到应用被从最近任务划掉");
        try {
            MainActivity.restartAfterTaskRemoved(this);
        } catch (Exception e) {
            Log.e(TAG, "防终结自启失败", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 释放 WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        // 取消保活闹钟
        cancelKeepAliveAlarm();
        // 取消"防终结"自启闹钟：护盾已停，防止 800ms 后主界面被拉回前台
        try { MainActivity.cancelRestartAlarm(this); } catch (Exception ignored) {}
        if (notifyUpdater != null) h.removeCallbacks(notifyUpdater);
        if (tick != null) h.removeCallbacks(tick);
        if (volPollRunnable != null) h.removeCallbacks(volPollRunnable);
        // 病毒库：移除弹窗并清理状态
        virusUninstallRunning = false;
        virusHandledPkgs.clear();
        removeVirusOverlayIfAny();
        // 停止紧急救援通知循环 + 取消"请尽快卸载"通知
        if (rescueNotifLoop != null) h.removeCallbacks(rescueNotifLoop);
        if (rescueNotifId >= 0) {
            try {
                NotificationManager nmD = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nmD != null) nmD.cancel(rescueNotifId);
            } catch (Exception ignored) {}
        }
        try { unregisterReceiver(volRcvr); } catch (Exception ignored) {}
        try { unregisterReceiver(pkgRcvr); } catch (Exception ignored) {}
        AdSkipService.setVolumeChangeListener(null);
        // 关键：同时清除前台切换监听 —— 否则无障碍仍持有已停止的 ProtectService
        // 僵尸引用，关闭护盾后切到黑名单应用仍会 warnPopup 并拉起界面
        AdSkipService.setForegroundChangeListener(null);
        // 注销摇动传感器
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        // 移除 Shizuku 断连监听
        try { Stellar.INSTANCE.removeBinderDeadListener(shizukuDeadListener); } catch (Exception ignored) {}
        stopForeground(true);

        // ===== 若所有开关全关（用户主动关闭护盾）→ 哨兵进程一并退场 =====
        SharedPreferences spE = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        boolean pE = spE.getBoolean("protect_on", false);
        boolean sE = spE.getBoolean("shake_trigger_on", false);
        boolean vE = spE.getBoolean("volume_trigger_on", false);
        if (!pE && !sE && !vE) {
            try {
                stopService(new Intent(this, ForegroundService.class));
            } catch (Exception ignored) {}
        }

        // ===== 自动重启：被 adb force-stop / 系统回收 / 后台被杀 后快速自愈 =====
        // 前提：仍有任意一个开关开启（用户主动关闭护盾时全关，不重启）
        SharedPreferences spD = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        boolean pD = spD.getBoolean("protect_on", false);
        boolean sD = spD.getBoolean("shake_trigger_on", false);
        boolean vD = spD.getBoolean("volume_trigger_on", false);
        if (pD || sD || vD) {
            Log.w(TAG, "服务被终结/回收，2秒后自动重启（防终结自愈）");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    ContextCompat.startForegroundService(ProtectService.this,
                            new Intent(ProtectService.this, ProtectService.class));
                } catch (Exception ignored) {}
            }, 2000);
        }
    }

    // ===== 摇动检测传感器 =====
    private void setupShakeSensor() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (sensorManager != null) {
            Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel != null) {
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
                Log.i(TAG, "摇动传感器已注册 (SENSOR_DELAY_GAME)");
            } else {
                Log.w(TAG, "设备无加速度传感器");
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        SharedPreferences spShake = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        if (!spShake.getBoolean("shake_trigger_on", false)) return;

        // 力度档位实时生效：用户改档位后下个采样点就按新参数判定
        int shakeLevel = readShakeLevel(spShake);
        float shakeThreshold = SHAKE_THRESHOLDS[shakeLevel - 1];
        int shakeHitsNeeded = SHAKE_HITS_TABLE[shakeLevel - 1];

        float x = event.values[0], y = event.values[1], z = event.values[2];
        float dx = Math.abs(x - lastAccelX);
        float dy = Math.abs(y - lastAccelY);
        float dz = Math.abs(z - lastAccelZ);
        lastAccelX = x; lastAccelY = y; lastAccelZ = z;

        float mag = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (mag < shakeThreshold) return;

        long now = System.currentTimeMillis();
        if (now - lastShakeTs < SHAKE_WINDOW) {
            shakeHitCount++;
        } else {
            shakeHitCount = 1;
        }
        lastShakeTs = now;

        if (shakeHitCount >= shakeHitsNeeded) {
            shakeHitCount = 0;
            // 冷却期内忽略重复摇动，防止弹窗关闭后被残留在传感器队列中的数据再次触发
            if (now - lastShakeRescueTime < SHAKE_RESCUE_COOLDOWN_MS) {
                Log.v(TAG, "摇动触发冷却中，跳过 (" + (now - lastShakeRescueTime) + "ms)");
                return;
            }
            lastShakeRescueTime = now;
            Log.w(TAG, "检测到猛烈摇动！触发逃生弹窗（力度档位 " + shakeLevel
                    + "，阈值 " + shakeThreshold + "，需 " + shakeHitsNeeded + " 次尖峰）");
            // 震动反馈
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(100);
                }
            }
            // 直接复用音量键的逃生弹窗逻辑（完整的前台检测+白名单+弹窗）
            h.post(this::triggerVolumeRescue);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ===== 保活：AlarmManager 定时唤醒 =====
    private void setupKeepAliveAlarm() {
        Intent keepIntent = new Intent(this, KeepAliveReceiver.class);
        PendingIntent keepPi = PendingIntent.getBroadcast(this, 0, keepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            try {
                am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 120_000, 120_000, keepPi);
                Log.i(TAG, "保活闹钟已设置（每2分钟）");
            } catch (Exception e) {
                Log.e(TAG, "设置保活闹钟失败", e);
            }
        }
    }

    private void cancelKeepAliveAlarm() {
        Intent keepIntent = new Intent(this, KeepAliveReceiver.class);
        PendingIntent keepPi = PendingIntent.getBroadcast(this, 0, keepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) am.cancel(keepPi);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
