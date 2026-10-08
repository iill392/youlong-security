package com.youlong.hd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;


public class FpsMonitorService extends Service {

    private static final String CHANNEL_ID = "fps_monitor_channel";
    private static final int NOTIFICATION_ID = 3002;
    public static final String ACTION_STOP = "com.youlong.hd.action.STOP_FPS";

    private static WindowManager windowManager;
    private static FpsOverlayView overlayView;
    private static volatile boolean running = false;

    // ============================================================
    
    // ============================================================

    private static class FpsOverlayView extends View implements Choreographer.FrameCallback {

        private final Paint bgPaint;
        private final Paint borderPaint;
        private final Paint textPaint;
        private final Paint closePaint;
        private final Paint closeTextPaint;
        private final RectF bgRect = new RectF();
        private final RectF closeRect = new RectF();
        private final Handler handler = new Handler(Looper.getMainLooper());

        
        private long lastFrameTimeNs = 0;
        private final long[] frameDeltas = new long[30];
        private int frameIndex = 0;
        private int frameCount = 0;
        private int currentFps = 0;

        
        private float dragStartX, dragStartY;
        private float initialX, initialY;
        private boolean isDragging = false;
        private static final int DRAG_THRESHOLD = 10;

        
        private float density;
        private float capsuleW;
        private float capsuleH;
        private float cornerRadius;
        private float textSize;
        private float closeSize;
        private float closeRadius;
        private float paddingH;
        private float paddingV;

        FpsOverlayView(Context context) {
            super(context);
            setFocusable(false);
            setFocusableInTouchMode(false);

            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(Color.argb(220, 20, 20, 20));

            borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(1.5f);
            borderPaint.setColor(Color.argb(64, 0, 255, 136));

            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.rgb(0, 255, 136));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);

            closePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            closePaint.setColor(Color.rgb(255, 71, 87));

            closeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            closeTextPaint.setColor(Color.WHITE);
            closeTextPaint.setTextAlign(Paint.Align.CENTER);
            closeTextPaint.setFakeBoldText(true);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            density = getResources().getDisplayMetrics().density;
            
            paddingH = 14 * density;
            paddingV = 7 * density;
            textSize = 14 * density;
            cornerRadius = 22 * density;
            closeSize = 20 * density;
            closeRadius = closeSize / 2;
            textPaint.setTextSize(textSize);
            closeTextPaint.setTextSize(10 * density);

            
            float textW = textPaint.measureText("88 FPS");
            capsuleW = textW + paddingH * 2;
            capsuleH = textSize + paddingV * 2 + 4 * density;

            
            lastFrameTimeNs = System.nanoTime();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            Choreographer.getInstance().removeFrameCallback(this);
        }

        

        @Override
        public void doFrame(long frameTimeNanos) {
            long now = System.nanoTime();
            long deltaNs = now - lastFrameTimeNs;
            lastFrameTimeNs = now;

            if (deltaNs > 0) {
                frameDeltas[frameIndex % frameDeltas.length] = deltaNs;
                frameIndex++;
                frameCount++;
            }

            
            if (frameCount >= 60) {
                frameCount = 0;
                long sumNs = 0;
                int count = Math.min(frameIndex, frameDeltas.length);
                for (int i = 0; i < count; i++) {
                    sumNs += frameDeltas[i];
                }
                if (sumNs > 0 && count > 0) {
                    currentFps = (int) (1_000_000_000L * count / sumNs);
                }
                postInvalidate();
            }

            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();

            
            bgRect.set(0, 0, w, h);
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint);
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, borderPaint);

            
            int textColor = currentFps >= 30 ? Color.rgb(0, 255, 136) : Color.rgb(255, 107, 107);
            textPaint.setColor(textColor);
            float textY = (h + textSize * 0.35f) / 2;
            
            canvas.drawText(currentFps + " FPS", w / 2, textY, textPaint);

            
            float cx = w - closeRadius - 3 * density;
            float cy = closeRadius + 3 * density;
            closeRect.set(cx - closeRadius, cy - closeRadius, cx + closeRadius, cy + closeRadius);
            canvas.drawOval(closeRect, closePaint);
            float closeTextY = cy + closeTextPaint.getTextSize() * 0.35f;
            canvas.drawText("x", cx, closeTextY, closeTextPaint);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension((int) capsuleW, (int) capsuleH);
        }

        

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getRawX();
            float y = event.getRawY();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dragStartX = x;
                    dragStartY = y;
                    WindowManager.LayoutParams lp = getWmLayoutParams();
                    initialX = lp.x;
                    initialY = lp.y;
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = x - dragStartX;
                    float dy = y - dragStartY;
                    if (!isDragging && (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD)) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        WindowManager.LayoutParams lp2 = getWmLayoutParams();
                        lp2.x = (int) (initialX + dx);
                        lp2.y = (int) (initialY + dy);
                        windowManager.updateViewLayout(this, lp2);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        
                        float localX = event.getX();
                        float localY = event.getY();
                        float cx2 = getWidth() - closeRadius - 3 * density;
                        float cy2 = closeRadius + 3 * density;
                        float dist = (float) Math.sqrt((localX - cx2) * (localX - cx2) + (localY - cy2) * (localY - cy2));
                        if (dist <= closeRadius + 4 * density) {
                            
                            handler.post(() -> stop(getContext()));
                        }
                    }
                    isDragging = false;
                    return true;

                default:
                    return super.onTouchEvent(event);
            }
        }

        private WindowManager.LayoutParams getWmLayoutParams() {
            return (WindowManager.LayoutParams) super.getLayoutParams();
        }
    }

    // ============================================================
    
    // ============================================================

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        addOverlay();
        running = true;
    }

    private void addOverlay() {
        if (overlayView != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView = new FpsOverlayView(this);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        
        params.x = 100;
        params.y = 300;

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ============================================================
    
    // ============================================================

    public static boolean isRunning() {
        return running;
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, FpsMonitorService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    // ============================================================
    
    // ============================================================

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
        }
        overlayView = null;
        windowManager = null;
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ============================================================
    
    // ============================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "帧率监测", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("帧率监测悬浮窗运行中");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent stopI = new Intent(this, FpsMonitorService.class);
        stopI.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopI, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("帧率监测运行中")
                .setContentText("显示当前屏幕帧率")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(contentPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
                .build();
    }
}
