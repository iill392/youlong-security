package com.youlong.hd;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 病毒内容特征库（virus-db.json / virus-db-rules.json）—— 2026-10-24 新增
 *
 * ==========================================================================
 * 与 {@link VirusDb}（包名/名称列表）的关系
 * --------------------------------------------------------------------------
 * 老的 safe1/safe2/anquan1/anquan2 + mian1/mian2/mingcheng1/mingcheng2 仍是
 * 第一道「包名 / 应用名称」快查（{@link VirusDb} 不动）；本类是 yl-tool-data
 * 新增的多信号特征库，用来抓「换包名 + 重签名」的克隆体：
 *
 *   强信号（单独命中即可 BLOCK，allowlist 除外）
 *     · 签名证书 SHA-256（克隆体共用同一 p12 证书）
 *     · APK 整包 SHA-256
 *     · DEX 解压后内容 SHA-256（重签名不变）
 *     · 关键资产 SHA-256（assets/*.p12、arsclib.properties）
 *   高特异字符串 token（≥ high_token_block_min 个才 BLOCK，1 个只 WARN）
 *   弱信号（结构指纹 / 克隆包名形态 / 通用 token）—— 只累计，命中 ≥2 项才 WARN
 *
 * 数据来源（README §2，域名不写死，运行时从 {@link VirusDb#getServerHost()} 取）：
 *   https://&lt;serverHost&gt;/virus-db.json
 *   https://&lt;serverHost&gt;/virus-db-rules.json
 *
 * 拉取策略（README §2/§6）：
 *   带 If-None-Match: &lt;etag&gt;，304 → 不更新；
 *   version 相同且两个 etag 都未变 → 跳过解析；
 *   替换前把旧 raw 存到 *_prev，解析失败自动回滚旧版（新数据不生效）。
 *   两个 raw JSON / etag / version / updated_at / lastFetch 都放在
 *   SharedPreferences（virus_content_db），断网用缓存继续检测。
 *
 * 服务端对接：{@link #snapshotJson(Context)} 生成
 * 「youlong/virus-db/snapshot/1」快照，{@link #pushSnapshot(Context)} 通过内置
 * 特权写入 /data/local/tmp/stellar_guard/virus_db.json（0600），供
 * embedded/server 的 VirusSnapshot.kt 在 Shizuku 会话入口做证书/脚本拦截。
 *
 * 约束：本类所有公开方法都必须「吞异常 + 记日志」，绝不允许把扫描/守护打崩；
 * 解析只用 Android 自带 org.json，不引入任何新依赖。
 * ==========================================================================
 */
public final class VirusContentDb {

    private static final String TAG = "VirusContentDb";

    // ======================================================================
    // 常量
    // ======================================================================

    /** SharedPreferences 名（两个 raw JSON + etag + 版本信息） */
    private static final String PREF = "virus_content_db";

    private static final String K_RAW_DB = "raw_db";
    private static final String K_RAW_RULES = "raw_rules";
    /** 上一版备份（解析失败时回滚用） */
    private static final String K_RAW_DB_PREV = "raw_db_prev";
    private static final String K_RAW_RULES_PREV = "raw_rules_prev";
    private static final String K_ETAG_DB = "etag_db";
    private static final String K_ETAG_RULES = "etag_rules";
    private static final String K_ETAG_DB_PREV = "etag_db_prev";
    private static final String K_ETAG_RULES_PREV = "etag_rules_prev";
    private static final String K_VERSION = "version";
    private static final String K_UPDATED_AT = "updated_at";
    private static final String K_LAST_FETCH = "last_fetch";
    /** 2026-10-24 新增：最近一次「同步成功」的时间（供内置特权页面展示） */
    private static final String K_LAST_SUCCESS = "last_success";
    private static final String K_ERROR = "last_error";

    /** 服务器上的两个特征文件（文件名公开，域名走 VirusDb.getServerHost()） */
    private static final String FILE_DB = "virus-db.json";
    private static final String FILE_RULES = "virus-db-rules.json";

    /** 快照 schema（服务端 VirusSnapshot.kt 按它解析，字段名不可改） */
    public static final String SNAPSHOT_SCHEMA = "youlong/virus-db/snapshot/1";
    /** 快照落地路径：内置特权目录，由服务端经 Binder 原子写入（0644） */
    public static final String SNAPSHOT_PATH = "/data/local/tmp/stellar_guard/virus_db.json";

    /** 判定等级（服务端/扫描页共用） */
    public static final int CLEAN = 0;
    public static final int WARN = 1;
    public static final int BLOCK = 2;

    /** 拉取节流：两次网络拉取至少间隔 30 秒（与 VirusDb 保持一致） */
    private static final long MIN_FETCH_INTERVAL_MS = 30000L;

    /**
     * 2026-10-24 新增：应用运行期间的定时拉取周期。
     *
     * <p>用户要求：只要应用在运行，就每 3 分钟拉取一次病毒特征库。
     * 由 {@link #startPeriodicRefresh(Context)} 在 Application 主进程里启动；
     * 服务端返回 ETag 未变时只有 304，流量很小。
     */
    private static final long PERIODIC_INTERVAL_MS = 3L * 60L * 1000L;

    /** 首次定时拉取的延迟：启动阶段已由 VirusDb.refreshNow 顺带拉过一次，3 分钟后再轮询。 */
    private static final long PERIODIC_INITIAL_DELAY_MS = PERIODIC_INTERVAL_MS;

    /** 整包哈希上限：超过 150MB 跳过该步（避免手动扫描被大游戏卡死） */
    private static final long APK_HASH_MAX_BYTES = 150L * 1024 * 1024;

    /** 高特异 token 扫描总预算：dex + asset 条目字节合计 64MB */
    private static final long TOKEN_SCAN_MAX_BYTES = 64L * 1024 * 1024;

    /** dex 条目：classes.dex / classes2.dex / ... */
    private static final Pattern DEX_ENTRY = Pattern.compile("classes\\d*\\.dex");

    /** 参与 token 扫描的条目后缀（与 yl-tool-data 的 matcher.py 保持一致） */
    private static final String[] TOKEN_SCAN_SUFFIX = {".dex", ".arsc", ".xml", ".properties"};

    /** 进程内判定缓存上限（避免手动扫描重复读 APK） */
    private static final int VERDICT_CACHE_MAX = 512;

    /** 单次 inspectApk 内部读取缓冲区 */
    private static final int IO_BUFFER = 64 * 1024;

    // ======================================================================
    // 判定结果
    // ======================================================================

    /**
     * 单个 APK 的内容判定结果。
     *
     * <p>level 取 {@link #CLEAN} / {@link #WARN} / {@link #BLOCK}；
     * family 是命中的家族名（如 TerminalDeityUltra）；
     * reason 是给用户看的中文原因（「哪个家族 + 哪个信号」）。
     */
    public static final class Verdict {
        public final int level;
        public final String family;
        public final String reason;
        /**
         * 2026-10-24 新增：本次判定是否因时间/字节预算被截断。
         * true 表示结果不完整（未命中也只能算 CLEAN，不缓存）。
         */
        public final boolean truncated;

        Verdict(int level, String family, String reason, boolean truncated) {
            this.level = level;
            this.family = family == null ? "" : family;
            this.reason = reason == null ? "" : reason;
            this.truncated = truncated;
        }
    }

    // ======================================================================
    // 内部数据模型（全部按 virus-db.json 的真实字段解析，不臆造）
    // ======================================================================

    /** 证书特征：certs[]{sha256, action, family(父级补), display_name(父级补)} */
    private static final class CertEntry {
        final String sha256;
        final String action;
        final String family;
        final String displayName;

        CertEntry(String sha256, String action, String family, String displayName) {
            this.sha256 = sha256;
            this.action = action;
            this.family = family;
            this.displayName = displayName;
        }
    }

    /** 包名特征（弱信号）：packages[]{package_name, action, family} */
    private static final class PkgEntry {
        final String pkg;
        final String action;
        final String family;
        final String displayName;

        PkgEntry(String pkg, String action, String family, String displayName) {
            this.pkg = pkg;
            this.action = action;
            this.family = family;
            this.displayName = displayName;
        }
    }

    /** 整包哈希特征：apk_samples[]{sha256, file_size, action, family} */
    private static final class ApkEntry {
        final String sha256;
        final String action;
        final String family;
        final String displayName;
        /** 样本 APK 的字节数（-1 = 库未提供）；用于整包哈希前的廉价过滤。 */
        final long fileSize;

        ApkEntry(String sha256, String action, String family, String displayName, long fileSize) {
            this.sha256 = sha256;
            this.action = action;
            this.family = family;
            this.displayName = displayName;
            this.fileSize = fileSize;
        }
    }

    /** DEX 内容哈希特征：content.dex_sha256{文件名: sha256}（文件名 + 哈希同时命中） */
    private static final class DexEntry {
        final String name;
        final String sha256;
        final String action;
        final String family;
        final String displayName;

        DexEntry(String name, String sha256, String action, String family, String displayName) {
            this.name = name;
            this.sha256 = sha256;
            this.action = action;
            this.family = family;
            this.displayName = displayName;
        }
    }

    /** 关键资产哈希特征：content.asset_sha256{路径: sha256} */
    private static final class AssetEntry {
        final String path;
        final String sha256;
        final String action;
        final String family;
        final String displayName;

        AssetEntry(String path, String sha256, String action, String family, String displayName) {
            this.path = path;
            this.sha256 = sha256;
            this.action = action;
            this.family = family;
            this.displayName = displayName;
        }
    }

    /**
     * 结构指纹：content.structure{dex_count, frameworks_android_count,
     * has_arsclib, has_p12_asset}。
     *
     * <p>字段缺省用 -1 表示「JSON 未给出」，未给出就不参与比较；
     * 全部字段一致才算一项弱信号（与 yl-tool-data/matcher.py 的
     * {@code all(structure.get(k) == v)} 语义一致）。
     */
    private static final class StructSig {
        int dexCount = -1;
        int fwCount = -1;
        /** -1 未给出 / 0 false / 1 true */
        int hasArsclib = -1;
        int hasP12 = -1;
        String action = "warn";
        String family = "";
        String displayName = "";

        boolean matches(int dc, int fc, boolean arsclib, boolean p12) {
            if (dexCount >= 0 && dc != dexCount) return false;
            if (fwCount >= 0 && fc != fwCount) return false;
            if (hasArsclib >= 0 && (arsclib ? 1 : 0) != hasArsclib) return false;
            if (hasP12 >= 0 && (p12 ? 1 : 0) != hasP12) return false;
            return true;
        }
    }

    /** 克隆包名形态：content.package_pattern{segments, lengths[], charset} */
    private static final class PkgPattern {
        int segments = -1;
        int[] lengths;
        String charset = "";
        String action = "warn";
        String family = "";
        String displayName = "";

