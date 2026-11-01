package com.youlong.hd;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import java.util.Set;

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

    private ListView listView;
    private AppAdapter adapter;
    private TextView countView;
    private TextView selectAllView;
    private TextView submitView;
    private EditText searchInput;
    private String query = "";

    private static class AppItem {
        String packageName;
        String label;
        Drawable icon;
    }

    private static class ViewHolder {
        CheckBox check;
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
                        try {
                            item.icon = app.loadIcon(pm);
                        } catch (Throwable ignored) {
                            item.icon = null;
                        }
                        loaded.add(item);
                    }
                    Collections.sort(loaded, new Comparator<AppItem>() {
                        @Override public int compare(AppItem a, AppItem b) {
                            return a.label.compareToIgnoreCase(b.label);
                        }
                    });
                } catch (Throwable ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        allApps.clear();
                        allApps.addAll(loaded);
                        applyFilter();
                    }
                });
            }
        }, "Whitelist-App-Loader").start();
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
            countView.setText("加载中…");
        }
        updateFooter();
    }

    private void updateFooter() {
        int total = visibleApps.size();
        countView.setText(allApps.isEmpty() ? "加载中…" : ("已选 " + selected.size() + " 个 / " + total + " 个应用"));
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

                h.icon = new ImageView(WhitelistPickerActivity.this);
                h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int s = dp(34);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
                ip.leftMargin = dp(4);
                ip.rightMargin = dp(10);
                h.icon.setLayoutParams(ip);
                row.addView(h.icon);

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
            if (item.icon != null) {
                h.icon.setVisibility(View.VISIBLE);
                h.icon.setImageDrawable(item.icon);
            } else {
                h.icon.setVisibility(View.GONE);
            }
            return convertView;
        }
    }

    private void loadCurrentWhitelist() {
        currentWhitelist.clear();
        String raw = prefs.getString("whitelist_pkgs", "");
        if (!raw.isEmpty()) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) currentWhitelist.add(t);
            }
        }
    }

    private void saveSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要加入的应用", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> all = new HashSet<>(currentWhitelist);
        all.addAll(selected);
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

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
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
