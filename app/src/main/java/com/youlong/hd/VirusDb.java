package com.youlong.hd;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;


public final class VirusDb {

    private static final String TAG = "VirusDb";
    private static final String PREF = "virus_db_prefs";

    // ========================================================================
    // Remote list endpoints are intentionally absent from this package:
    // the server host and the five list paths are not shipped.
    // ========================================================================
    private static final String SERVER_HOST_D = "";
    private static final String SERVER_BASE = "";

    
    private static String stripScheme(String h) {
        if (h == null) return "";
        String s = h.trim();
        if (s.regionMatches(true, 0, "https://", 0, 8)) return s.substring(8);
        if (s.regionMatches(true, 0, "http://", 0, 7)) return s.substring(7);
        return s;
    }

    
    private static final String URL_CERTAIN_PKG = "";
    private static final String URL_SUSPECT_PKG = "";
    private static final String URL_CERTAIN_NAME = "";
    private static final String URL_SUSPECT_NAME = "";
    private static final String URL_KEYS = "";

    
    public static String getServerHost() { return SERVER_HOST_D; }

    private static final String K_CERTAIN_PKG = "certain_pkgs";
    private static final String K_CERTAIN_NAME = "certain_names";
    private static final String K_SUSPECT_PKG = "suspect_pkgs";
    private static final String K_SUSPECT_NAME = "suspect_names";
    private static final String K_KEYS = "suspect_keys";
    private static final String K_TIME = "fetch_time";

    
    private static final long MIN_FETCH_INTERVAL_MS = 30000L;

    
    private static volatile Data cache = Data.EMPTY;
    private static volatile boolean loadedFromDisk = false;
    private static volatile boolean fetching = false;
    private static volatile long lastFetchTime = 0L;

    // ======================================================================
    
    // ======================================================================
    public static final class ListStatus {
        public final String key;
        public final String label;
        public final String url;
        
        public volatile boolean ok = false;
        
        public volatile int count = 0;
        
        public volatile long updatedAt = 0L;

        ListStatus(String key, String label, String url) {
            this.key = key;
            this.label = label;
            this.url = url;
        }
    }

    private static final ListStatus LS_CERTAIN_PKG = new ListStatus("certainPkg", "100% 病毒包名", URL_CERTAIN_PKG);
    private static final ListStatus LS_SUSPECT_PKG = new ListStatus("suspectPkg", "可能病毒包名", URL_SUSPECT_PKG);
    private static final ListStatus LS_CERTAIN_NAME = new ListStatus("certainName", "100% 病毒名称", URL_CERTAIN_NAME);
    private static final ListStatus LS_SUSPECT_NAME = new ListStatus("suspectName", "可能病毒名称", URL_SUSPECT_NAME);
    private static final ListStatus LS_KEYS = new ListStatus("keys", "可能病毒关键字", URL_KEYS);
    private static final ListStatus[] ALL_LISTS = {
            LS_CERTAIN_PKG, LS_SUSPECT_PKG, LS_CERTAIN_NAME, LS_SUSPECT_NAME, LS_KEYS};

    
    private static volatile boolean serverReachable = false;
    
    private static volatile long lastAttempt = 0L;
    
    private static volatile long lastSuccess = 0L;
    
    private static volatile String lastError = "";
    
    private static volatile String lastHttpDetail = "";
    
    private static volatile boolean refreshing = false;

    public static java.util.List<ListStatus> getListStatus() {
        return java.util.Arrays.asList(ALL_LISTS);
    }

    public static boolean isServerReachable() { return serverReachable; }

    public static long getLastAttempt() { return lastAttempt; }

    public static long getLastSuccess() { return lastSuccess; }

    public static String getLastError() { return lastError; }

    public static boolean isRefreshing() { return refreshing; }

    
    public static long getCachedFetchTime() { return lastFetchTime; }

    private VirusDb() {}

    // ======================================================================
    
    // ======================================================================
    public static final class Data {
        public static final Data EMPTY = new Data(new HashSet<String>(), new HashSet<String>(),
                new HashSet<String>(), new HashSet<String>(), new HashSet<String>());

        
        public final Set<String> certainPkgs;
        
