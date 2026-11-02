package com.youlong.hd;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 白名单管理（2026-10-30 重做）。
 *
 * 需求：
 *   · 去掉所有内置/自动加入的信任应用；
 *   · 已加入列表支持搜索；
 *   · 加入应用的选择器支持搜索、全选/取消全选；
 *   · 页面改为卡片式，减少「一层套一层」的旧样式。
 */
public class WhitelistActivity extends Activity {

    /** 2026-10-30：按用户要求清空内置信任应用。 */
    public static final Set<String> DEFAULT_TRUSTED_PKGS = Collections.emptySet();

    private static final int COLOR_BG = 0xFFF4F6FB;
    private static final int COLOR_PRIMARY = 0xFF1565C0;
    private static final int COLOR_PRIMARY_DARK = 0xFF0B3D91;
    private static final int COLOR_TEXT = 0xFF172033;
    private static final int COLOR_SUB = 0xFF7B8499;
    private static final int COLOR_LINE = 0xFFE6EAF2;

    private PackageManager pm;
    private SharedPreferences prefs;
    private final Set<String> currentWhitelist = new HashSet<>();
    /** 只缓存白名单里的应用名/图标，绝不为整个应用列表加载。 */
    private final Map<String, String> labelCache = new HashMap<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();

    private LinearLayout whitelistContainer;
    private LinearLayout emptyHint;
    private TextView emptyTitle;
    private TextView emptySub;
    private TextView countText;
    private TextView sectionTitle;
    private EditText searchInput;
    private String query = "";

    private static class AppItem {
        String packageName;
        String label;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pm = getPackageManager();
        prefs = getSharedPreferences("shield_prefs", MODE_PRIVATE);
        loadWhitelist();
        syncToStellarAsync(this);

        getWindow().setStatusBarColor(COLOR_PRIMARY_DARK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setFitsSystemWindows(true);

        root.addView(buildHeader());
        root.addView(buildContent());

        setContentView(root);

        refreshWhitelistUI();
        loadLabelsAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从全屏选择页返回后只刷新白名单本身；不再重新扫描全部应用。
        if (pm != null) {
            loadWhitelist();
            refreshWhitelistUI();
            loadLabelsAsync();
        }
    }

    /** 后台只加载白名单内的应用名称；数量很小，不进主线程。 */
    private void loadLabelsAsync() {
        final List<String> pkgs = new ArrayList<>(currentWhitelist);
        if (pkgs.isEmpty()) return;
        new Thread(() -> {
            final Map<String, String> loadedLabels = new HashMap<>();
            final Map<String, Drawable> loadedIcons = new HashMap<>();
            for (String pkg : pkgs) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    loadedLabels.put(pkg, ai.loadLabel(pm).toString());
                    try {
                        loadedIcons.put(pkg, ai.loadIcon(pm));
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable ignored) {
                }
            }
            runOnUiThread(() -> {
                if (isFinishing()) return;
                labelCache.putAll(loadedLabels);
                iconCache.putAll(loadedIcons);
                refreshWhitelistUI();
            });
        }, "Whitelist-Labels").start();
    }

