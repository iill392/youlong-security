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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 白名单管理。
 * 进页面只显示"加入白名单"按钮。点击弹出应用列表让用户多选。
 * 已加入的应用显示在下方，可移除（内置信任应用除外）。
 *
 * <p>本类同时提供**全应用统一的白名单判定**：
 * {@link #isWhitelisted(Context, String)} 按条目精确匹配，且同时覆盖
 * 内置默认信任包（{@link #DEFAULT_TRUSTED_PKGS}）、历史版本遗留的硬编码信任包
 * （{@link #LEGACY_TRUSTED_PKGS}）与用户自定义白名单。
 * 各拦截/卸载入口请一律调用它，避免出现"某一处漏检查→白名单应用仍被拦截/卸载"
 * 的不一致（历史上 ShieldWarnActivity 只读原始串、漏掉内置信任包，导致微信等
 * 内置信任应用在音量键救援 7 秒倒计时后仍被拉起系统卸载框）。
 */
public class WhitelistActivity extends Activity {

    // ===== 内置默认信任应用（不可移除，防锁机不会拦截） =====
    public static final java.util.Set<String> DEFAULT_TRUSTED_PKGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.eg.android.AlipayGphone",
            "com.tencent.mm",
            "com.xunmeng.pinduoduo",
            "com.youlong.zoo",
            "com.tencent.mobileqq"
    ));

    /**
     * 历史版本硬编码跳过的包名（统一并入白名单判定，与白名单管理页语义一致）。
     * 原先只写死在 ProtectService.loadWhitelist() 里，其他组件读不到。
     */
    public static final java.util.Set<String> LEGACY_TRUSTED_PKGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.larus.nova",
            "com.smile.gifmaker"
    ));

    /**
     * 统一的白名单判定（各拦截/卸载入口共用，按条目精确匹配）。
     *
     * <p>覆盖：内置默认信任包 + 历史遗留信任包 + 用户在白名单管理页/拦截弹窗里
     * 添加的自定义白名单。匹配规则为「按逗号拆条、逐条 trim 后 equals」，
     * 不做子串匹配——子串匹配会把 "com.a.b" 误判成已在白名单（如已存在 "com.a.bb"），
     * 导致"信任此应用"静默失败。
     */
    public static boolean isWhitelisted(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return false;
        if (DEFAULT_TRUSTED_PKGS.contains(pkg) || LEGACY_TRUSTED_PKGS.contains(pkg)) return true;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE);
            String raw = prefs.getString("whitelist_pkgs", "");
            if (raw == null || raw.isEmpty()) return false;
            for (String p : raw.split(",")) {
                if (pkg.equals(p.trim())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private PackageManager pm;
    private SharedPreferences prefs;
    private Set<String> currentWhitelist = new HashSet<>();
    private List<AppItem> allApps = new ArrayList<>();
    private LinearLayout whitelistContainer;
    private LinearLayout emptyHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pm = getPackageManager();
        prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        loadWhitelist();

        // ===== 根布局 =====
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F6FA);
        root.setFitsSystemWindows(true);

        // ------- 顶栏 -------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF1565C0);
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
        title.setText("白名单管理");
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
        iconView.setText("+");
        iconView.setTextColor(0xFF1565C0);
        iconView.setTextSize(48);
        iconView.setTypeface(null, Typeface.BOLD);
        iconView.setGravity(Gravity.CENTER);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(0xFFE3F2FD);
        int iconSize = dp(80);
        iconBg.setSize(iconSize, iconSize);
        iconView.setBackground(iconBg);
        iconView.setMinWidth(iconSize);
        iconView.setMinHeight(iconSize);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize);
        ip.gravity = Gravity.CENTER_HORIZONTAL;
        iconView.setLayoutParams(ip);
        content.addView(iconView);

        // 加入按钮
        Button addBtn = new Button(this);
        addBtn.setText("\u52A0\u5165\u767D\u540D\u5355");
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

        // 说明文字
        TextView hint = new TextView(this);
        hint.setText("\u767D\u540D\u5355\u4E2D\u7684\u5E94\u7528\u5C06\u4E0D\u4F1A\u88AB\u5B89\u5168\u62A4\u76FE\u62E6\u622A");
        hint.setTextColor(0xFF999999);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, dp(24));
        content.addView(hint);

        // 空状态提示
        emptyHint = new LinearLayout(this);
        emptyHint.setOrientation(LinearLayout.VERTICAL);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(0, dp(20), 0, 0);
        TextView emptyText = new TextView(this);
        emptyText.setText("\u6682\u65E0\u767D\u540D\u5355\u5E94\u7528");
        emptyText.setTextColor(0xFFBBBBBB);
        emptyText.setTextSize(15);
        emptyText.setGravity(Gravity.CENTER);
        emptyHint.addView(emptyText);
        content.addView(emptyHint);

        // 已加入白名单列表容器
        whitelistContainer = new LinearLayout(this);
        whitelistContainer.setOrientation(LinearLayout.VERTICAL);
        whitelistContainer.setPadding(0, dp(16), 0, 0);
        content.addView(whitelistContainer);

        scroll.addView(content);
        root.addView(scroll);

        setContentView(root);

        loadApps();
        refreshWhitelistUI();
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp(int px) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (px * d + 0.5f);
    }

    private void loadWhitelist() {
        currentWhitelist.clear();
        // 加入内置默认白名单
        currentWhitelist.addAll(DEFAULT_TRUSTED_PKGS);
        // 加入用户自定义白名单
        String raw = prefs.getString("whitelist_pkgs", "");
        if (!raw.isEmpty()) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) currentWhitelist.add(t);
            }
        }
    }

    private void saveWhitelist() {
        StringBuilder sb = new StringBuilder();
        for (String p : currentWhitelist) {
            // 不保存内置默认白名单
            if (DEFAULT_TRUSTED_PKGS.contains(p)) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        prefs.edit().putString("whitelist_pkgs", sb.toString()).apply();
    }

    private static class AppItem {
        String packageName;
        String label;
    }

    private void loadApps() {
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        allApps.clear();
        for (ApplicationInfo app : installed) {
            if (app.packageName.equals(getPackageName())) continue;
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
            if (!currentWhitelist.contains(a.packageName)) {
                candidates.add(a);
            }
        }

        if (candidates.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("\u63D0\u793A")
                    .setMessage("\u6240\u6709\u5DF2\u5B89\u88C5\u5E94\u7528\u90FD\u5DF2\u5728\u767D\u540D\u5355\u4E2D")
                    .setPositiveButton("\u786E\u5B9A", null)
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
                .setTitle("\u9009\u62E9\u8981\u52A0\u5165\u767D\u540D\u5355\u7684\u5E94\u7528\uFF08\u53EF\u591A\u9009\uFF09")
                .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("\u786E\u5B9A\u52A0\u5165", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        for (int i = 0; i < candidates.size(); i++) {
                            if (checked[i]) {
                                currentWhitelist.add(candidates.get(i).packageName);
                            }
                        }
                        saveWhitelist();
                        refreshWhitelistUI();
                    }
                })
                .setNegativeButton("\u53D6\u6D88", null)
                .show();
    }

    private void refreshWhitelistUI() {
        whitelistContainer.removeAllViews();

        List<AppItem> whitelisted = new ArrayList<>();
        for (String pkg : currentWhitelist) {
            AppItem found = null;
            for (AppItem a : allApps) {
                if (a.packageName.equals(pkg)) { found = a; break; }
            }
            if (found == null) {
                found = new AppItem();
                found.packageName = pkg;
                found.label = pkg;
            }
            whitelisted.add(found);
        }
        Collections.sort(whitelisted, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        if (whitelisted.isEmpty()) {
            emptyHint.setVisibility(View.VISIBLE);
            whitelistContainer.setVisibility(View.GONE);
            return;
        }

        emptyHint.setVisibility(View.GONE);
        whitelistContainer.setVisibility(View.VISIBLE);

        TextView listTitle = new TextView(this);
        listTitle.setText("\u5DF2\u52A0\u5165\u767D\u540D\u5355 (" + whitelisted.size() + ")");
        listTitle.setTextColor(0xFF333333);
        listTitle.setTextSize(14);
        listTitle.setTypeface(null, Typeface.BOLD);
        listTitle.setPadding(0, 0, 0, dp(8));
        whitelistContainer.addView(listTitle);

        for (final AppItem item : whitelisted) {
            whitelistContainer.addView(buildWhitelistRow(item));
        }
    }

    private View buildWhitelistRow(final AppItem item) {
        final boolean isDefault = DEFAULT_TRUSTED_PKGS.contains(item.packageName);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundColor(Color.WHITE);
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setShape(GradientDrawable.RECTANGLE);
        rowBg.setCornerRadius(dp(12));
        rowBg.setColor(Color.WHITE);
        if (isDefault) {
            rowBg.setStroke(dp(1), 0xFF42A5F5);
        }
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
        int c = isDefault ? 0xFF42A5F5 : avatarColor(Math.abs(item.packageName.hashCode()) % 8);
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

        // 内置信任标记
        if (isDefault) {
            TextView badgeTv = new TextView(this);
            badgeTv.setText("\u7CFB\u7EDF\u4FE1\u4EFB");
            badgeTv.setTextColor(0xFF42A5F5);
            badgeTv.setTextSize(10);
            badgeTv.setTypeface(null, Typeface.BOLD);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.RECTANGLE);
            badgeBg.setCornerRadius(dp(8));
            badgeBg.setColor(0xFFE3F2FD);
            badgeTv.setBackground(badgeBg);
            badgeTv.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.topMargin = dp(3);
            badgeTv.setLayoutParams(badgeLp);
            textCol.addView(badgeTv);
        }

        row.addView(textCol);

        // 内置白名单不显示移除按钮
        if (!isDefault) {
            Button removeBtn = new Button(this);
            removeBtn.setText("\u79FB\u9664");
            removeBtn.setTextColor(0xFFE53935);
            removeBtn.setTextSize(13);
            GradientDrawable rbBg = new GradientDrawable();
            rbBg.setShape(GradientDrawable.RECTANGLE);
            rbBg.setCornerRadius(dp(16));
            rbBg.setColor(0xFFFFEBEE);
            removeBtn.setBackground(rbBg);
            removeBtn.setPadding(dp(14), dp(6), dp(14), dp(6));
            removeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentWhitelist.remove(item.packageName);
                    saveWhitelist();
                    refreshWhitelistUI();
                }
            });
            row.addView(removeBtn);
        }

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(8);
        row.setLayoutParams(rp);

        return row;
    }

    private int avatarColor(int idx) {
        int[] colors = {
                0xFF1565C0, 0xFF2E7D32, 0xFFC62828, 0xFF6A1B9A,
                0xFFE65100, 0xFF00838F, 0xFF4E342E, 0xFF37474F
        };
        return colors[idx];
    }
}