        public final Set<String> certainNames;
        
        public final Set<String> suspectPkgs;
        
        public final Set<String> suspectNames;
        
        public final Set<String> keys;

        
        private final Set<String> certainNamesLower = new HashSet<>();
        private final Set<String> suspectNamesLower = new HashSet<>();
        private final java.util.List<String> keysLower = new java.util.ArrayList<>();

        Data(Set<String> certainPkgs, Set<String> certainNames, Set<String> suspectPkgs,
             Set<String> suspectNames, Set<String> keys) {
            this.certainPkgs = certainPkgs;
            this.certainNames = certainNames;
            this.suspectPkgs = suspectPkgs;
            this.suspectNames = suspectNames;
            this.keys = keys;
            for (String s : certainNames) certainNamesLower.add(s.toLowerCase(Locale.ROOT));
            for (String s : suspectNames) suspectNamesLower.add(s.toLowerCase(Locale.ROOT));
            for (String s : keys) {
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) keysLower.add(t);
            }
        }

        
        public boolean isEmpty() {
            return certainPkgs.isEmpty() && certainNames.isEmpty()
                    && suspectPkgs.isEmpty() && suspectNames.isEmpty() && keys.isEmpty();
        }

        
        public boolean isCertain(String pkg, String label) {
            if (pkg != null && !pkg.isEmpty() && certainPkgs.contains(pkg)) return true;
            String l = label == null ? "" : label.trim();
            if (l.isEmpty()) return false;
            return certainNames.contains(l) || certainNamesLower.contains(l.toLowerCase(Locale.ROOT));
        }

        
        public String matchSuspect(String pkg, String label) {
            String l = label == null ? "" : label.trim();
            String ll = l.toLowerCase(Locale.ROOT);
            if (pkg != null && !pkg.isEmpty()) {
                if (suspectPkgs.contains(pkg)) return "包名命中可疑库";
                String pl = pkg.toLowerCase(Locale.ROOT);
                for (int i = 0; i < keysLower.size(); i++) {
                    if (pl.contains(keysLower.get(i))) return "包名含关键字";
                }
            }
            if (!l.isEmpty()) {
                if (suspectNames.contains(l) || suspectNamesLower.contains(ll)) return "应用名称命中可疑库";
                for (int i = 0; i < keysLower.size(); i++) {
                    if (ll.contains(keysLower.get(i))) return "名称含关键字";
                }
            }
            return null;
        }

