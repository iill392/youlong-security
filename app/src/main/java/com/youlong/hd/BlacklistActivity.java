package com.youlong.hd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 管控应用管理（原黑名单管理）。
 * 用户将应用加入管控后，安全护盾将拦截这些应用。
 * 不显示系统应用。
 * "我都很信任" 按钮清空管控列表。
 */
public class BlacklistActivity extends Activity {

    private PackageManager pm;
    private SharedPreferences prefs;
    private Set<String> currentBlacklist = new HashSet<>();
    private List<AppItem> allApps = new ArrayList<>();
    private LinearLayout blacklistContainer;
    private LinearLayout emptyHint;
    private TextView trustAllBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pm = getPackageManager();
        prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        loadBlacklist();

        // ===== 根布局 =====
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F6FA);
        root.setFitsSystemWindows(true);

        // ------- 顶栏 -------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFFD32F2F);
        header.setPadding(24, dp(12) + getStatusBarHeight(), 24, dp(12));

        TextView backBtn = new TextView(this);
        backBtn.setText("\u2190");
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTextSize(22);
        backBtn.setPadding(0, 0, dp(16), 0);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        header.addView(backBtn);

        TextView title = new TextView(this);
        title.setText("管控应用的列表");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title);

        root.addView(header);

        // ------- 内容区 -------
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(40), dp(24), dp(40));

        // 图标
        TextView iconView = new TextView(this);
        iconView.setText("\u26A0\uFE0F");
        iconView.setTextSize(40);
        iconView.setGravity(Gravity.CENTER);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(0xFFFFEBEE);
        int iconSize = dp(80);
        iconBg.setSize(iconSize, iconSize);
        iconView.setBackground(iconBg);
        iconView.setMinWidth(iconSize);
        iconView.setMinHeight(iconSize);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize);
        ip.gravity = Gravity.CENTER_HORIZONTAL;
        iconView.setLayoutParams(ip);
        content.addView(iconView);

        // 加入管控按钮
        Button addBtn = new Button(this);
        addBtn.setText("添加要管控的应用");
        addBtn.setTextColor(Color.WHITE);
        addBtn.setTextSize(17);
        addBtn.setTypeface(null, Typeface.BOLD);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setShape(GradientDrawable.RECTANGLE);
        btnBg.setCornerRadius(dp(28));
        btnBg.setColor(0xFF1565C0);
        addBtn.setBackground(btnBg);
        addBtn.setPadding(dp(48), dp(14), dp(48), dp(14));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(24);
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        addBtn.setLayoutParams(bp);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAppPicker();
            }
        });
        content.addView(addBtn);

        // "我都很信任" 按钮 — 清空全部管控
        trustAllBtn = new TextView(this);
        trustAllBtn.setText("我都很信任");
        trustAllBtn.setTextColor(0xFF666666);
        trustAllBtn.setTextSize(14);
        trustAllBtn.setGravity(Gravity.CENTER);
        trustAllBtn.setPadding(0, dp(16), 0, dp(8));
        trustAllBtn.setTypeface(null, Typeface.BOLD);
        trustAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                trustAll();
            }
        });
        content.addView(trustAllBtn);

        // 说明文字
        TextView hint = new TextView(this);
        hint.setText("管控中的应用将启动安全护盾拦截\n不显示系统应用");
        hint.setTextColor(0xFF999999);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(4), 0, dp(24));
        content.addView(hint);

        // 空状态提示
        emptyHint = new LinearLayout(this);
        emptyHint.setOrientation(LinearLayout.VERTICAL);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(0, dp(20), 0, 0);
        TextView emptyText = new TextView(this);
        emptyText.setText("暂无管控应用\n所有应用都不会被拦截");
        emptyText.setTextColor(0xFFBBBBBB);
        emptyText.setTextSize(15);
        emptyText.setGravity(Gravity.CENTER);
        emptyHint.addView(emptyText);
        content.addView(emptyHint);

        // 已加入管控列表容器
        blacklistContainer = new LinearLayout(this);
        blacklistContainer.setOrientation(LinearLayout.VERTICAL);
        blacklistContainer.setPadding(0, dp(16), 0, 0);
        content.addView(blacklistContainer);

        scroll.addView(content);
        root.addView(scroll);

        setContentView(root);

        loadApps();
        refreshBlacklistUI();
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp(int px) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (px * d + 0.5f);
    }

    private void loadBlacklist() {
        currentBlacklist.clear();
        String raw = prefs.getString("blacklist_pkgs", "");
        if (!raw.isEmpty()) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) currentBlacklist.add(t);
            }
        }
    }

    private void saveBlacklist() {
        StringBuilder sb = new StringBuilder();
        for (String p : currentBlacklist) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        prefs.edit().putString("blacklist_pkgs", sb.toString()).apply();
    }

    private static class AppItem {
        String packageName;
        String label;
    }

    /** 判断是否为系统应用 */
    private boolean isSystemApp(ApplicationInfo app) {
        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return true;
        if ((app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true;
        if (app.sourceDir != null && app.sourceDir.startsWith("/system/")) return true;
        return false;
    }

    private void loadApps() {
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        allApps.clear();
        for (ApplicationInfo app : installed) {
            // 过滤掉自身和系统应用
            if (app.packageName.equals(getPackageName())) continue;
            if (isSystemApp(app)) continue;

            AppItem item = new AppItem();
            item.packageName = app.packageName;
            item.label = app.loadLabel(pm).toString();
            allApps.add(item);
        }
        Collections.sort(allApps, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
    }

    private void showAppPicker() {
        final List<AppItem> candidates = new ArrayList<>();
        for (AppItem a : allApps) {
            if (!currentBlacklist.contains(a.packageName)) {
                candidates.add(a);
            }
        }

        if (candidates.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("所有已安装应用都已在管控中")
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }

        final String[] names = new String[candidates.size()];
        final boolean[] checked = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).label + "\n" + candidates.get(i).packageName;
            checked[i] = false;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择要加入管控的应用（可多选）")
                .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("确定加入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        for (int i = 0; i < candidates.size(); i++) {
                            if (checked[i]) {
                                currentBlacklist.add(candidates.get(i).packageName);
                            }
                        }
                        saveBlacklist();
                        refreshBlacklistUI();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** "我都很信任" — 清空所有管控 */
    private void trustAll() {
        if (currentBlacklist.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("当前没有管控应用")
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("确认清空")
                .setMessage("确定要清空所有管控吗？\n所有应用将不再被拦截。")
                .setPositiveButton("确定清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        currentBlacklist.clear();
                        saveBlacklist();
                        refreshBlacklistUI();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshBlacklistUI() {
        blacklistContainer.removeAllViews();

        // 更新信任按钮文字
        if (trustAllBtn != null) {
            trustAllBtn.setText("我都很信任" + (currentBlacklist.isEmpty() ? "" : "（清空" + currentBlacklist.size() + "个管控）"));
        }

        List<AppItem> blacklisted = new ArrayList<>();
        for (String pkg : currentBlacklist) {
            AppItem found = null;
            for (AppItem a : allApps) {
                if (a.packageName.equals(pkg)) { found = a; break; }
            }
            if (found == null) {
                found = new AppItem();
                found.packageName = pkg;
                found.label = pkg;
            }
            blacklisted.add(found);
        }
        Collections.sort(blacklisted, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        if (blacklisted.isEmpty()) {
            emptyHint.setVisibility(View.VISIBLE);
            blacklistContainer.setVisibility(View.GONE);
            return;
        }

        emptyHint.setVisibility(View.GONE);
        blacklistContainer.setVisibility(View.VISIBLE);

        TextView listTitle = new TextView(this);
        listTitle.setText("已加入管控 (" + blacklisted.size() + ")");
        listTitle.setTextColor(0xFF333333);
        listTitle.setTextSize(14);
        listTitle.setTypeface(null, Typeface.BOLD);
        listTitle.setPadding(0, 0, 0, dp(8));
        blacklistContainer.addView(listTitle);

        for (final AppItem item : blacklisted) {
            blacklistContainer.addView(buildBlacklistRow(item));
        }
    }

    private View buildBlacklistRow(final AppItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundColor(Color.WHITE);
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setShape(GradientDrawable.RECTANGLE);
        rowBg.setCornerRadius(dp(12));
        rowBg.setColor(Color.WHITE);
        rowBg.setStroke(dp(1), 0xFFE53935);
        row.setBackground(rowBg);

        TextView avatar = new TextView(this);
        String letter = item.label.isEmpty() ? "?" : item.label.substring(0, 1).toUpperCase();
        avatar.setText(letter);
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(15);
        avatar.setTypeface(null, Typeface.BOLD);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        int c = avatarColor(Math.abs(item.packageName.hashCode()) % 8);
        avatarBg.setColor(c);
        int size = dp(38);
        avatar.setBackground(avatarBg);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(size, size);
        ap.rightMargin = dp(12);
        avatar.setLayoutParams(ap);
        row.addView(avatar);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textCol.setLayoutParams(tp);

        TextView nameTv = new TextView(this);
        nameTv.setText(item.label);
        nameTv.setTextColor(0xFF222222);
        nameTv.setTextSize(14);
        nameTv.setSingleLine(true);
        textCol.addView(nameTv);

        TextView pkgTv = new TextView(this);
        pkgTv.setText(item.packageName);
        pkgTv.setTextColor(0xFFAAAAAA);
        pkgTv.setTextSize(11);
        pkgTv.setSingleLine(true);
        textCol.addView(pkgTv);

        row.addView(textCol);

        // 移除按钮
        Button removeBtn = new Button(this);
        removeBtn.setText("移出");
        removeBtn.setTextColor(0xFF2E7D32);
        removeBtn.setTextSize(13);
        GradientDrawable rbBg = new GradientDrawable();
        rbBg.setShape(GradientDrawable.RECTANGLE);
        rbBg.setCornerRadius(dp(16));
        rbBg.setColor(0xFFE8F5E9);
        removeBtn.setBackground(rbBg);
        removeBtn.setPadding(dp(14), dp(6), dp(14), dp(6));
        removeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentBlacklist.remove(item.packageName);
                saveBlacklist();
                refreshBlacklistUI();
            }
        });
        row.addView(removeBtn);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(8);
        row.setLayoutParams(rp);

        return row;
    }

    private int avatarColor(int idx) {
        int[] colors = {
                0xFFD32F2F, 0xFFC62828, 0xFFB71C1C, 0xFFE53935,
                0xFF6A1B9A, 0xFFE65100, 0xFF4E342E, 0xFF37474F
        };
        return colors[idx];
    }
}
