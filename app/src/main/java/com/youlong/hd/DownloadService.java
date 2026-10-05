package com.youlong.hd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class DownloadService extends Service {

    private static final String CHANNEL_ID = "download_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final Map<String, DownloadTask> activeDownloads = new HashMap<>();

    private NotificationManager notificationManager;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String url = intent.getStringExtra("url");
            String fileName = intent.getStringExtra("fileName");
            Uri destinationUri = intent.getParcelableExtra("destinationUri");

            if (url != null && fileName != null && destinationUri != null) {
                startDownload(url, fileName, destinationUri);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "下载进度",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("显示文件下载进度");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void startDownload(String url, String fileName, Uri destinationUri) {
        DownloadTask task = new DownloadTask(url, fileName, destinationUri);
        activeDownloads.put(fileName, task);
        new Thread(task).start();
    }

    private class DownloadTask implements Runnable {
        private String url;
        private String fileName;
        private Uri destinationUri;
        private int progress = 0;
        private boolean isCompleted = false;

        public DownloadTask(String url, String fileName, Uri destinationUri) {
            this.url = url;
            this.fileName = fileName;
            this.destinationUri = destinationUri;
        }

        @Override
        public void run() {
            try {
                mainHandler.post(() -> showProgressNotification(fileName, 0));

                URL downloadUrl = new URL(url);
                HttpURLConnection connection = (HttpURLConnection) downloadUrl.openConnection();
                connection.connect();

                int fileLength = connection.getContentLength();
                InputStream input = connection.getInputStream();

                android.content.ContentResolver resolver = getContentResolver();
                java.io.OutputStream output = resolver.openOutputStream(destinationUri);

                if (output == null) {
                    mainHandler.post(() -> showErrorNotification(fileName, "无法创建文件"));
                    return;
                }

                byte[] buffer = new byte[4096];
                long total = 0;
                int count;
                int lastProgress = 0;

                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    output.write(buffer, 0, count);

                    if (fileLength > 0) {
                        progress = (int) ((total * 100) / fileLength);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            final int currentProgress = progress;
                            mainHandler.post(() -> showProgressNotification(fileName, currentProgress));
                        }
                    }
                }

                output.flush();
                output.close();
                input.close();
                connection.disconnect();

                isCompleted = true;
                mainHandler.post(() -> showCompleteNotification(fileName));

            } catch (Exception e) {
                mainHandler.post(() -> showErrorNotification(fileName, e.getMessage()));
            } finally {
                activeDownloads.remove(fileName);
                if (activeDownloads.isEmpty()) {
                    stopSelf();
                }
            }
        }
    }

    private void showProgressNotification(String fileName, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载")
                .setContentText(fileName + " - " + progress + "%")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(100, progress, false);

        Notification notification = builder.build();
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void showCompleteNotification(String fileName) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(destinationUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("下载完成")
                .setContentText(fileName + " 下载完成，点击打开")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        Notification notification = builder.build();
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void showErrorNotification(String fileName, String error) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("下载失败")
                .setContentText(fileName + " - " + (error != null ? error : "未知错误"))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        Notification notification = builder.build();
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private Uri destinationUri;
}