        public int total() {
            return certainPkgs.size() + certainNames.size()
                    + suspectPkgs.size() + suspectNames.size() + keys.size();
        }
    }

    // ======================================================================
    
    // ======================================================================

    
    public static Data get(Context ctx) {
        if (!loadedFromDisk) loadFromCache(ctx);
        return cache;
    }

    
    public static void loadFromCache(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            Set<String> cp = splitToSet(sp.getString(K_CERTAIN_PKG, ""));
            Set<String> cn = splitToSet(sp.getString(K_CERTAIN_NAME, ""));
            Set<String> spk = splitToSet(sp.getString(K_SUSPECT_PKG, ""));
            Set<String> sn = splitToSet(sp.getString(K_SUSPECT_NAME, ""));
            Set<String> ks = splitToSet(sp.getString(K_KEYS, ""));
            cache = new Data(cp, cn, spk, sn, ks);
            lastFetchTime = sp.getLong(K_TIME, 0L);
            loadedFromDisk = true;
            Log.i(TAG, "病毒库本地缓存已加载: " + cache.total() + " 条");
        } catch (Exception e) {
            Log.e(TAG, "读取病毒库缓存失败", e);
            loadedFromDisk = true;
        }
    }

    
    public static void refreshAsync(final Context ctx) {
        
        Log.i(TAG, "开源版未内置病毒库：跳过拉取（详见 VirusDb 类注释）");
    }

    
    public static void refreshNow(Context ctx) {
        
        Log.i(TAG, "开源版未内置病毒库：refreshNow 为空实现");
    }

    
    @SuppressWarnings("unused")
    private static void refreshNowLegacy(Context ctx) {
        loadFromCache(ctx);
        final Data old = cache;

        refreshing = true;
        lastAttempt = System.currentTimeMillis();
        lastError = "";
        try {
            
            Set<String> cPkg = fetchSet(LS_CERTAIN_PKG, true);
            Set<String> sPkg = fetchSet(LS_SUSPECT_PKG, true);
            Set<String> cName = fetchSet(LS_CERTAIN_NAME, false);
            Set<String> sName = fetchSet(LS_SUSPECT_NAME, false);
            Set<String> keys = fetchSet(LS_KEYS, false);

            Data nd = new Data(
                    cPkg != null ? cPkg : old.certainPkgs,
                    cName != null ? cName : old.certainNames,
                    sPkg != null ? sPkg : old.suspectPkgs,
                    sName != null ? sName : old.suspectNames,
                    keys != null ? keys : old.keys);

            cache = nd;
            lastFetchTime = System.currentTimeMillis();

            int okCount = 0;
            for (ListStatus ls : ALL_LISTS) if (ls.ok) okCount++;
            if (okCount > 0) {
                serverReachable = true;
                lastSuccess = lastFetchTime;
            } else {
                serverReachable = false;
                if (lastError.isEmpty()) lastError = "所有列表均拉取失败";
            }

            save(ctx, nd);
            Log.i(TAG, "病毒库已更新(" + okCount + "/" + ALL_LISTS.length + "): 100%包名=" + nd.certainPkgs.size()
                    + " 100%名称=" + nd.certainNames.size()
                    + " 可疑包名=" + nd.suspectPkgs.size()
                    + " 可疑名称=" + nd.suspectNames.size()
                    + " 关键字=" + nd.keys.size());
        } finally {
            refreshing = false;
        }
    }

    // ======================================================================
    
    // ======================================================================

    private static void save(Context ctx, Data d) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(K_CERTAIN_PKG, join(d.certainPkgs))
                    .putString(K_CERTAIN_NAME, join(d.certainNames))
                    .putString(K_SUSPECT_PKG, join(d.suspectPkgs))
                    .putString(K_SUSPECT_NAME, join(d.suspectNames))
                    .putString(K_KEYS, join(d.keys))
                    .putLong(K_TIME, lastFetchTime)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "写入病毒库缓存失败", e);
        }
    }

    
    private static Set<String> fetchSet(ListStatus st, boolean isPkg) {
        lastHttpDetail = "";
        String body = httpGet(st.url);
        if (body == null) {
            st.ok = false;
            if (lastError == null || lastError.isEmpty()) {
                lastError = st.label + " 拉取失败"
                        + (lastHttpDetail.isEmpty() ? "" : "（" + lastHttpDetail + "）");
            }
            return null;
        }
        Set<String> set = parse(body, isPkg);
        st.ok = true;
        st.count = set.size();
        st.updatedAt = System.currentTimeMillis();
        return set;
    }

    
    private static Set<String> parse(String body, boolean isPkg) {
        Set<String> out = new LinkedHashSet<>();
        if (body == null) return out;
        String s = body.replace("\uFEFF", "")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("[", "\n").replace("]", "\n")
                .replace("\"", "").replace(",", "\n");
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (isPkg) t = cleanPkg(t);
            if (t.isEmpty()) continue;
            out.add(t);
        }
        return out;
    }

    
    private static String cleanPkg(String raw) {
        String s = raw.replace("\uFEFF", "").trim();
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

    private static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    private static Set<String> splitToSet(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    
    // 注意（2026-10 审查）：本方法仅被 refreshNowLegacy 调用，而 refresh 系列在
    // 开源版是空实现，此链路不可达。若未来接入数据源：必须强制 HTTPS（manifest
    // usesCleartextTraffic 已改为 false）并对响应做签名校验，防止 MITM 注入包名。
    private static String httpGet(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "YulongShield/9.1.1");
            conn.setRequestProperty("Accept-Charset", "UTF-8");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                lastHttpDetail = "HTTP " + code;
                Log.w(TAG, "拉取失败(" + code + "): " + url);
                return null;
            }
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Exception e) {
            lastHttpDetail = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            Log.w(TAG, "拉取异常: " + url + " → " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }
}