    // ============================================================
    // 顶栏
    // ============================================================
    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(12) + getStatusBarHeight(), dp(18), dp(14));

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{COLOR_PRIMARY, COLOR_PRIMARY}
        );
        header.setBackground(bg);

        TextView back = new TextView(this);
        back.setText("\u2190");
        back.setTextColor(Color.WHITE);
        back.setTextSize(23);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, dp(14), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView title = new TextView(this);
        title.setText("白名单管理");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        titleCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText("只放行你真正信任的应用");
        sub.setTextColor(0xCCFFFFFF);
        sub.setTextSize(12);
        sub.setPadding(0, dp(2), 0, 0);
        titleCol.addView(sub);
        header.addView(titleCol);

        TextView add = new TextView(this);
        add.setText("＋ 添加");
        add.setTextColor(Color.WHITE);
        add.setTextSize(14);
        add.setTypeface(null, Typeface.BOLD);
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable addBg = new GradientDrawable();
        addBg.setShape(GradientDrawable.RECTANGLE);
        addBg.setCornerRadius(dp(22));
        addBg.setColor(0x33FFFFFF);
        addBg.setStroke(dp(1), 0x66FFFFFF);
        add.setBackground(addBg);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAppPicker();
            }
        });
        header.addView(add);

        return header;
    }

    // ============================================================
    // 内容
    // ============================================================
    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(28));

        // 概览卡片
        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setPadding(dp(18), dp(16), dp(18), dp(16));
        summary.setBackground(roundRect(Color.WHITE, 18, COLOR_LINE, 1));
        summary.setElevation(dp(1));

        LinearLayout summaryTop = new LinearLayout(this);
        summaryTop.setOrientation(LinearLayout.HORIZONTAL);
        summaryTop.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout countCol = new LinearLayout(this);
        countCol.setOrientation(LinearLayout.VERTICAL);
        countCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        countText = new TextView(this);
        countText.setText("0");
        countText.setTextColor(COLOR_PRIMARY);
        countText.setTextSize(24);
        countText.setTypeface(null, Typeface.BOLD);
        countCol.addView(countText);

        TextView countLabel = new TextView(this);
        countLabel.setText("个应用已加入白名单");
        countLabel.setTextColor(COLOR_TEXT);
        countLabel.setTextSize(14);
        countCol.addView(countLabel);
        summaryTop.addView(countCol);
        summary.addView(summaryTop);

        TextView summaryDesc = new TextView(this);
        summaryDesc.setText("白名单内的应用不会被拦截、冻结或终结");
        summaryDesc.setTextColor(COLOR_SUB);
        summaryDesc.setTextSize(12);
        summaryDesc.setPadding(0, dp(9), 0, 0);
        summary.addView(summaryDesc);
        content.addView(summary);

        // 搜索框
        searchInput = new EditText(this);
        searchInput.setHint("搜索已加入的应用名称或包名");
        searchInput.setHintTextColor(0xFFA8B0C2);
        searchInput.setTextColor(COLOR_TEXT);
        searchInput.setTextSize(14);
        searchInput.setSingleLine(true);
        searchInput.setPadding(dp(16), dp(12), dp(16), dp(12));
        searchInput.setBackground(roundRect(Color.WHITE, 14, COLOR_LINE, 1));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString().trim();
                refreshWhitelistUI();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.topMargin = dp(14);
        searchInput.setLayoutParams(searchLp);
        content.addView(searchInput);

        // 列表区标题
        LinearLayout sectionHeader = new LinearLayout(this);
        sectionHeader.setOrientation(LinearLayout.HORIZONTAL);
        sectionHeader.setGravity(Gravity.CENTER_VERTICAL);
        sectionHeader.setPadding(dp(2), dp(20), dp(2), dp(8));

        sectionTitle = new TextView(this);
        sectionTitle.setText("已加入白名单");
        sectionTitle.setTextColor(COLOR_TEXT);
        sectionTitle.setTextSize(15);
        sectionTitle.setTypeface(null, Typeface.BOLD);
        sectionTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sectionHeader.addView(sectionTitle);

        TextView tip = new TextView(this);
        tip.setText("点击右侧可移除");
        tip.setTextColor(COLOR_SUB);
        tip.setTextSize(11);
        sectionHeader.addView(tip);
        content.addView(sectionHeader);

        // 空状态
        emptyHint = new LinearLayout(this);
        emptyHint.setOrientation(LinearLayout.VERTICAL);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(dp(20), dp(44), dp(20), dp(44));
        emptyHint.setBackground(roundRect(Color.WHITE, 18, COLOR_LINE, 1));

        TextView emptyIcon = new TextView(this);
        emptyIcon.setText("—");
        emptyIcon.setTextColor(COLOR_SUB);
        emptyIcon.setTextSize(22);
        emptyIcon.setGravity(Gravity.CENTER);
        emptyHint.addView(emptyIcon);

        emptyTitle = new TextView(this);
        emptyTitle.setText("还没有加入白名单应用");
        emptyTitle.setTextColor(COLOR_TEXT);
        emptyTitle.setTextSize(15);
        emptyTitle.setTypeface(null, Typeface.BOLD);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(10), 0, 0);
        emptyHint.addView(emptyTitle);

        emptySub = new TextView(this);
        emptySub.setText("点击右上角「添加」，可搜索并多选应用");
        emptySub.setTextColor(COLOR_SUB);
        emptySub.setTextSize(12);
        emptySub.setGravity(Gravity.CENTER);
        emptySub.setPadding(0, dp(6), 0, 0);
        emptyHint.addView(emptySub);
        content.addView(emptyHint);

        // 白名单列表
        whitelistContainer = new LinearLayout(this);
        whitelistContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(whitelistContainer);

        scroll.addView(content);
        return scroll;
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

    // ============================================================
    // 数据
    // ============================================================
    private void loadWhitelist() {
        currentWhitelist.clear();
        // 2026-11-02（用户需求）：彻底删除“自动把系统应用加入白名单”功能。
        // 旧版本已经写入的系统应用在这里被过滤并清理，不再保留。
        String raw = prefs.getString("whitelist_pkgs", "");
        boolean removedSystem = false;
        if (!raw.isEmpty()) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (t.isEmpty()) continue;
                if (isSystemPackage(t)) {
                    removedSystem = true;
                    continue;
                }
                currentWhitelist.add(t);
            }
        }
        if (removedSystem) {
            saveWhitelist();
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

    private void saveWhitelist() {
        StringBuilder sb = new StringBuilder();
        for (String p : currentWhitelist) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        prefs.edit().putString("whitelist_pkgs", sb.toString()).apply();
        syncToStellarAsync(this);
    }

    /**
     * 把白名单同步到内置特权「指令拦截白名单」。
     * 2026-10-30：不再加入任何内置信任应用。
     */
    public static void syncToStellarAsync(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    syncToStellar(app);
                } catch (Throwable tr) {
                    android.util.Log.w("Whitelist", "同步指令白名单失败", tr);
                }
            }
        }, "Whitelist-Sync").start();
    }

    /** 同步到服务端；必须在后台线程调用。 */
    public static boolean syncToStellar(Context context) {
        SharedPreferences p = context.getSharedPreferences("shield_prefs", MODE_PRIVATE);
        Set<String> all = new java.util.LinkedHashSet<>();
        String raw = p.getString("whitelist_pkgs", "");
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) all.add(t);
            }
        }

        StringBuilder body = new StringBuilder();
        for (String pkg : all) {
            if (!pkg.matches("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")) continue;
            if (body.length() > 0) body.append('\n');
            body.append(pkg);
        }
        String content = body.toString();

        context.getSharedPreferences("stellar_command_whitelist", MODE_PRIVATE)
                .edit()
                .putString("pkgs", content.replace('\n', ','))
                .apply();

        return StellarUtils.writeCommandWhitelist(content);
    }

    // ============================================================
    // 已加入列表
    // ============================================================
    private void refreshWhitelistUI() {
        View focused = getCurrentFocus();
        whitelistContainer.removeAllViews();

        List<AppItem> all = new ArrayList<>();
        for (String pkg : currentWhitelist) {
            AppItem item = new AppItem();
            item.packageName = pkg;
            String cached = labelCache.get(pkg);
            item.label = cached != null ? cached : pkg;
            all.add(item);
        }
        Collections.sort(all, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem a, AppItem b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        List<AppItem> visible = new ArrayList<>();
        String q = query.toLowerCase(Locale.getDefault());
        for (AppItem a : all) {
            if (q.isEmpty()
                    || a.label.toLowerCase(Locale.getDefault()).contains(q)
                    || a.packageName.toLowerCase(Locale.getDefault()).contains(q)) {
                visible.add(a);
            }
        }

        countText.setText(String.valueOf(currentWhitelist.size()));
        sectionTitle.setText("已加入白名单 (" + currentWhitelist.size() + ")");

        if (visible.isEmpty()) {
            emptyHint.setVisibility(View.VISIBLE);
            whitelistContainer.setVisibility(View.GONE);
            if (currentWhitelist.isEmpty()) {
                emptyTitle.setText("还没有加入白名单应用");
                emptySub.setText("点击右上角「添加」，可搜索并多选应用");
            } else {
                emptyTitle.setText("没有匹配的应用");
                emptySub.setText("换个关键词试试");
            }
        } else {
            emptyHint.setVisibility(View.GONE);
            whitelistContainer.setVisibility(View.VISIBLE);
            for (AppItem item : visible) {
                whitelistContainer.addView(buildWhitelistRow(item));
            }
        }

        if (focused == searchInput) {
            searchInput.requestFocus();
        }
    }

    private View buildWhitelistRow(final AppItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(roundRect(Color.WHITE, 16, COLOR_LINE, 1));
        row.setElevation(dp(1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(9);
        row.setLayoutParams(rp);

        FrameLayout iconWrap = new FrameLayout(this);
        int avatarSize = dp(42);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        ip.rightMargin = dp(12);
        iconWrap.setLayoutParams(ip);

        TextView avatar = new TextView(this);
        String letter = item.label == null || item.label.isEmpty()
                ? "?" : item.label.substring(0, 1).toUpperCase(Locale.getDefault());
        avatar.setText(letter);
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(16);
        avatar.setTypeface(null, Typeface.BOLD);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(avatarColor(Math.abs(item.packageName.hashCode()) % 8));
        avatar.setBackground(avatarBg);
        iconWrap.addView(avatar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setVisibility(View.GONE);
        iconWrap.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Drawable cachedIcon = iconCache.get(item.packageName);
        if (cachedIcon != null) {
            icon.setImageDrawable(cachedIcon);
            icon.setVisibility(View.VISIBLE);
            avatar.setVisibility(View.GONE);
        }
        row.addView(iconWrap);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView name = new TextView(this);
        name.setText(item.label);
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(15);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        textCol.addView(name);

        TextView pkg = new TextView(this);
        pkg.setText(item.packageName);
        pkg.setTextColor(COLOR_SUB);
        pkg.setTextSize(11);
        pkg.setSingleLine(true);
        pkg.setPadding(0, dp(2), 0, 0);
        textCol.addView(pkg);
        row.addView(textCol);

        TextView remove = new TextView(this);
        remove.setText("移除");
        remove.setTextColor(0xFFE53935);
        remove.setTextSize(12);
        remove.setTypeface(null, Typeface.BOLD);
        remove.setGravity(Gravity.CENTER);
        remove.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable removeBg = new GradientDrawable();
        removeBg.setShape(GradientDrawable.RECTANGLE);
        removeBg.setCornerRadius(dp(18));
        removeBg.setColor(0xFFFFEBEE);
        remove.setBackground(removeBg);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentWhitelist.remove(item.packageName);
                saveWhitelist();
                refreshWhitelistUI();
            }
        });
        row.addView(remove);

        return row;
    }

    // ============================================================
    // 应用选择器：搜索 + 全选
    // ============================================================
    /** 2026-10-30：改为全屏选择页，不再使用难看的弹窗。 */
    private void showAppPicker() {
        startActivity(new android.content.Intent(this, WhitelistPickerActivity.class));
    }


    private int avatarColor(int idx) {
        int[] colors = {
                0xFF1565C0, 0xFF2E7D32, 0xFFC62828, 0xFF6A1B9A,
                0xFFE65100, 0xFF00838F, 0xFF4E342E, 0xFF37474F
        };
        return colors[idx];
    }
}