        /** 包名分段数 / 各段长度 / 字符集是否与特征一致（弱信号） */
        boolean matches(String pkg) {
            if (pkg == null || pkg.isEmpty()) return false;
            String[] parts = pkg.split("\\.");
            if (segments > 0 && parts.length != segments) return false;
            if (lengths != null && lengths.length > 0) {
                if (parts.length != lengths.length) return false;
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].length() != lengths[i]) return false;
                }
            }
            if (charset != null && !charset.isEmpty()) {
                if ("a-z".equalsIgnoreCase(charset)) {
                    // 克隆包名：全小写字母（matcher.py 的 shape.all_lower）
                    for (String p : parts) {
                        if (p.isEmpty()) return false;
                        for (int i = 0; i < p.length(); i++) {
                            char c = p.charAt(i);
                            if (c < 'a' || c > 'z') return false;
                        }
                    }
                } else {
                    for (String p : parts) {
                        for (int i = 0; i < p.length(); i++) {
                            if (charset.indexOf(p.charAt(i)) < 0) return false;
                        }
                    }
                }
            }
            return true;
        }
    }

    /** 字符串 token：content.string_tokens{high[], low[]} */
    private static final class TokenEntry {
        final String token;
        final boolean ascii;
        final String action;
        final String family;
        final String displayName;

        TokenEntry(String token, String action, String family, String displayName) {
            this.token = token;
            this.ascii = isAscii(token);
            this.action = action;
            this.family = family;
            this.displayName = displayName;
        }

        private static boolean isAscii(String s) {
            if (s == null) return false;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) > 0x7F) return false;
            }
            return true;
        }
    }

    /**
     * 行为规则（给内置特权的 stdin 脚本审计用）：
     * families[].behavior_rules[] 与 virus-db-rules.json 的 rules[] 合并，按 id 去重。
     */
    private static final class Rule {
        String id = "";
        String family = "";
        String displayName = "";
        String kind = "token";
        String pattern = "";
        int weight = 0;
        String action = "warn";
        String description = "";
    }

    /** IOC（取证/展示用，不参与判定） */
    private static final class Ioc {
        final String type;
        final String value;
        final String description;
        final String family;

        Ioc(String type, String value, String description, String family) {
            this.type = type;
            this.value = value;
            this.description = description;
            this.family = family;
        }
    }

    /** 解析后的整库索引（只读快照，整体替换，读取线程天然拿到一致视图） */
    private static final class Db {
        String schema = "";
        String version = "";
        String updatedAt = "";

        int highTokenBlockMin = 2;
        String weakOnlyAction = "warn";
        String fuzzyAction = "warn";
        final List<String> strongSignals = new ArrayList<>();

        final Map<String, CertEntry> certs = new LinkedHashMap<>();
        final Map<String, PkgEntry> packages = new LinkedHashMap<>();
        final Map<String, ApkEntry> apkSamples = new LinkedHashMap<>();
        final List<DexEntry> dexSamples = new ArrayList<>();
        final List<AssetEntry> assetSamples = new ArrayList<>();
        final List<StructSig> structures = new ArrayList<>();
        final List<PkgPattern> patterns = new ArrayList<>();
        final List<TokenEntry> highTokens = new ArrayList<>();
        final List<TokenEntry> lowTokens = new ArrayList<>();
        final Set<String> allowlistCerts = new HashSet<>();
        final Map<String, Rule> rules = new LinkedHashMap<>();
        final List<Ioc> iocs = new ArrayList<>();
        /** families[].severity（如 high/medium），保留供展示/审计用 */
        final Map<String, String> familySeverity = new LinkedHashMap<>();

        boolean isEmpty() {
            return certs.isEmpty() && packages.isEmpty() && apkSamples.isEmpty()
                    && dexSamples.isEmpty() && assetSamples.isEmpty()
                    && highTokens.isEmpty() && lowTokens.isEmpty()
                    && structures.isEmpty() && patterns.isEmpty() && rules.isEmpty();
        }

        int tokenCount() {
            return highTokens.size() + lowTokens.size();
        }

        /** dex 特征要求「文件名 + 内容哈希」同时命中（与 matcher.py 一致） */
        DexEntry matchDex(String name, String sha256) {
            if (name == null || sha256 == null) return null;
            for (int i = 0; i < dexSamples.size(); i++) {
                DexEntry de = dexSamples.get(i);
                if (de.name.equals(name) && de.sha256.equalsIgnoreCase(sha256)) return de;
            }
            return null;
        }
    }

    // ======================================================================
    // 运行状态
    // ======================================================================

    private static final Object LOAD_LOCK = new Object();
    private static final Object FETCH_LOCK = new Object();
    private static final Map<String, Verdict> VERDICT_CACHE = new ConcurrentHashMap<>();

    /** 2026-10-24：判定结果落盘（跨进程重启复用；键里含特征库版本）。 */
    private static final String PREF_VERDICT = "virus_verdict_cache";
    private static final String K_VERDICT_PREFIX = "v_";
    /** 最近一次 inspectApk 的 Context（remember 落盘用，避免改所有调用点）。 */
    private static volatile Context verdictCtx = null;
    /** 落盘判定条数（-1 = 尚未初始化）；超过上限时整表清一次，避免无限增长。 */
    private static final AtomicInteger PERSISTED_COUNT = new AtomicInteger(-1);
    /** 判定落盘的 SharedPreferences 缓存（避免每个应用都 getSharedPreferences）。 */
    private static volatile SharedPreferences verdictPrefs = null;

    /** 内存特征库（null = 尚未解析出任何数据） */
    private static volatile Db cache = null;
    private static volatile boolean loadedFromDisk = false;
    private static volatile boolean refreshing = false;
    private static volatile long lastFetch = 0L;
    /** 2026-10-24 新增：最近一次同步成功时间（0 = 从未成功） */
    private static volatile long lastSuccess = 0L;
    private static volatile String lastError = "";
    /** 已成功推送到内置特权的版本号（避免重复 IPC） */
    private static volatile String pushedVersion = "";
    /** 已成功推送的快照内容签名（包名列表变化也会重推，不只看 version） */
    private static volatile String pushedSignature = "";

    /**
     * 2026-10-25：宿主扫描命中的应用包名（包名/名称/证书/内容/整包任意信号）。
     * 这些包名会随快照推给内置特权，服务端按包名把该应用的全部命令拦掉。
     */
    private static final Set<String> MATCHED_PKGS = ConcurrentHashMap.newKeySet();
    private static final String PREF_MATCHED = "virus_matched_pkgs";
    private static final String K_MATCHED = "matched";
    private static volatile boolean matchedLoaded = false;

    // ---- 2026-10-24 新增：应用运行期间的 3 分钟定时拉取 ----
    private static final AtomicBoolean POLLER_STARTED = new AtomicBoolean(false);
    private static volatile ScheduledExecutorService poller = null;
    /** 最近一次定时轮询时间（毫秒） */
    private static volatile long lastPollAt = 0L;
    /** 已执行的定时轮询次数（含 304/失败，便于排障） */
    private static volatile int pollCount = 0;

    private VirusContentDb() {}

    // ======================================================================
    // 对外 API（服务端对接按这些名字调用，不要改名）
    // ======================================================================

    /** 从 SharedPreferences 加载本地缓存（断网也能继续检测；解析失败自动回滚上一版） */
    public static void loadFromCache(Context ctx) {
        if (ctx == null) return;
        synchronized (LOAD_LOCK) {
            try {
                SharedPreferences sp = prefs(ctx);
                if (sp == null) {
                    loadedFromDisk = true;
                    return;
                }
                String rawDb = sp.getString(K_RAW_DB, "");
                String rawRules = sp.getString(K_RAW_RULES, "");
                lastFetch = sp.getLong(K_LAST_FETCH, 0L);
                lastSuccess = sp.getLong(K_LAST_SUCCESS, 0L);
                lastError = oString(sp.getString(K_ERROR, ""));

                Db d = parse(rawDb, rawRules);
                if (d == null && rawDb != null && !rawDb.trim().isEmpty()) {
                    // 当前版本损坏 → 回滚到 *_prev 备份（README §2/§6）
                    String prevDb = sp.getString(K_RAW_DB_PREV, "");
                    String prevRules = sp.getString(K_RAW_RULES_PREV, "");
                    Db prev = parse(prevDb, prevRules);
                    if (prev != null) {
                        d = prev;
                        sp.edit()
                                .putString(K_RAW_DB, prevDb == null ? "" : prevDb)
                                .putString(K_RAW_RULES, prevRules == null ? "" : prevRules)
                                .putString(K_ETAG_DB, oString(sp.getString(K_ETAG_DB_PREV, "")))
                                .putString(K_ETAG_RULES, oString(sp.getString(K_ETAG_RULES_PREV, "")))
                                .putString(K_VERSION, prev.version)
                                .putString(K_UPDATED_AT, prev.updatedAt)
                                .apply();
                        Log.w(TAG, "当前特征库解析失败，已回滚上一版: " + prev.version);
                    }
                }
                cache = d;
                loadedFromDisk = true;
                if (d != null) {
                    Log.i(TAG, "特征库缓存已加载: version=" + d.version
                            + " certs=" + d.certs.size()
                            + " packages=" + d.packages.size()
                            + " apk=" + d.apkSamples.size()
                            + " dex=" + d.dexSamples.size()
                            + " asset=" + d.assetSamples.size()
                            + " token=" + d.tokenCount()
                            + " rules=" + d.rules.size());
                } else {
                    Log.i(TAG, "特征库缓存为空（尚未同步）");
                }
            } catch (Throwable tr) {
                Log.e(TAG, "读取特征库缓存失败", tr);
                loadedFromDisk = true;
            }
        }
    }

    /**
     * 立即拉取并解析两个特征文件（阻塞，必须在后台线程调用）。
     *
     * <p>失败/304 都不会清空本地数据；新数据解析失败会回滚旧版。
     * 成功后顺带把快照推给内置特权（{@link #pushSnapshot(Context)}）。
     */
    public static void refreshNow(Context ctx) {
        if (ctx == null) return;
        synchronized (FETCH_LOCK) {
            ensureLoaded(ctx);
            SharedPreferences sp = prefs(ctx);
            if (sp == null) return;

            String host = oString(VirusDb.getServerHost()).trim();
            while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
            if (host.isEmpty()) {
                lastError = "服务器地址为空";
                Log.w(TAG, lastError);
                return;
            }

            String oldDbRaw = oString(sp.getString(K_RAW_DB, ""));
            String oldRulesRaw = oString(sp.getString(K_RAW_RULES, ""));
            String oldEtagDb = oString(sp.getString(K_ETAG_DB, ""));
            String oldEtagRules = oString(sp.getString(K_ETAG_RULES, ""));
            long now = System.currentTimeMillis();
            lastFetch = now;

            HttpResult rDb = httpGet("https://" + host + "/" + FILE_DB, oldEtagDb);
            HttpResult rRules = httpGet("https://" + host + "/" + FILE_RULES, oldEtagRules);

            if (!rDb.ok && !rDb.notModified && !rRules.ok && !rRules.notModified) {
                // 两个文件都没拿到（离线/服务器异常）→ 保留旧数据，只记录错误
                lastError = "拉取失败：" + rDb.error + " / " + rRules.error;
                saveFetchMeta(sp, lastError);
                Log.w(TAG, lastError);
                return;
            }

            String newDbRaw = (rDb.ok && !rDb.notModified) ? rDb.body : oldDbRaw;
            String newRulesRaw = (rRules.ok && !rRules.notModified) ? rRules.body : oldRulesRaw;

            // version 相同且两个 etag 都未变（都 304 或正文完全一样）→ 跳过解析（README §6）
            boolean dbUnchanged = rDb.notModified || sameText(newDbRaw, oldDbRaw);
            boolean rulesUnchanged = rRules.notModified || sameText(newRulesRaw, oldRulesRaw);
            if (dbUnchanged && rulesUnchanged) {
                // 正文没变但 etag 可能变了（例如 CDN 重新发布）→ 把新 etag 记下来，省下次流量
                String etagDb = pickEtag(rDb, oldEtagDb);
                String etagRules = pickEtag(rRules, oldEtagRules);
                if (!etagDb.equals(oldEtagDb) || !etagRules.equals(oldEtagRules)) {
                    sp.edit()
                            .putString(K_ETAG_DB, etagDb)
                            .putString(K_ETAG_RULES, etagRules)
                            .apply();
                }
                lastError = "";
                // 2026-10-24 新增：304/未变化也算一次同步成功（供内置特权页面显示时间）
                lastSuccess = now;
                sp.edit().putLong(K_LAST_SUCCESS, now).apply();
                saveFetchMeta(sp, "");
                Log.d(TAG, "特征库未变化（304/同版本），跳过解析");
                pushSnapshot(ctx);
                return;
            }

            if (newDbRaw.trim().isEmpty()) {
                lastError = FILE_DB + " 无有效内容，保留旧版";
                saveFetchMeta(sp, lastError);
                Log.w(TAG, lastError);
                return;
            }

            // 先解析校验新数据，解析失败 → 不落盘（等价回滚，新数据不生效）
            Db nd = parse(newDbRaw, newRulesRaw);
            if (nd == null) {
                rollback(sp, oldDbRaw, oldRulesRaw, oldEtagDb, oldEtagRules);
                lastError = "新特征库解析失败，已回滚旧版";
                saveFetchMeta(sp, lastError);
                Log.w(TAG, lastError);
                return;
            }

            String newEtagDb = pickEtag(rDb, oldEtagDb);
            String newEtagRules = pickEtag(rRules, oldEtagRules);

            // 解析成功才替换：先把旧 raw/etag 存到 *_prev（供下次解析失败回滚）
            sp.edit()
                    .putString(K_RAW_DB_PREV, oldDbRaw)
                    .putString(K_RAW_RULES_PREV, oldRulesRaw)
                    .putString(K_ETAG_DB_PREV, oldEtagDb)
                    .putString(K_ETAG_RULES_PREV, oldEtagRules)
                    .putString(K_RAW_DB, newDbRaw)
                    .putString(K_RAW_RULES, newRulesRaw)
                    .putString(K_ETAG_DB, newEtagDb)
                    .putString(K_ETAG_RULES, newEtagRules)
                    .putString(K_VERSION, nd.version)
                    .putString(K_UPDATED_AT, nd.updatedAt)
                    .putLong(K_LAST_FETCH, now)
                    .putLong(K_LAST_SUCCESS, now)
                    .putString(K_ERROR, "")
                    .apply();

            cache = nd;
            loadedFromDisk = true;
            lastSuccess = now;
            lastError = "";
            VERDICT_CACHE.clear();
            // 2026-10-24 性能：特征库版本变了，旧的落盘判定全部作废（键里虽含版本，
            // 但会无限累积）；直接清掉，后台扫描会按新库重新判定。
            clearPersistedVerdicts(ctx);
            Log.i(TAG, "特征库已更新: version=" + nd.version
                    + " updated_at=" + nd.updatedAt
                    + " certs=" + nd.certs.size()
                    + " packages=" + nd.packages.size()
                    + " apk=" + nd.apkSamples.size()
                    + " dex=" + nd.dexSamples.size()
                    + " asset=" + nd.assetSamples.size()
                    + " token=" + nd.tokenCount()
                    + " rules=" + nd.rules.size());

            pushSnapshot(ctx);
        }
    }

    /**
     * 后台异步拉取（节流 30 秒、单飞：同一时刻只允许一个拉取线程）。
     *
     * <p>被节流时不会发网络请求，但会顺带补推一次快照（保证内置特权能拿到
     * 已缓存的特征库）。
     */
    public static void refreshAsync(final Context ctx) {
        if (ctx == null) return;
        if (refreshing) {
            Log.d(TAG, "特征库正在拉取中，忽略本次请求");
            return;
        }
        ensureLoaded(ctx);
        if (System.currentTimeMillis() - lastFetch < MIN_FETCH_INTERVAL_MS) {
            Log.d(TAG, "特征库距上次拉取不足 30 秒，跳过网络请求");
            maybePushAsync(ctx);
            return;
        }
        refreshing = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshNow(ctx);
                } catch (Throwable tr) {
                    Log.w(TAG, "特征库异步拉取异常", tr);
                } finally {
                    refreshing = false;
                }
            }
        }, "VirusContentDb-Fetch").start();
    }

    /**
     * 2026-10-24 新增（用户需求）：应用运行期间每 3 分钟拉取一次病毒特征库。
     *
     * <p>只在 Application 主进程调用（由 {@link YouLongApp} 判断进程名后启动），
     * 避免 :guard 哨兵进程重复请求。定时任务使用 daemon 线程：
     *   · 进程活着就一直跑；进程退出线程自动结束；
     *   · 每次都走 {@link #refreshNow(Context)}（内部有锁串行 + ETag/304），
     *     不会与其它刷新并发写缓存；
     *   · 单次网络/解析异常被吞掉，下个周期继续；
     *   · 与手动刷新/启动刷新共用同一份缓存与 etag。
     *
     * <p>重复调用是幂等的（AtomicBoolean）。
     */
    public static void startPeriodicRefresh(final Context ctx) {
        if (ctx == null) return;
        if (!POLLER_STARTED.compareAndSet(false, true)) {
            Log.d(TAG, "定时拉取已启动，忽略重复调用");
            return;
        }
        final Context app = ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx;
        try {
            ScheduledExecutorService es = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "VirusContentDb-Poll");
                t.setDaemon(true);
                return t;
            });
            es.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    lastPollAt = System.currentTimeMillis();
                    pollCount++;
                    try {
                        Log.i(TAG, "定时拉取黑名单列表（第 " + pollCount + " 次）");
                        VirusDb.refreshNow(app);
                        pushSnapshot(app);
                    } catch (Throwable tr) {
                        Log.w(TAG, "定时拉取异常（下个周期重试）", tr);
                    }
                    // 2026-10-24（用户需求）：拉完库立刻做一轮后台检测；下一轮
                    // 刷新在 3 分钟后，正好形成「请求 → 检测」的持续循环。
                    try {
                        VirusAutoScanner.scanOnce(app);
                    } catch (Throwable tr) {
                        Log.w(TAG, "后台病毒库检测异常（下轮继续）", tr);
                    }
                }
            }, PERIODIC_INITIAL_DELAY_MS, PERIODIC_INTERVAL_MS, TimeUnit.MILLISECONDS);
            // 启动 30 秒后先补一次检测，不必等到第一次 3 分钟轮询
            //（此时启动阶段的 VirusDb.refreshNow 通常已经把缓存拉回来了）。
            es.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        VirusAutoScanner.scanOnce(app);
                    } catch (Throwable tr) {
                        Log.w(TAG, "启动后台检测异常", tr);
                    }
                }
            }, 30L, TimeUnit.SECONDS);
            poller = es;
            Log.i(TAG, "已启动黑名单定时拉取 + 后台检测：每 "
                    + (PERIODIC_INTERVAL_MS / 60000L) + " 分钟一次");
        } catch (Throwable tr) {
            // 定时器创建失败不影响主流程；允许下次再尝试
            POLLER_STARTED.set(false);
            Log.w(TAG, "启动定时拉取失败", tr);
        }
    }

    /** 定时拉取是否已启动（供状态/排障） */
    public static boolean isPeriodicRefreshStarted() {
        return POLLER_STARTED.get();
    }

    /** 定时拉取周期（毫秒），供状态页展示 */
    public static long getPollIntervalMs() {
        return PERIODIC_INTERVAL_MS;
    }

    /** 已执行的定时轮询次数（含 304/失败） */
    public static int getPollCount() {
        return pollCount;
    }

    /** 最近一次定时轮询时间（毫秒；0 表示还没跑过） */
    public static long getLastPollAt() {
        return lastPollAt;
    }

    // ======================================================================
    // 2026-10-24 新增：内置特权页面「病毒库同步」卡片对接
    // ======================================================================

    /** 最近一次同步成功时间（0 = 从未成功；对应 K_LAST_SUCCESS） */
    public static long getLastSuccessAt() {
        return lastSuccess;
    }

    /**
     * 手动同步（内置特权页面「立即同步」按钮调用）。
     *
     * @param force true = 忽略 30 秒节流，立即发起网络请求
     * @return true = 已启动后台同步；false = 正在同步中或参数为空
     */
    public static boolean requestSync(final Context ctx, boolean force) {
        if (ctx == null) return false;
        try {
            ensureLoaded(ctx);
            if (!force && System.currentTimeMillis() - lastFetch < MIN_FETCH_INTERVAL_MS) {
                Log.d(TAG, "手动同步被节流（距上次拉取不足 30 秒）");
                maybePushAsync(ctx);
                return false;
            }
            if (refreshing) {
                Log.d(TAG, "手动同步：已有同步在进行中");
                return false;
            }
            refreshing = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 2026-10-25：手动同步也拉 4 个黑名单列表并重推快照
                        VirusDb.refreshNow(ctx);
                        pushSnapshot(ctx);
                    } catch (Throwable tr) {
                        Log.w(TAG, "手动同步异常", tr);
                    } finally {
                        refreshing = false;
                    }
                    // 2026-10-24：手动同步完成后也立刻检测一轮（与定时任务共用单飞）
                    try {
                        VirusAutoScanner.scanOnce(ctx);
                    } catch (Throwable tr) {
                        Log.w(TAG, "手动同步后的后台检测异常", tr);
                    }
                }
            }, "VirusContentDb-ManualSync").start();
            Log.i(TAG, "手动同步已启动");
            return true;
        } catch (Throwable tr) {
            Log.w(TAG, "requestSync error", tr);
            return false;
        }
    }

    /**
     * 给内置特权管理器（manager 模块）UI 读取的同步状态。
     *
     * <p>轻量、无网络、无锁（只读 volatile 字段 + 一次懒加载），可以在 Compose
     * 里每秒轮询；返回 null 表示没有可用的状态提供者（理论上不会）。
     */
    public static roro.stellar.manager.virusdb.VirusDbSyncStatus syncStatus(Context ctx) {
        try {
            // 2026-10-25：黑名单改为 4 个列表（safe1/safe2/名称库），状态也取自 VirusDb
            VirusDb.Data vdb = ctx != null ? VirusDb.get(ctx) : null;
            Set<String> seen = new HashSet<>();
            int pkgs = 0;
            int names = 0;
            if (vdb != null) {
                for (String p : vdb.certainPkgs) if (p != null && seen.add(p)) pkgs++;
                for (String p : vdb.suspectPkgs) if (p != null && seen.add(p)) pkgs++;
                names = vdb.certainNames.size() + vdb.suspectNames.size();
            }
            for (String p : MATCHED_PKGS) if (p != null && seen.add(p)) pkgs++;
            long fetchedAt = VirusDb.getCachedFetchTime();
            String version = "";
            if (fetchedAt > 0) {
                version = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                        java.util.Locale.ROOT).format(new java.util.Date(fetchedAt));
            }
            return new roro.stellar.manager.virusdb.VirusDbSyncStatus(
                    vdb != null && !vdb.isEmpty(),
                    version,
                    VirusDb.getLastSuccess(),
                    fetchedAt,
                    VirusDb.isRefreshing(),
                    oString(VirusDb.getLastError()),
                    POLLER_STARTED.get(),
                    PERIODIC_INTERVAL_MS,
                    !pushedSignature.isEmpty(),
                    pkgs,
                    names
            );
        } catch (Throwable tr) {
            Log.w(TAG, "syncStatus error", tr);
            return new roro.stellar.manager.virusdb.VirusDbSyncStatus(
                    false, "", 0L, 0L, false,
                    tr.getClass().getSimpleName(), false, PERIODIC_INTERVAL_MS,
                    false, 0, 0);
        }
    }

    /** 黑名单列表是否已就绪 */
    public static boolean isReady(Context ctx) {
        if (ctx == null) return false;
        VirusDb.Data vdb = VirusDb.get(ctx);
        return vdb != null && !vdb.isEmpty();
    }

    /** 当前黑名单的拉取时间（毫秒；未拉取返回空串） */
    public static String getVersion(Context ctx) {
        long t = VirusDb.getCachedFetchTime();
        return t > 0 ? String.valueOf(t) : "";
    }

    // ======================================================================
    // 2026-10-25：宿主扫描命中的应用（黑名单包名集合）
    // ======================================================================

    /**
     * 记录一批「已判定为黑名单」的应用包名，并异步把快照重推给内置特权。
     * 不关心命中来源（包名/名称/证书/内容），服务端只按包名全拦。
     */
    public static void onPackagesMatched(Context ctx, Collection<String> pkgs) {
        if (ctx == null || pkgs == null || pkgs.isEmpty()) return;
        boolean changed = false;
        for (String p : pkgs) {
            if (p == null) continue;
            String name = p.trim();
            if (name.isEmpty()) continue;
            if (MATCHED_PKGS.add(name)) changed = true;
        }
        if (!changed) return;
        saveMatched(ctx);
        Log.i(TAG, "已记录黑名单应用 " + MATCHED_PKGS.size() + " 个，准备推送内置特权");
        maybePushAsync(ctx);
    }

    /** 单个应用命中黑名单。 */
    public static void onPackageMatched(Context ctx, String pkg) {
        if (pkg == null) return;
        onPackagesMatched(ctx, Collections.singletonList(pkg));
    }

    /** 应用更新后不再命中 / 已卸载：从黑名单移除并重推。 */
    public static void removeMatchedPackage(Context ctx, String pkg) {
        if (ctx == null || pkg == null) return;
        if (MATCHED_PKGS.remove(pkg.trim())) {
            saveMatched(ctx);
            maybePushAsync(ctx);
        }
    }

    private static void loadMatched(Context ctx) {
        synchronized (LOAD_LOCK) {
            if (matchedLoaded) return;
            try {
                SharedPreferences sp = prefs(ctx, PREF_MATCHED);
                if (sp != null) {
                    Set<String> saved = sp.getStringSet(K_MATCHED, null);
                    if (saved != null) {
                        for (String p : saved) {
                            if (p != null && !p.trim().isEmpty()) MATCHED_PKGS.add(p.trim());
                        }
                    }
                }
            } catch (Throwable tr) {
                Log.w(TAG, "读取黑名单应用列表失败", tr);
            }
            matchedLoaded = true;
        }
    }

    private static void saveMatched(Context ctx) {
        try {
            SharedPreferences sp = prefs(ctx, PREF_MATCHED);
            if (sp == null) return;
            sp.edit().putStringSet(K_MATCHED, new HashSet<>(MATCHED_PKGS)).apply();
        } catch (Throwable tr) {
            Log.w(TAG, "保存黑名单应用列表失败", tr);
        }
    }

    /** 黑名单状态 JSON（网页/排障用） */
    public static String statusJson(Context ctx) {
        try {
            VirusDb.Data vdb = ctx != null ? VirusDb.get(ctx) : null;
            Set<String> seen = new HashSet<>();
            if (vdb != null) {
                for (String p : vdb.certainPkgs) if (p != null) seen.add(p);
                for (String p : vdb.suspectPkgs) if (p != null) seen.add(p);
            }
            for (String p : MATCHED_PKGS) if (p != null) seen.add(p);
            int names = vdb == null ? 0 : vdb.certainNames.size() + vdb.suspectNames.size();
            long fetchedAt = VirusDb.getCachedFetchTime();
            JSONObject o = new JSONObject();
            o.put("ready", vdb != null && !vdb.isEmpty());
            o.put("loaded", loadedFromDisk);
            o.put("refreshing", VirusDb.isRefreshing());
            o.put("version", fetchedAt > 0 ? String.valueOf(fetchedAt) : "");
            o.put("updatedAt", fetchedAt > 0 ? String.valueOf(fetchedAt) : "");
            o.put("schema", "lists");
            o.put("lastFetch", fetchedAt);
            o.put("error", oString(VirusDb.getLastError()));
            o.put("certs", 0);
            o.put("packages", seen.size());
            o.put("names", names);
            o.put("apkSamples", 0);
            o.put("dexSamples", 0);
            o.put("assetSamples", 0);
            o.put("highTokens", 0);
            o.put("lowTokens", 0);
            o.put("structures", 0);
            o.put("patterns", 0);
            o.put("rules", 0);
            o.put("iocs", 0);
            o.put("allowlistCerts", 0);
            o.put("highTokenBlockMin", 0);
            o.put("pushedVersion", oString(pushedVersion));
            o.put("snapshotPushed", !pushedSignature.isEmpty());
            o.put("pushedSignature", oString(pushedSignature));
            o.put("snapshotPath", SNAPSHOT_PATH);
            o.put("periodic", POLLER_STARTED.get());
            o.put("pollIntervalMs", PERIODIC_INTERVAL_MS);
            o.put("pollCount", pollCount);
            o.put("lastPoll", lastPollAt);
            o.put("lastSuccess", VirusDb.getLastSuccess());
            return o.toString();
        } catch (Throwable tr) {
            Log.e(TAG, "statusJson error", tr);
            return "{\"ready\":false}";
        }
    }

    /**
     * 单个已安装 APK 的内容检测（阻塞，读 APK 文件；调用方负责线程与总预算）。
     *
     * <p>判定顺序（README §4.2/§4.4，与 task 一致）：
     * <ol>
     *   <li>签名证书 SHA-256（当前 + 历史）：allowlist → CLEAN；certs 命中：
     *       block → BLOCK，warn → WARN</li>
     *   <li>APK 整包 SHA-256（sourceDir 流式，&gt;150MB 跳过）→ apk_samples</li>
     *   <li>DEX 内容 SHA-256（classes\\d*\\.dex）→ dex_sha256</li>
     *   <li>关键资产 SHA-256（asset_sha256 的路径 + 哈希）</li>
     *   <li>高特异 token（dex/arsc/xml/properties 条目，64MB 预算）：
     *       ≥ high_token_block_min → BLOCK，==1 → WARN</li>
     *   <li>弱信号（structure 全字段、package_pattern、通用 token）——只累计</li>
     *   <li>无强信号且 token=0：弱信号 ≥2 → WARN，否则 CLEAN</li>
     * </ol>
     */
    public static Verdict inspectApk(Context ctx, String pkg) {
        return inspectApk(ctx, pkg, 0L);
    }

    /**
     * 带截止时间的 inspectApk（VirusScanner 的 8 秒总预算用）。
     *
     * @param deadlineMs 绝对时间戳（System.currentTimeMillis() 口径），&lt;=0 表示不限时；
     *                   超时返回 CLEAN + truncated=true
     */
    static Verdict inspectApk(Context ctx, String pkg, long deadlineMs) {
        if (ctx == null || pkg == null || pkg.isEmpty()) {
            return new Verdict(CLEAN, "", "参数为空", false);
        }
        ensureLoaded(ctx);
        final Db d = cache;
        if (d == null || d.isEmpty()) {
            return new Verdict(CLEAN, "", "内容特征库未就绪", false);
        }

        final PackageManager pm = ctx.getPackageManager();
        PackageInfo info;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            } else {
                // noinspection deprecation
                info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            }
        } catch (Throwable tr) {
            Log.w(TAG, "inspectApk: 读取包信息失败 " + pkg + " → " + tr.getMessage());
            return new Verdict(CLEAN, "", "无法读取包信息", false);
        }

        // 判定缓存：包名 + versionCode + lastUpdateTime + 特征库版本。
        // 2026-10-24 性能：除了进程内缓存，还落盘一份（virus_verdict_cache）——
        // 进程重启 / 后台扫描重启后不必把所有 APK 重新哈希一遍。
        final String cacheKey = pkg + "|" + info.versionCode + "|" + info.lastUpdateTime + "|" + d.version;
        Verdict cached = VERDICT_CACHE.get(cacheKey);
        if (cached != null) return cached;
        verdictCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        Verdict persisted = loadPersistedVerdict(verdictCtx, cacheKey);
        if (persisted != null) {
            VERDICT_CACHE.put(cacheKey, persisted);
            return persisted;
        }

        String sourceDir = null;
        try {
            if (info.applicationInfo != null) sourceDir = info.applicationInfo.sourceDir;
            if (sourceDir == null) {
                sourceDir = pm.getApplicationInfo(pkg, 0).sourceDir;
            }
        } catch (Throwable tr) {
            Log.w(TAG, "inspectApk: 读取 APK 路径失败 " + pkg + " → " + tr.getMessage());
        }

        // 已经命中但只要求告警的强信号（证书/整包/资产 action=warn）
        Verdict warnVerdict = null;
        try {
            // ---------------- ① 签名证书 ----------------
            List<String> certShas = certSha256List(info);
            for (String sha : certShas) {
                if (d.allowlistCerts.contains(sha)) {
                    return remember(cacheKey, new Verdict(CLEAN, "", "证书在 allowlist，放行", false));
                }
            }
            for (String sha : certShas) {
                CertEntry ce = d.certs.get(sha);
                if (ce == null) continue;
                String why = dn(ce.displayName, ce.family) + "：签名证书命中";
                if (isBlock(ce.action)) {
                    return remember(cacheKey, new Verdict(BLOCK, ce.family, why, false));
                }
                if (warnVerdict == null) {
                    warnVerdict = new Verdict(WARN, ce.family, why + "（仅告警）", false);
                }
            }

            // 2026-10-24 性能：包名特征先查。命中 block 时直接返回，不再做整包/
            // DEX/token 计算（同时把审查里一直没接线的 packages[] 接上了）。
            PkgEntry pkgEntry = d.packages.get(pkg);
            if (pkgEntry != null) {
                String why = dn(pkgEntry.displayName, pkgEntry.family) + "：包名命中";
                if (isBlock(pkgEntry.action)) {
                    return remember(cacheKey, new Verdict(BLOCK, pkgEntry.family, why, false));
                }
                if (warnVerdict == null) {
                    warnVerdict = new Verdict(WARN, pkgEntry.family, why + "（仅告警）", false);
                }
            }

            if (sourceDir == null) {
                return remember(cacheKey, warnVerdict != null
                        ? warnVerdict
                        : new Verdict(CLEAN, "", "无法读取 APK 文件", false));
            }            File apkFile = new File(sourceDir);
            if (!apkFile.isFile()) {
                return remember(cacheKey, warnVerdict != null
                        ? warnVerdict
                        : new Verdict(CLEAN, "", "APK 文件不存在", false));
            }

            // ---------------- ② APK 整包哈希 ----------------
            // 2026-10-24 性能：整包哈希要读完整个 APK（最大 150MB），而特征库通常
            // 只有一两个样本。先用「库里是否存在同尺寸样本」过滤，没有再跳过哈希
            //（库没给 file_size 时保守地仍然哈希，兼容旧数据）。
            if (!d.apkSamples.isEmpty()
                    && apkFile.length() <= APK_HASH_MAX_BYTES
                    && apkSizeMayMatch(d, apkFile.length())) {
                if (expired(deadlineMs)) return truncatedVerdict();
                String apkSha = sha256File(apkFile);
                ApkEntry ae = apkSha == null ? null : d.apkSamples.get(apkSha);
                if (ae != null) {
                    String why = dn(ae.displayName, ae.family) + "：APK 哈希命中";
                    if (isBlock(ae.action)) {
                        return remember(cacheKey, new Verdict(BLOCK, ae.family, why, false));
                    }
                    if (warnVerdict == null) {
                        warnVerdict = new Verdict(WARN, ae.family, why + "（仅告警）", false);
                    }
                }
            } else if (apkFile.length() > APK_HASH_MAX_BYTES) {
                Log.i(TAG, "APK 超过 150MB，跳过整包哈希: " + pkg + " (" + apkFile.length() + ")");
            }

            // ---------------- ③④⑤ 需要遍历 ZIP ----------------
            ZipFile zip = null;
            try {
                zip = new ZipFile(apkFile);

                // 一次遍历收集 dex 条目 + 结构指纹（不读字节，很便宜）
                List<ZipEntry> dexEntries = new ArrayList<>();
                int fwCount = 0;
                boolean hasArsclib = false;
                boolean hasP12 = false;
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze == null) continue;
                    String name = ze.getName();
                    if (name == null || ze.isDirectory()) continue;
                    if (DEX_ENTRY.matcher(name).matches()) {
                        dexEntries.add(ze);
                    } else if (name.startsWith("frameworks/android/android-") && name.endsWith(".apk")) {
                        fwCount++;
                    } else if (name.equals("arsclib.properties") || name.endsWith("/arsclib.properties")) {
                        hasArsclib = true;
                    } else if (name.startsWith("assets/") && name.endsWith(".p12")) {
                        hasP12 = true;
                    }
                }
                final int dexCount = dexEntries.size();

                // ③ DEX 内容哈希（解压后哈希，重签名不变）
                // 2026-10-24 性能：库里没有 DEX 样本时，完全跳过逐个 DEX 解压+哈希。
                if (!d.dexSamples.isEmpty()) {
                    for (int i = 0; i < dexEntries.size(); i++) {
                        if (expired(deadlineMs)) return truncatedVerdict();
                        ZipEntry ze = dexEntries.get(i);
                        String sha = sha256Stream(zip, ze);
                        DexEntry de = sha == null ? null : d.matchDex(ze.getName(), sha);
                        if (de == null) continue;
                        String why = dn(de.displayName, de.family) + "：DEX 哈希命中（" + de.name + "）";
                        if (isBlock(de.action)) {
                            return remember(cacheKey, new Verdict(BLOCK, de.family, why, false));
                        }
                        if (warnVerdict == null) {
                            warnVerdict = new Verdict(WARN, de.family, why + "（仅告警）", false);
                        }
                    }
                }

                // ④ 关键资产哈希（路径存在 + 内容哈希一致）
                for (int i = 0; i < d.assetSamples.size(); i++) {
                    if (expired(deadlineMs)) return truncatedVerdict();
                    AssetEntry ae = d.assetSamples.get(i);
                    ZipEntry ze = zip.getEntry(ae.path);
                    if (ze == null || ze.isDirectory()) continue;
                    String sha = sha256Stream(zip, ze);
                    if (sha == null || !sha.equalsIgnoreCase(ae.sha256)) continue;
                    String why = dn(ae.displayName, ae.family) + "：关键资产哈希命中（" + ae.path + "）";
                    if (isBlock(ae.action)) {
                        return remember(cacheKey, new Verdict(BLOCK, ae.family, why, false));
                    }
                    if (warnVerdict == null) {
                        warnVerdict = new Verdict(WARN, ae.family, why + "（仅告警）", false);
                    }
                }

                // ⑤ 高特异 token（dex/arsc/xml/properties 条目字节，64MB 总预算）
                int maxHigh = 0;
                String highFamily = "";
                String highDisplay = "";
                boolean lowHit = false;
                String lowFamily = "";
                String lowDisplay = "";
                TokenScan ts = null;
                if (!d.highTokens.isEmpty() || !d.lowTokens.isEmpty()) {
                    ts = scanTokens(zip, d, deadlineMs);
                    for (Map.Entry<String, Integer> e : ts.highByFamily.entrySet()) {
                        int n = e.getValue() == null ? 0 : e.getValue().intValue();
                        if (n > maxHigh) {
                            maxHigh = n;
                            highFamily = e.getKey();
                            highDisplay = ts.displayOf(highFamily, d);
                        }
                    }
                    for (Map.Entry<String, Integer> e : ts.lowByFamily.entrySet()) {
                        int n = e.getValue() == null ? 0 : e.getValue().intValue();
                        if (n > 0) {
                            lowHit = true;
                            if (lowFamily.isEmpty()) {
                                lowFamily = e.getKey();
                                lowDisplay = ts.displayOf(lowFamily, d);
                            }
                        }
                    }
                }

                if (maxHigh >= d.highTokenBlockMin) {
                    return remember(cacheKey, new Verdict(BLOCK, highFamily,
                            dn(highDisplay, highFamily) + "：高特异字符串命中 " + maxHigh + " 个", false));
                }
                if (maxHigh == 1 && warnVerdict == null) {
                    warnVerdict = new Verdict(WARN, highFamily,
                            dn(highDisplay, highFamily) + "：高特异字符串命中 1 个（仅告警）", false);
                }

                // ---------------- ⑥ 弱信号：结构指纹 / 克隆包名形态 / 通用 token ----------------
                int weak = 0;
                String weakFamily = "";
                String weakDisplay = "";
                for (int i = 0; i < d.structures.size(); i++) {
                    StructSig ss = d.structures.get(i);
                    if (ss.matches(dexCount, fwCount, hasArsclib, hasP12)) {
                        weak++;
                        if (weakFamily.isEmpty()) {
                            weakFamily = ss.family;
                            weakDisplay = ss.displayName;
                        }
                    }
                }
                for (int i = 0; i < d.patterns.size(); i++) {
                    PkgPattern pp = d.patterns.get(i);
                    if (pp.matches(pkg)) {
                        weak++;
                        if (weakFamily.isEmpty()) {
                            weakFamily = pp.family;
                            weakDisplay = pp.displayName;
                        }
                    }
                }
                if (lowHit) {
                    weak++;
                    if (weakFamily.isEmpty()) {
                        weakFamily = lowFamily;
                        weakDisplay = lowDisplay;
                    }
                }

                // ---------------- ⑦ 汇总 ----------------
                if (maxHigh == 0 && weak >= 2) {
                    return remember(cacheKey, new Verdict(WARN, weakFamily,
                            dn(weakDisplay, weakFamily) + "：弱信号累计 " + weak + " 项（结构/包名形态/通用字符串）", false));
                }
                if (warnVerdict != null) {
                    return remember(cacheKey, warnVerdict);
                }
                if (ts != null && ts.truncated) {
                    // token 扫描没跑完 → 结果不完整，不缓存
                    return truncatedVerdict();
                }
                String cleanWhy = weak > 0 ? "未命中特征（弱信号 " + weak + " 项）" : "未命中特征";
                return remember(cacheKey, new Verdict(CLEAN, weakFamily, cleanWhy, false));
            } finally {
                if (zip != null) {
                    try { zip.close(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "inspectApk: 内容检测异常 " + pkg + " → " + tr.getClass().getSimpleName(), tr);
            return new Verdict(CLEAN, "", "内容检测异常（已忽略）", false);
        }
    }

    /**
     * 生成服务端快照 JSON（2026-10-25 改：黑名单只来自 4 个列表）。
     *
     * <p>safe1 / safe2（包名）+ mingcheng1 / mingcheng2（应用名称）里的
     * 所有条目都按黑名单处理；服务端按「包名」或「应用名称」命中后，
     * 直接拦掉该应用的所有命令。原 virus-db.json 的内容特征
     * （证书 / 整包 / DEX / 资产 / token）不再进入服务端快照。
     *
     * <pre>
     * {"schema":"youlong/virus-db/snapshot/1","version":"...","updated_at":"...",
     *  "decision_policy":{...},"allowlist_certs":[],"certs":[],
     *  "packages":[{"package_name":"...","action":"block","family":"VirusDb"}],
     *  "names":[{"name":"...","action":"block","family":"VirusDb"}],
     *  "rules":[]}
     * </pre>
     */
    public static String snapshotJson(Context ctx) {
        try {
            ensureLoaded(ctx);
            VirusDb.Data vdb = VirusDb.get(ctx);
            long fetchedAt = VirusDb.getCachedFetchTime();
            JSONObject root = new JSONObject();
            root.put("schema", SNAPSHOT_SCHEMA);
            root.put("version", fetchedAt > 0 ? String.valueOf(fetchedAt) : "lists");
            root.put("updated_at", fetchedAt > 0 ? String.valueOf(fetchedAt) : "");

            JSONObject policy = new JSONObject();
            policy.put("high_token_block_min", 2);
            policy.put("weak_only_action", "warn");
            policy.put("fuzzy_action", "warn");
            root.put("decision_policy", policy);

            root.put("allowlist_certs", new JSONArray());
            // 2026-10-25：内容特征分析不再下发给服务端
            root.put("certs", new JSONArray());

            // 包名黑名单：safe1 + safe2 + 宿主扫描命中的包
            JSONArray packages = new JSONArray();
            Set<String> addedPkgs = new HashSet<>();
            if (vdb != null) {
                addPackages(packages, addedPkgs, vdb.certainPkgs);
                addPackages(packages, addedPkgs, vdb.suspectPkgs);
            }
            for (String p : MATCHED_PKGS) {
                addPackage(packages, addedPkgs, p, "Matched");
            }
            root.put("packages", packages);

            // 应用名称黑名单：mingcheng1 + mingcheng2
            JSONArray names = new JSONArray();
            Set<String> addedNames = new HashSet<>();
            if (vdb != null) {
                addNames(names, addedNames, vdb.certainNames);
                addNames(names, addedNames, vdb.suspectNames);
            }
            root.put("names", names);

            // 内容特征分析（行为规则）不再下发给服务端
            root.put("rules", new JSONArray());

            return root.toString();
        } catch (Throwable tr) {
            Log.e(TAG, "snapshotJson error", tr);
            return "{}";
        }
    }

    private static void addPackages(JSONArray arr, Set<String> added, Set<String> pkgs) {
        if (pkgs == null) return;
        for (String p : pkgs) addPackage(arr, added, p, "VirusDb");
    }

    private static void addPackage(JSONArray arr, Set<String> added, String pkg, String family) {
        if (pkg == null) return;
        String name = pkg.trim();
        if (name.isEmpty() || !added.add(name)) return;
        try {
            JSONObject o = new JSONObject();
            o.put("package_name", name);
            o.put("action", "block");
            o.put("family", family);
            arr.put(o);
        } catch (Throwable ignored) {}
    }

    private static void addNames(JSONArray arr, Set<String> added, Set<String> names) {
        if (names == null) return;
        for (String n : names) {
            if (n == null) continue;
            String name = n.trim();
            if (name.isEmpty()) continue;
            String key = name.toLowerCase(Locale.ROOT);
            if (!added.add(key)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("action", "block");
                o.put("family", "VirusDb");
                arr.put(o);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 把快照推给内置特权（阻塞，后台线程调用）。
     *
     * <p>2026-10-24 改：不再用 `sh` + base64 写 /data/local/tmp（该目录与服务端
     * fork 的 sh 同 uid，策略文件可被任意 shell 改写）。现在走
     * {@link StellarUtils#writeVirusSnapshot(String)} → Binder
     * {@code IStellarService.writePolicyFile}，服务端校验调用方是管理器并原子落盘。
     *
     * <p>同一版本只推一次；特权未连接时静默跳过（下次再试）。
     */
    public static void pushSnapshot(Context ctx) {
        if (ctx == null) return;
        try {
            ensureLoaded(ctx);
            // 2026-10-25：黑名单来自 4 个列表（safe1/safe2/名称库），不再依赖内容特征库。
            VirusDb.Data vdb = VirusDb.get(ctx);
            if ((vdb == null || vdb.isEmpty()) && MATCHED_PKGS.isEmpty()) return;
            if (!StellarUtils.isStellarAvailable() || !StellarUtils.hasStellarPermission()) {
                Log.i(TAG, "内置特权未就绪，暂不推送特征库快照");
                return;
            }
            String json = snapshotJson(ctx);
            if (json == null || json.length() <= 2 || "{}".equals(json)) {
                Log.w(TAG, "快照 JSON 为空，跳过推送");
                return;
            }
            // 2026-10-25：按快照内容签名去重，不只看 version。
            // 这样 safe1/safe2/名称库更新（virus-db 版本没变）也会重推给内置特权。
            String sig = oString(sha256Bytes(json.getBytes(StandardCharsets.UTF_8)));
            if (!sig.isEmpty() && sig.equals(pushedSignature)) return;
            boolean ok = StellarUtils.writeVirusSnapshot(json);
            if (ok) {
                pushedSignature = sig;
                long fetchedAt = VirusDb.getCachedFetchTime();
                pushedVersion = fetchedAt > 0 ? String.valueOf(fetchedAt) : "lists";
                Log.i(TAG, "黑名单快照已推送: " + SNAPSHOT_PATH
                        + " (" + json.length() + " 字节, fetchTime=" + fetchedAt + ")");
            } else {
                Log.w(TAG, "特征库快照推送失败（下次重试）");
            }
        } catch (Throwable tr) {
            Log.w(TAG, "pushSnapshot error", tr);
        }
    }

    // ======================================================================
    // 解析（org.json）
    // ======================================================================

    /** 解析两个 raw JSON；任何结构问题都返回 null（调用方据此回滚旧版） */
    private static Db parse(String rawDb, String rawRules) {
        if (rawDb == null) return null;
        String text = stripBom(rawDb).trim();
        if (text.isEmpty()) return null;

        Db d = new Db();
        try {
            JSONObject root = new JSONObject(text);

            d.schema = str(root, "schema");
            d.version = str(root, "version");
            d.updatedAt = str(root, "updated_at");

            JSONObject policy = root.optJSONObject("decision_policy");
            if (policy != null) {
                d.highTokenBlockMin = policy.optInt("high_token_block_min", 2);
                if (d.highTokenBlockMin < 1) d.highTokenBlockMin = 1;
                String weak = str(policy, "weak_only_action");
                if (!weak.isEmpty()) d.weakOnlyAction = weak;
                String fuzzy = str(policy, "fuzzy_action");
                if (!fuzzy.isEmpty()) d.fuzzyAction = fuzzy;
                JSONArray ss = policy.optJSONArray("strong_signals");
                if (ss != null) {
                    for (int i = 0; i < ss.length(); i++) {
                        String s = oString(ss.optString(i, "")).trim();
                        if (!s.isEmpty()) d.strongSignals.add(s);
                    }
                }
            }

            JSONArray families = root.optJSONArray("families");
            if (families != null) {
                for (int i = 0; i < families.length(); i++) {
                    JSONObject f = families.optJSONObject(i);
                    if (f != null) parseFamily(d, f);
                }
            }

            JSONArray allow = root.optJSONArray("allowlist_certs");
            if (allow != null) {
                for (int i = 0; i < allow.length(); i++) {
                    String sha = lower(oString(allow.optString(i, "")).trim());
                    if (!sha.isEmpty()) d.allowlistCerts.add(sha);
                }
            }
        } catch (Throwable tr) {
            Log.w(TAG, "virus-db.json 解析失败", tr);
            return null;
        }

        // 2026-10-24：结构校验。CDN/劫持返回 200 + `{}`（合法 JSON 但没有特征）
        // 时，旧逻辑会把它当「解析成功」写入 prefs 并替换内存索引，等于静默清空
        // 特征库。这里要求 schema / version / 至少一条特征，否则按解析失败回滚。
        if (d.schema.isEmpty() || !d.schema.startsWith("youlong/virus-db/")) {
            Log.w(TAG, "virus-db.json schema 不合法，拒绝使用: " + d.schema);
            return null;
        }
        if (d.version.isEmpty() || d.isEmpty()) {
            Log.w(TAG, "virus-db.json 缺少 version/families，按解析失败处理");
            return null;
        }

        // 行为规则文件：有就合并（按 id 去重）；内容坏了算解析失败（整版回滚）
        if (rawRules != null && !rawRules.trim().isEmpty()) {
            try {
                JSONObject root = new JSONObject(stripBom(rawRules).trim());
                JSONArray rules = root.optJSONArray("rules");
                if (rules != null) {
                    for (int i = 0; i < rules.length(); i++) {
                        JSONObject o = rules.optJSONObject(i);
                        if (o == null) continue;
                        Rule r = ruleOf(o, "", "");
                        mergeRule(d, r);
                    }
                }
            } catch (Throwable tr) {
                Log.w(TAG, "virus-db-rules.json 解析失败", tr);
                return null;
            }
        }

        return d;
    }

    /** 解析一个 family（certs/packages/apk_samples/content/behavior_rules/iocs） */
    private static void parseFamily(Db d, JSONObject f) {
        String family = str(f, "family");
        if (family.isEmpty()) return;
        String display = str(f, "display_name");
        if (display.isEmpty()) display = family;
        String action = orDefault(str(f, "action"), "block");
        // severity 只保留供展示/审计（判定只用 action + 内容信号）
        d.familySeverity.put(family, orDefault(str(f, "severity"), "medium"));

        // certs[]：family/display_name 由父级补
        JSONArray certs = f.optJSONArray("certs");
        if (certs != null) {
            for (int i = 0; i < certs.length(); i++) {
                JSONObject c = certs.optJSONObject(i);
                if (c == null) continue;
                String sha = lower(str(c, "sha256"));
                if (sha.isEmpty()) continue;
                CertEntry entry = new CertEntry(sha, orDefault(str(c, "action"), action), family, display);
                CertEntry old = d.certs.get(sha);
                // 同一证书出现在多个家族：block 优先
                if (old == null || (!isBlock(old.action) && isBlock(entry.action))) {
                    d.certs.put(sha, entry);
                }
            }
        }

        // packages[]（弱信号）
        JSONArray pkgs = f.optJSONArray("packages");
        if (pkgs != null) {
            for (int i = 0; i < pkgs.length(); i++) {
                JSONObject p = pkgs.optJSONObject(i);
                if (p == null) continue;
                String name = str(p, "package_name");
                if (name.isEmpty()) continue;
                PkgEntry entry = new PkgEntry(name, orDefault(str(p, "action"), action), family, display);
                PkgEntry old = d.packages.get(name);
                if (old == null || (!isBlock(old.action) && isBlock(entry.action))) {
                    d.packages.put(name, entry);
                }
            }
        }

        // apk_samples[]
        JSONArray apks = f.optJSONArray("apk_samples");
        if (apks != null) {
            for (int i = 0; i < apks.length(); i++) {
                JSONObject a = apks.optJSONObject(i);
                if (a == null) continue;
                String sha = lower(str(a, "sha256"));
                if (sha.isEmpty()) continue;
                long fileSize = a.optLong("file_size", -1L);
                ApkEntry entry = new ApkEntry(sha,
                        orDefault(str(a, "action"), action), family, display, fileSize);
                ApkEntry old = d.apkSamples.get(sha);
                if (old == null || (!isBlock(old.action) && isBlock(entry.action))) {
                    d.apkSamples.put(sha, entry);
                }
            }
        }

        // content 下的各类内容特征
        JSONObject content = f.optJSONObject("content");
        if (content != null) {
            // dex_sha256{文件名: sha256}
            JSONObject dex = content.optJSONObject("dex_sha256");
            if (dex != null) {
                java.util.Iterator<String> it = dex.keys();
                while (it != null && it.hasNext()) {
                    String name = it.next();
                    String sha = lower(oString(dex.optString(name, "")).trim());
                    if (name == null || name.isEmpty() || sha.isEmpty()) continue;
                    d.dexSamples.add(new DexEntry(name, sha,
                            orDefault(str(content, "action"), action), family, display));
                }
            }

            // asset_sha256{路径: sha256}
            JSONObject assets = content.optJSONObject("asset_sha256");
            if (assets != null) {
                java.util.Iterator<String> it = assets.keys();
                while (it != null && it.hasNext()) {
                    String path = it.next();
                    String sha = lower(oString(assets.optString(path, "")).trim());
                    if (path == null || path.isEmpty() || sha.isEmpty()) continue;
                    d.assetSamples.add(new AssetEntry(path, sha,
                            orDefault(str(content, "action"), action), family, display));
                }
            }

            // package_pattern{segments, lengths, charset}
            JSONObject pattern = content.optJSONObject("package_pattern");
            if (pattern != null) {
                PkgPattern pp = new PkgPattern();
                pp.segments = pattern.optInt("segments", -1);
                JSONArray lengths = pattern.optJSONArray("lengths");
                if (lengths != null && lengths.length() > 0) {
                    pp.lengths = new int[lengths.length()];
                    for (int i = 0; i < lengths.length(); i++) {
                        pp.lengths[i] = lengths.optInt(i, -1);
                    }
                }
                pp.charset = str(pattern, "charset");
                pp.action = orDefault(str(content, "action"), action);
                pp.family = family;
                pp.displayName = display;
                d.patterns.add(pp);
            }

            // structure{dex_count, frameworks_android_count, has_arsclib, has_p12_asset}
            JSONObject structure = content.optJSONObject("structure");
            if (structure != null) {
                StructSig ss = new StructSig();
                ss.dexCount = structure.has("dex_count") ? structure.optInt("dex_count", -1) : -1;
                ss.fwCount = structure.has("frameworks_android_count")
                        ? structure.optInt("frameworks_android_count", -1) : -1;
                ss.hasArsclib = structure.has("has_arsclib")
                        ? (structure.optBoolean("has_arsclib", false) ? 1 : 0) : -1;
                ss.hasP12 = structure.has("has_p12_asset")
                        ? (structure.optBoolean("has_p12_asset", false) ? 1 : 0) : -1;
                ss.action = orDefault(str(content, "action"), action);
                ss.family = family;
                ss.displayName = display;
                d.structures.add(ss);
            }

            // string_tokens{high[], low[]}
            JSONObject tokens = content.optJSONObject("string_tokens");
            if (tokens != null) {
                String tokenAction = orDefault(str(content, "action"), action);
                JSONArray high = tokens.optJSONArray("high");
                if (high != null) {
                    for (int i = 0; i < high.length(); i++) {
                        String t = oString(high.optString(i, "")).trim();
                        if (!t.isEmpty()) d.highTokens.add(new TokenEntry(t, tokenAction, family, display));
                    }
                }
                JSONArray low = tokens.optJSONArray("low");
                if (low != null) {
                    for (int i = 0; i < low.length(); i++) {
                        String t = oString(low.optString(i, "")).trim();
                        if (!t.isEmpty()) d.lowTokens.add(new TokenEntry(t, tokenAction, family, display));
                    }
                }
            }
        }

        // behavior_rules[]（缺少 family/display_name，由父级补；与 rules 文件按 id 合并）
        JSONArray rules = f.optJSONArray("behavior_rules");
        if (rules != null) {
            for (int i = 0; i < rules.length(); i++) {
                JSONObject o = rules.optJSONObject(i);
                if (o == null) continue;
                mergeRule(d, ruleOf(o, family, display));
            }
        }

        // iocs[]（取证/展示用）
        JSONArray iocs = f.optJSONArray("iocs");
        if (iocs != null) {
            for (int i = 0; i < iocs.length(); i++) {
                JSONObject o = iocs.optJSONObject(i);
                if (o == null) continue;
                d.iocs.add(new Ioc(str(o, "type"), str(o, "value"), str(o, "description"), family));
            }
        }
    }

    /** 从 JSON 取一条规则；family/displayName 缺省用父级补 */
    private static Rule ruleOf(JSONObject o, String family, String display) {
        Rule r = new Rule();
        r.id = str(o, "id");
        r.family = str(o, "family");
        if (r.family.isEmpty()) r.family = family == null ? "" : family;
        r.displayName = display == null ? "" : display;
        r.kind = orDefault(str(o, "kind"), "token");
        r.pattern = str(o, "pattern");
        r.weight = o.optInt("weight", 0);
        r.action = orDefault(str(o, "action"), "warn");
        r.description = str(o, "description");
        return r;
    }

    /** 按 id 去重合并规则；已有记录缺 family 时用新记录补 */
    private static void mergeRule(Db d, Rule r) {
        if (r == null || r.id.isEmpty() || r.pattern.isEmpty()) return;
        Rule old = d.rules.get(r.id);
        if (old == null) {
            d.rules.put(r.id, r);
            return;
        }
        if (old.family.isEmpty() && !r.family.isEmpty()) old.family = r.family;
        if (old.displayName.isEmpty() && !r.displayName.isEmpty()) old.displayName = r.displayName;
    }

    // ======================================================================
    // 内容检测：token 扫描 / 结构 / 哈希
    // ======================================================================

    /** token 扫描结果（按家族累计命中的高特异/通用 token） */
    private static final class TokenScan {
        final Map<String, Integer> highByFamily = new HashMap<>();
        final Map<String, Integer> lowByFamily = new HashMap<>();
        final Map<String, String> familyDisplay = new HashMap<>();
        long bytesScanned = 0L;
        boolean truncated = false;

        String displayOf(String family, Db d) {
            String dn = familyDisplay.get(family);
            if (dn != null && !dn.isEmpty()) return dn;
            // 兜底：从库里任一条该家族的 token 上取展示名
            for (int i = 0; i < d.highTokens.size(); i++) {
                TokenEntry t = d.highTokens.get(i);
                if (t.family.equals(family)) return t.displayName;
            }
            return family;
        }
    }

    /**
     * 扫 dex/arsc/xml/properties 条目字节里的高特异 token。
     *
     * <p>与 yl-tool-data/matcher.py 的 scan_tokens 语义一致，但：
     *   · 加 64MB 总预算 + 截止时间，避免手动扫描被大 APK 卡死；
     *   · 256KB 分块 + token 长度重叠流式匹配，避免把几十 MB 的 dex 整个
     *     读进内存（低端机 OOM 会把扫描线程直接打崩）。
     */
    private static TokenScan scanTokens(ZipFile zip, Db d, long deadlineMs) {
        TokenScan r = new TokenScan();
        Set<String> hitHigh = new HashSet<>();
        Set<String> hitLow = new HashSet<>();
        // 分块匹配要留 token 长度的重叠，否则跨块边界的 token 会漏掉
        int maxTokenBytes = 1;
        for (int i = 0; i < d.highTokens.size(); i++) {
            int len = d.highTokens.get(i).token.getBytes(StandardCharsets.UTF_8).length;
            if (len > maxTokenBytes) maxTokenBytes = len;
        }
        for (int i = 0; i < d.lowTokens.size(); i++) {
            int len = d.lowTokens.get(i).token.getBytes(StandardCharsets.UTF_8).length;
            if (len > maxTokenBytes) maxTokenBytes = len;
        }
        try {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                if (r.bytesScanned >= TOKEN_SCAN_MAX_BYTES) { r.truncated = true; break; }
                if (expired(deadlineMs)) { r.truncated = true; break; }
                ZipEntry ze = en.nextElement();
                if (ze == null || ze.isDirectory()) continue;
                String name = ze.getName();
                if (name == null || !matchTokenSuffix(name)) continue;

                InputStream in = null;
                try {
                    in = zip.getInputStream(ze);
                    if (in == null) continue;
                    scanEntryStream(in, d, r, hitHigh, hitLow, maxTokenBytes, deadlineMs);
                } catch (Throwable tr) {
                    Log.w(TAG, "token 扫描条目失败: " + name, tr);
                } finally {
                    closeQuietly(in);
                }
                if (r.truncated) break;
            }
        } catch (Throwable tr) {
            Log.w(TAG, "token 扫描失败", tr);
            r.truncated = true;
        }
        return r;
    }

    /** 单条目流式 token 匹配（内存占用恒定：256KB + token 长度重叠） */
    private static void scanEntryStream(InputStream in, Db d, TokenScan r,
                                        Set<String> hitHigh, Set<String> hitLow,
                                        int maxTokenBytes, long deadlineMs) throws java.io.IOException {
        final int overlap = Math.max(0, maxTokenBytes - 1);
        final int chunk = 256 * 1024;
        byte[] buf = new byte[chunk + overlap];
        int carry = 0;
        while (true) {
            if (r.bytesScanned >= TOKEN_SCAN_MAX_BYTES || expired(deadlineMs)) {
                r.truncated = true;
                return;
            }
            int want = (int) Math.min((long) chunk, TOKEN_SCAN_MAX_BYTES - r.bytesScanned);
            if (want <= 0) {
                r.truncated = true;
                return;
            }
            int n = in.read(buf, carry, want);
            if (n <= 0) return;
            int total = carry + n;
            r.bytesScanned += n;
            matchTokenChunk(buf, total, d, r, hitHigh, hitLow);
            if (total > overlap) {
                System.arraycopy(buf, total - overlap, buf, 0, overlap);
                carry = overlap;
            } else {
                carry = total;
            }
        }
    }

    /** 在一个数据块里匹配 high/low token（ASCII 走 latin1 indexOf，非 ASCII 走字节查找） */
    private static void matchTokenChunk(byte[] buf, int len, Db d, TokenScan r,
                                        Set<String> hitHigh, Set<String> hitLow) {
        String latin1 = null;
        for (int i = 0; i < d.highTokens.size(); i++) {
            TokenEntry t = d.highTokens.get(i);
            String key = t.family + "\u0000" + t.token;
            if (hitHigh.contains(key)) continue;
            boolean hit;
            if (t.ascii) {
                if (latin1 == null) latin1 = new String(buf, 0, len, StandardCharsets.ISO_8859_1);
                hit = latin1.contains(t.token);
            } else {
                hit = byteContains(buf, len, t.token.getBytes(StandardCharsets.UTF_8));
            }
            if (!hit) continue;
            hitHigh.add(key);
            r.familyDisplay.put(t.family, t.displayName);
            Integer c = r.highByFamily.get(t.family);
            r.highByFamily.put(t.family, c == null ? 1 : c.intValue() + 1);
        }
        for (int i = 0; i < d.lowTokens.size(); i++) {
            TokenEntry t = d.lowTokens.get(i);
            String key = t.family + "\u0000" + t.token;
            if (hitLow.contains(key)) continue;
            boolean hit;
            if (t.ascii) {
                if (latin1 == null) latin1 = new String(buf, 0, len, StandardCharsets.ISO_8859_1);
                hit = latin1.contains(t.token);
            } else {
                hit = byteContains(buf, len, t.token.getBytes(StandardCharsets.UTF_8));
            }
            if (!hit) continue;
            hitLow.add(key);
            r.familyDisplay.put(t.family, t.displayName);
            Integer c = r.lowByFamily.get(t.family);
            r.lowByFamily.put(t.family, c == null ? 1 : c.intValue() + 1);
        }
    }

    private static boolean matchTokenSuffix(String name) {
        for (int i = 0; i < TOKEN_SCAN_SUFFIX.length; i++) {
            if (name.endsWith(TOKEN_SCAN_SUFFIX[i])) return true;
        }
        return false;
    }

    /** 字节级子串查找（非 ASCII token 用；hayLen 指定有效长度） */
    private static boolean byteContains(byte[] hay, int hayLen, byte[] needle) {
        if (hay == null || needle == null || needle.length == 0 || hayLen < needle.length) return false;
        byte first = needle[0];
        int limit = hayLen - needle.length;
        for (int i = 0; i <= limit; i++) {
            if (hay[i] != first) continue;
            int j = 1;
            while (j < needle.length && hay[i + j] == needle[j]) j++;
            if (j == needle.length) return true;
        }
        return false;
    }

    /** 计算 ZIP 条目内容的 SHA-256（小写 hex；失败返回 null） */
    private static String sha256Stream(ZipFile zip, ZipEntry ze) {
        InputStream in = null;
        try {
            in = zip.getInputStream(ze);
            if (in == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[IO_BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Throwable tr) {
            Log.w(TAG, "计算条目哈希失败: " + (ze == null ? "" : ze.getName()), tr);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 计算文件 SHA-256（流式，小写 hex；失败返回 null） */
    private static String sha256File(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[IO_BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Throwable tr) {
            Log.w(TAG, "计算文件哈希失败: " + (file == null ? "" : file.getPath()), tr);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 取包的证书 SHA-256 列表（当前 + 历史；API &lt; 28 回退 GET_SIGNATURES） */
    private static List<String> certSha256List(PackageInfo info) {
        List<String> out = new ArrayList<>();
        try {
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (info != null && info.signingInfo != null) {
                    sigs = info.signingInfo.hasMultipleSigners()
                            ? info.signingInfo.getApkContentsSigners()
                            : info.signingInfo.getSigningCertificateHistory();
                }
            } else if (info != null) {
                // noinspection deprecation
                sigs = info.signatures;
            }
            if (sigs == null) return out;
            for (int i = 0; i < sigs.length; i++) {
                Signature s = sigs[i];
                if (s == null) continue;
                String sha = sha256Bytes(s.toByteArray());
                if (sha != null && !sha.isEmpty()) out.add(sha);
            }
        } catch (Throwable tr) {
            Log.w(TAG, "读取签名证书失败", tr);
        }
        return out;
    }

    private static String sha256Bytes(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        } catch (Throwable tr) {
            Log.w(TAG, "计算证书哈希失败", tr);
            return null;
        }
    }

    // ======================================================================
    // HTTP（带 If-None-Match，304 不更新）
    // ======================================================================

    private static final class HttpResult {
        boolean ok = false;
        boolean notModified = false;
        String body = "";
        String etag = "";
        String error = "";
    }

    private static HttpResult httpGet(String url, String ifNoneMatch) {
        HttpResult r = new HttpResult();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            // 两个特征文件都是 Cloudflare Pages 上的静态小文件：连接 8 秒、读取 10 秒足够，
            // 避免离线时给 VirusDb.refreshNow 叠加太长的等待
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "YulongShield/9.2.9");
            conn.setRequestProperty("Accept-Charset", "UTF-8");
            if (ifNoneMatch != null && !ifNoneMatch.isEmpty()) {
                conn.setRequestProperty("If-None-Match", ifNoneMatch);
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                r.notModified = true;
                r.etag = ifNoneMatch == null ? "" : ifNoneMatch;
                Log.d(TAG, "特征库未变更(304): " + url);
                return r;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                r.error = "HTTP " + code;
                Log.w(TAG, "特征库拉取失败(" + code + "): " + url);
                return r;
            }
            String etag = conn.getHeaderField("ETag");
            if (etag != null) r.etag = etag.trim();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            r.body = sb.toString();
            r.ok = true;
            return r;
        } catch (Throwable tr) {
            r.error = tr.getClass().getSimpleName()
                    + (tr.getMessage() == null ? "" : ": " + tr.getMessage());
            Log.w(TAG, "特征库拉取异常: " + url + " → " + tr.getMessage());
            return r;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    // ======================================================================
    // 小工具
    // ======================================================================

    private static void ensureLoaded(Context ctx) {
        if (!loadedFromDisk) loadFromCache(ctx);
        if (!matchedLoaded) loadMatched(ctx);
    }

    private static SharedPreferences prefs(Context ctx) {
        return prefs(ctx, PREF);
    }

    private static SharedPreferences prefs(Context ctx, String name) {
        try {
            return ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
        } catch (Throwable tr) {
            Log.w(TAG, "打开 SharedPreferences 失败", tr);
            return null;
        }
    }

    private static void saveFetchMeta(SharedPreferences sp, String error) {
        try {
            sp.edit()
                    .putLong(K_LAST_FETCH, lastFetch)
                    .putString(K_ERROR, error == null ? "" : error)
                    .apply();
        } catch (Throwable tr) {
            Log.w(TAG, "保存拉取状态失败", tr);
        }
    }

    /** 解析失败回滚：把旧 raw/etag 恢复为当前生效版本（新数据不生效） */
    private static void rollback(SharedPreferences sp, String oldDbRaw, String oldRulesRaw,
                                 String oldEtagDb, String oldEtagRules) {
        try {
            sp.edit()
                    .putString(K_RAW_DB, oldDbRaw == null ? "" : oldDbRaw)
                    .putString(K_RAW_RULES, oldRulesRaw == null ? "" : oldRulesRaw)
                    .putString(K_ETAG_DB, oldEtagDb == null ? "" : oldEtagDb)
                    .putString(K_ETAG_RULES, oldEtagRules == null ? "" : oldEtagRules)
                    .apply();
            Log.w(TAG, "已回滚到本地旧版特征库");
        } catch (Throwable tr) {
            Log.w(TAG, "回滚特征库失败", tr);
        }
    }

    private static String pickEtag(HttpResult r, String oldEtag) {
        if (r == null) return oldEtag == null ? "" : oldEtag;
        if (!r.ok || r.notModified) return oldEtag == null ? "" : oldEtag;
        return (r.etag == null || r.etag.isEmpty()) ? (oldEtag == null ? "" : oldEtag) : r.etag;
    }

    private static void maybePushAsync(final Context ctx) {
        try {
            // 2026-10-25：黑名单来自 4 个列表 / 扫描命中；不再看内容特征库 cache
            VirusDb.Data vdb = VirusDb.get(ctx);
            if ((vdb == null || vdb.isEmpty()) && MATCHED_PKGS.isEmpty()) return;
            // 是否需要重推交给 pushSnapshot 用内容签名判断
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        pushSnapshot(ctx);
                    } catch (Throwable ignored) {
                        // 快照推送失败不影响检测
                    }
                }
            }, "VirusContentDb-Push").start();
        } catch (Throwable tr) {
            Log.w(TAG, "maybePushAsync error", tr);
        }
    }

    /** 缓存判定结果（截断的结果不缓存，避免把不完整结果固化；同时落盘一份） */
    private static Verdict remember(String key, Verdict v) {
        if (v != null && !v.truncated) {
            try {
                if (VERDICT_CACHE.size() > VERDICT_CACHE_MAX) VERDICT_CACHE.clear();
                VERDICT_CACHE.put(key, v);
                Context c = verdictCtx;
                if (c != null) persistVerdict(c, key, v);
            } catch (Throwable ignored) {}
        }
        return v;
    }

    // ======================================================================
    // 2026-10-24 性能：判定结果落盘 / 整包哈希的尺寸预过滤
    // ======================================================================

    /** 判定结果落盘（value = level \u0001 family \u0001 reason；键里已含特征库版本）。 */
    private static void persistVerdict(Context ctx, String key, Verdict v) {
        try {
            SharedPreferences sp = verdictPrefs(ctx);
            if (sp == null) return;
            SharedPreferences.Editor ed = sp.edit();
            if (PERSISTED_COUNT.get() < 0) {
                PERSISTED_COUNT.set(sp.getAll().size());
            }
            if (PERSISTED_COUNT.incrementAndGet() > 1200) {
                ed.clear(); // 防止无限增长（键里含特征库版本，旧版本会累积）
                PERSISTED_COUNT.set(1);
            }
            ed.putString(K_VERDICT_PREFIX + key,
                    v.level + "\u0001" + v.family + "\u0001" + v.reason);
            ed.apply();
        } catch (Throwable ignored) {}
    }

    private static SharedPreferences verdictPrefs(Context ctx) {
        SharedPreferences sp = verdictPrefs;
        if (sp == null) {
            sp = prefs(ctx, PREF_VERDICT);
            verdictPrefs = sp;
        }
        return sp;
    }

    private static Verdict loadPersistedVerdict(Context ctx, String key) {
        try {
            SharedPreferences sp = verdictPrefs(ctx);
            if (sp == null) return null;
            String val = sp.getString(K_VERDICT_PREFIX + key, null);
            if (val == null || val.isEmpty()) return null;
            String[] p = val.split("\u0001", 3);
            if (p.length < 3) return null;
            return new Verdict(Integer.parseInt(p[0]), p[1], p[2], false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 特征库版本变化后清掉落盘判定（新特征可能改变结论）。 */
    private static void clearPersistedVerdicts(Context ctx) {
        try {
            SharedPreferences sp = verdictPrefs(ctx);
            if (sp != null) {
                sp.edit().clear().apply();
                PERSISTED_COUNT.set(0);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 特征库里是否存在「大小与目标 APK 一致」（或未提供大小）的整包样本。
     * 用于在整包 SHA-256 之前做廉价过滤，避免对每个应用都读完整个 APK。
     */
    private static boolean apkSizeMayMatch(Db d, long size) {
        for (ApkEntry ae : d.apkSamples.values()) {
            if (ae.fileSize <= 0L || ae.fileSize == size) return true;
        }
        return false;
    }

    private static Verdict truncatedVerdict() {
        return new Verdict(CLEAN, "", "内容检测超出时间预算，结果不完整", true);
    }

    private static boolean expired(long deadlineMs) {
        return deadlineMs > 0L && System.currentTimeMillis() > deadlineMs;
    }

    private static boolean isBlock(String action) {
        return action != null && "block".equalsIgnoreCase(action.trim());
    }

    private static String dn(String displayName, String family) {
        String d = oString(displayName).trim();
        if (!d.isEmpty()) return d;
        String f = oString(family).trim();
        return f.isEmpty() ? "特征库" : f;
    }

    private static boolean sameText(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        return x.equals(y);
    }

    private static String stripBom(String s) {
        if (s == null) return "";
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }

    private static String str(JSONObject o, String key) {
        if (o == null || key == null) return "";
        try {
            return oString(o.optString(key, "")).trim();
        } catch (Throwable tr) {
            return "";
        }
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isEmpty()) ? def : v;
    }

    /** 统一处理 org.json 的 "null" 字符串与真正 null */
    private static String oString(String s) {
        if (s == null) return "";
        if ("null".equals(s)) return "";
        return s;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try { in.close(); } catch (Throwable ignored) {}
    }
}
