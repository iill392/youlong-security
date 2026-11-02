package com.youlong.hd;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全屏「添加白名单」页面（2026-10-30）。
 *
 * 性能要点：
 *   · ListView + convertView 回收，不再把 150+ 个 LinearLayout 一次性塞进 ScrollView；
 *   · 勾选只 notifyDataSetChanged，不重建页面；
 *   · 应用列表和图标在后台线程加载，进入页面先显示「加载中」。
 */
public class WhitelistPickerActivity extends Activity {

    private static final int COLOR_BG = 0xFFF5F6F8;
    private static final int COLOR_PRIMARY = 0xFF1565C0;
    private static final int COLOR_TEXT = 0xFF172033;
    private static final int COLOR_SUB = 0xFF7B8499;
    private static final int COLOR_LINE = 0xFFE6EAF2;

    private PackageManager pm;
    private SharedPreferences prefs;
    private final Set<String> currentWhitelist = new HashSet<>();
    private final Set<String> selected = new HashSet<>();
    private final List<AppItem> allApps = new ArrayList<>();
    private final List<AppItem> visibleApps = new ArrayList<>();
    /** 只缓存已经滚动到过的图标；不预加载全部应用图标。 */
    private final Map<String, Drawable> iconCache = new ConcurrentHashMap<>();
    private final Set<String> loadingIcons = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(2);

    private ListView listView;
    private AppAdapter adapter;
    private TextView countView;
    private TextView selectAllView;
    private TextView submitView;
    private EditText searchInput;
    private String query = "";
    private boolean loadFinished = false;

    private static class AppItem {
        String packageName;
        String label;
    }

    private static class ViewHolder {
        CheckBox check;
        FrameLayout iconWrap;
        TextView avatar;
        ImageView icon;
        TextView name;
        TextView pkg;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        loadCurrentWhitelist();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setFitsSystemWindows(true);

        root.addView(buildHeader());
        root.addView(buildSearch());
        root.addView(buildToolbar());

