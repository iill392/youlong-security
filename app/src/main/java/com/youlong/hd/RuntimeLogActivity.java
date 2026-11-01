package com.youlong.hd;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 2026-10-30：运行日志查看页。
 * 展示 runtime_events.log 的最近内容，每 2 秒自动刷新。
 */
public class RuntimeLogActivity extends Activity {

    private TextView logView;
    private ScrollView scrollView;
    private long lastModified = -1L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh(false);
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.WHITE);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F6F8);
        root.setFitsSystemWindows(true);

        root.addView(buildHeader());
        root.addView(buildBody());

        setContentView(root);
        logView = (TextView) root.findViewWithTag("logText");
        scrollView = (ScrollView) root.findViewWithTag("logScroll");
        refresh(true);
        handler.postDelayed(refreshTask, 2000);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.WHITE);
        header.setPadding(dp(12), dp(10) + statusBarHeight(), dp(14), dp(10));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(0xFF172033);
        back.setTextSize(22);
        back.setPadding(dp(8), dp(6), dp(14), dp(6));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("运行日志");
        title.setTextColor(0xFF172033);
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(title);

        TextView refresh = new TextView(this);
        refresh.setText("刷新");
        refresh.setTextColor(0xFF1565C0);
        refresh.setTextSize(14);
        refresh.setTypeface(null, Typeface.BOLD);
        refresh.setPadding(dp(10), dp(8), dp(10), dp(8));
        refresh.setOnClickListener(v -> refresh(true));
        header.addView(refresh);

        TextView clear = new TextView(this);
        clear.setText("清空");
        clear.setTextColor(0xFFE53935);
        clear.setTextSize(14);
        clear.setPadding(dp(10), dp(8), dp(4), dp(8));
        clear.setOnClickListener(v -> {
            File f = RuntimeEventLog.file(this);
            if (f.exists()) f.delete();
            RuntimeEventLog.log(this, "APP", "运行日志已清空");
            refresh(true);
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
        });
        header.addView(clear);
        return header;
    }

    private View buildBody() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(14), dp(12), dp(14), dp(12));
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView hint = new TextView(this);
        hint.setText("记录 App 打开、进入内置特权页、服务端启动与自动重启等事件");
        hint.setTextColor(0xFF7B8499);
        hint.setTextSize(11);
        hint.setPadding(dp(4), 0, dp(4), dp(8));
        wrap.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setTag("logScroll");
        scroll.setFillViewport(true);
        scroll.setBackground(roundRect(Color.WHITE, 12));
        scroll.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView tv = new TextView(this);
        tv.setTag("logText");
        tv.setTextColor(0xFF172033);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(dp(2), 1f);
        scroll.addView(tv);
        wrap.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    private void refresh(boolean force) {
        try {
            File f = RuntimeEventLog.file(this);
            if (!force && f.lastModified() == lastModified) return;
            lastModified = f.lastModified();
            String text = readTail(f, 600);
            if (text.isEmpty()) {
                text = "暂无运行日志";
            }
            logView.setText(text);
            if (scrollView != null) {
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }
        } catch (Throwable tr) {
            logView.setText("读取日志失败: " + tr);
        }
    }

    private String readTail(File file, int maxLines) {
        if (file == null || !file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String all = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            String[] lines = all.split("\n");
            int start = Math.max(0, lines.length - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.length; i++) {
                if (lines[i].isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[i]);
            }
            return sb.toString();
        } catch (Throwable tr) {
            return "";
        }
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(radius));
        d.setColor(color);
        return d;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshTask);
        super.onDestroy();
    }
}