        listView = new ListView(this);
        listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(dp(8));
        listView.setPadding(dp(16), dp(4), dp(16), dp(12));
        listView.setClipToPadding(false);
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        adapter = new AppAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visibleApps.size()) return;
            AppItem item = visibleApps.get(position);
            if (selected.contains(item.packageName)) selected.remove(item.packageName);
            else selected.add(item.packageName);
            adapter.notifyDataSetChanged();
            updateFooter();
        });
        root.addView(listView);

        root.addView(buildBottomBar());
        setContentView(root);

        loadAppsAsync();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.WHITE);
        header.setPadding(dp(12), dp(10) + getStatusBarHeight(), dp(16), dp(10));

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextColor(COLOR_SUB);
        cancel.setTextSize(15);
        cancel.setPadding(dp(8), dp(8), dp(16), dp(8));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        header.addView(cancel);

        TextView title = new TextView(this);
        title.setText("添加白名单");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(title);
        return header;
    }

    private View buildSearch() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setBackgroundColor(Color.WHITE);
        wrap.setPadding(dp(16), 0, dp(16), dp(10));

        searchInput = new EditText(this);
        searchInput.setHint("搜索应用名称或包名");
        searchInput.setHintTextColor(0xFFA8B0C2);
        searchInput.setTextColor(COLOR_TEXT);
        searchInput.setTextSize(14);
        searchInput.setSingleLine(true);
        searchInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        searchInput.setBackground(roundRect(COLOR_BG, 12, COLOR_LINE, 1));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString().trim();
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        wrap.addView(searchInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(6), dp(18), dp(2));

        countView = new TextView(this);
        countView.setTextColor(COLOR_SUB);
        countView.setTextSize(12);
        countView.setText("加载中…");
        countView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(countView);

        selectAllView = new TextView(this);
        selectAllView.setText("全选");
        selectAllView.setTextColor(COLOR_PRIMARY);
        selectAllView.setTextSize(13);
        selectAllView.setTypeface(null, Typeface.BOLD);
        selectAllView.setPadding(dp(10), dp(8), dp(4), dp(8));
        selectAllView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (visibleApps.isEmpty()) return;
                boolean allSelected = true;
                for (AppItem item : visibleApps) {
                    if (!selected.contains(item.packageName)) {
                        allSelected = false;
                        break;
                    }
                }
                for (AppItem item : visibleApps) {
                    if (allSelected) selected.remove(item.packageName);
                    else selected.add(item.packageName);
                }
                adapter.notifyDataSetChanged();
                updateFooter();
            }
        });
        bar.addView(selectAllView);
        return bar;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(dp(16), dp(10), dp(16), dp(14));

        submitView = new TextView(this);
        submitView.setText("加入所选");
        submitView.setTextColor(Color.WHITE);
        submitView.setTextSize(16);
        submitView.setTypeface(null, Typeface.BOLD);
        submitView.setGravity(Gravity.CENTER);
        submitView.setPadding(0, dp(13), 0, dp(13));
        submitView.setBackground(roundRect(0xFFB9C4D6, 14, 0, 0));
        submitView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSelected();
            }
        });
        bar.addView(submitView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private void loadAppsAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppItem> loaded = new ArrayList<>();
                try {
                    for (ApplicationInfo app : pm.getInstalledApplications(0)) {
                        if (app.packageName.equals(getPackageName())) continue;
                        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                                && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                            continue;
                        }
                        AppItem item = new AppItem();
                        item.packageName = app.packageName;
                        item.label = app.loadLabel(pm).toString();
                        loaded.add(item);
                    }
                    Collections.sort(loaded, new Comparator<AppItem>() {
                        @Override public int compare(AppItem a, AppItem b) {
                            return a.label.compareToIgnoreCase(b.label);
                        }
                    });
                } catch (Throwable ignored) {
                }
                if (loaded.isEmpty()) {
                    loaded.addAll(loadLauncherApps());
                    Collections.sort(loaded, new Comparator<AppItem>() {
                        @Override public int compare(AppItem a, AppItem b) {
                            return a.label.compareToIgnoreCase(b.label);
                        }
                    });
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        loadFinished = true;
                        allApps.clear();
                        allApps.addAll(loaded);
                        applyFilter();
                    }
                });
            }
        }, "Whitelist-App-Loader").start();
    }

    /**
     * 某些 ROM 在未授予读取应用列表权限时，getInstalledApplications 会返回空列表。
     * 用 launcher 查询兜底，至少让用户能搜索/选择桌面应用；权限恢复后自然回到完整列表。
     */
    private List<AppItem> loadLauncherApps() {
        List<AppItem> list = new ArrayList<>();
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(it, 0);
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : infos) {
                if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
                ApplicationInfo app = ri.activityInfo.applicationInfo;
                String pkg = app.packageName;
                if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    continue;
                }
                AppItem item = new AppItem();
                item.packageName = pkg;
                item.label = ri.loadLabel(pm).toString();
                list.add(item);
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    private void applyFilter() {
        visibleApps.clear();
        String q = query.toLowerCase(Locale.getDefault());
        for (AppItem item : allApps) {
            if (currentWhitelist.contains(item.packageName)) continue;
            if (q.isEmpty()
                    || item.label.toLowerCase(Locale.getDefault()).contains(q)
                    || item.packageName.toLowerCase(Locale.getDefault()).contains(q)) {
                visibleApps.add(item);
            }
        }
        adapter.notifyDataSetChanged();
        if (visibleApps.isEmpty() && allApps.isEmpty()) {
            countView.setText(loadFinished
                    ? "未读取到应用，请返回首页检查读取应用列表权限"
                    : "加载中…");
        }
        updateFooter();
    }

    private void updateFooter() {
        int total = visibleApps.size();
        if (allApps.isEmpty()) {
            countView.setText(loadFinished
                    ? "未读取到应用，请返回首页检查读取应用列表权限"
                    : "加载中…");
        } else {
            countView.setText("已选 " + selected.size() + " 个 / " + total + " 个应用");
        }
        boolean allSelected = total > 0;
        for (AppItem item : visibleApps) {
            if (!selected.contains(item.packageName)) {
                allSelected = false;
                break;
            }
        }
        selectAllView.setText(allSelected ? "取消全选" : "全选");

        submitView.setText(selected.isEmpty()
                ? "加入所选"
                : "加入所选（" + selected.size() + "）");
        submitView.setBackground(roundRect(
                selected.isEmpty() ? 0xFFB9C4D6 : COLOR_PRIMARY, 14, 0, 0));
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleApps.size(); }
        @Override public Object getItem(int position) { return visibleApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(WhitelistPickerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setBackground(roundRect(Color.WHITE, 12, COLOR_LINE, 1));

                h = new ViewHolder();
                h.check = new CheckBox(WhitelistPickerActivity.this);
                h.check.setClickable(false);
                h.check.setFocusable(false);
                row.addView(h.check);

                h.iconWrap = new FrameLayout(WhitelistPickerActivity.this);
                int s = dp(34);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
                ip.leftMargin = dp(4);
                ip.rightMargin = dp(10);
                h.iconWrap.setLayoutParams(ip);

                h.avatar = new TextView(WhitelistPickerActivity.this);
                h.avatar.setTextColor(Color.WHITE);
                h.avatar.setTextSize(14);
                h.avatar.setTypeface(null, Typeface.BOLD);
                h.avatar.setGravity(Gravity.CENTER);
                h.iconWrap.addView(h.avatar, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                h.icon = new ImageView(WhitelistPickerActivity.this);
                h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.icon.setVisibility(View.GONE);
                h.iconWrap.addView(h.icon, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                row.addView(h.iconWrap);

                LinearLayout textCol = new LinearLayout(WhitelistPickerActivity.this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                h.name = new TextView(WhitelistPickerActivity.this);
                h.name.setTextColor(COLOR_TEXT);
                h.name.setTextSize(14);
                h.name.setSingleLine(true);
                textCol.addView(h.name);

                h.pkg = new TextView(WhitelistPickerActivity.this);
                h.pkg.setTextColor(COLOR_SUB);
                h.pkg.setTextSize(11);
                h.pkg.setSingleLine(true);
                h.pkg.setPadding(0, dp(2), 0, 0);
                textCol.addView(h.pkg);
                row.addView(textCol);

                row.setTag(h);
                convertView = row;
            } else {
                h = (ViewHolder) convertView.getTag();
            }

            AppItem item = visibleApps.get(position);
            h.name.setText(item.label);
            h.pkg.setText(item.packageName);
            h.check.setChecked(selected.contains(item.packageName));
            String letter = item.label == null || item.label.isEmpty()
                    ? "?" : item.label.substring(0, 1).toUpperCase(Locale.getDefault());
            h.avatar.setText(letter);
            h.avatar.setBackground(roundRect(
                    avatarColor(Math.abs(item.packageName.hashCode()) % 8), 17, 0, 0));
            bindIcon(h, item.packageName);
            return convertView;
        }

        private void bindIcon(ViewHolder h, String pkg) {
            h.iconWrap.setTag(pkg);
            Drawable cached = iconCache.get(pkg);
            if (cached != null) {
                showIcon(h, cached);
                return;
            }
            h.avatar.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            if (!loadingIcons.add(pkg)) return;
            iconExecutor.execute(() -> {
                Drawable loaded = null;
                try {
                    loaded = pm.getApplicationIcon(pkg);
                } catch (Throwable ignored) {
                }
                if (loaded != null) iconCache.put(pkg, loaded);
                loadingIcons.remove(pkg);
                final Drawable result = loaded;
                runOnUiThread(() -> {
                    if (isFinishing() || result == null) return;
                    if (pkg.equals(h.iconWrap.getTag())) showIcon(h, result);
                });
            });
        }

        private void showIcon(ViewHolder h, Drawable icon) {
            h.icon.setImageDrawable(icon);
            h.icon.setVisibility(View.VISIBLE);
            h.avatar.setVisibility(View.GONE);
        }
    }

    private void loadCurrentWhitelist() {
        currentWhitelist.clear();
        String raw = prefs.getString("whitelist_pkgs", "");
        boolean removedSystem = false;
        if (!raw.isEmpty()) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (t.isEmpty()) continue;
                // 2026-11-02（用户需求）：系统应用不再自动进入白名单；
                // 旧版本留在 prefs 里的系统条目在这里直接清掉，避免再次写回。
                if (isSystemPackage(t)) {
                    removedSystem = true;
                    continue;
                }
                currentWhitelist.add(t);
            }
        }
        if (removedSystem) {
            saveCurrentWhitelistOnly();
            WhitelistActivity.syncToStellarAsync(this);
        }
    }

    /** 是否是系统应用；系统应用不进入用户白名单。 */
    private boolean isSystemPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pm == null) return false;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void saveCurrentWhitelistOnly() {
        StringBuilder sb = new StringBuilder();
        for (String p : currentWhitelist) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        prefs.edit().putString("whitelist_pkgs", sb.toString()).apply();
    }

    private void saveSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要加入的应用", Toast.LENGTH_SHORT).show();
            return;
        }
        // 再次过滤，确保旧版本残留的系统应用不会随保存写回。
        Set<String> all = new HashSet<>();
        for (String p : currentWhitelist) {
            if (!isSystemPackage(p)) all.add(p);
        }
        for (String p : selected) {
            if (!isSystemPackage(p)) all.add(p);
        }
        StringBuilder sb = new StringBuilder();
        for (String p : all) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        prefs.edit().putString("whitelist_pkgs", sb.toString()).apply();
        WhitelistActivity.syncToStellarAsync(this);
        Toast.makeText(this, "已加入 " + selected.size() + " 个应用", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        iconExecutor.shutdownNow();
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int avatarColor(int idx) {
        int[] colors = {
                0xFF1565C0, 0xFF2E7D32, 0xFFC62828, 0xFF6A1B9A,
                0xFFE65100, 0xFF00838F, 0xFF4E342E, 0xFF37474F
        };
        return colors[idx & 7];
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(radiusDp));
        d.setColor(color);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }
}
