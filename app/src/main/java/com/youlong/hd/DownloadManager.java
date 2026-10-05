package com.youlong.hd;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.webkit.URLUtil;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadManager {

    private static final String CHANNEL_ID = "download_channel";
    private static final int NOTIFICATION_ID = 1002;
    private static final int POST_NOTIFICATIONS_REQUEST_CODE = 2001;
    private static final int CREATE_DOCUMENT_REQUEST_CODE = 2002;

    private Activity activity;
    private NotificationManager notificationManager;
    private ExecutorService executorService;
    private Map<String, Runnable> activeDownloads;

    private String pendingDownloadUrl;
    private String pendingFileName;
    private Uri pendingDestinationUri;
    private String pendingBlobData; // blob: 下载转为 base64 后暂存在此

    public DownloadManager(Activity activity) {
        this.activity = activity;
        this.notificationManager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        this.executorService = Executors.newFixedThreadPool(3);
        this.activeDownloads = new HashMap<>();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "文件下载",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("显示文件下载进度");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public void downloadFile(String url, String userAgent, String contentDisposition, String mimeType) {
        pendingDownloadUrl = url;

        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);

        if (fileName == null || fileName.isEmpty() || !fileName.contains(".")) {
            fileName = extractFileNameFromUrl(url);
        }

        pendingFileName = fileName;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        activity,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        POST_NOTIFICATIONS_REQUEST_CODE
                );
                return;
            }
        }

        startFilePicker();
    }

    /**
     * 预准备 blob: URL 下载 —— 仅记录 url 和文件名，等待 WebView JS 回传 base64 数据。
     * 当 onBlobDataReceived() 被调用后，才会弹出文件选择器。
     */
    public void prepareBlobDownload(String blobUrl, String userAgent, String contentDisposition, String mimeType) {
        pendingBlobData = null;
        pendingDownloadUrl = blobUrl;

        String fileName = URLUtil.guessFileName(blobUrl, contentDisposition, mimeType);
        if (fileName == null || fileName.isEmpty() || !fileName.contains(".")) {
            fileName = extractFileNameFromUrl(blobUrl);
        }
        pendingFileName = fileName;
    }

    /**
     * WebView JS 回传 blob 的 base64 数据后调用，弹出文件选择器。
     */
    public void onBlobDataReceived(String base64Data) {
        pendingBlobData = base64Data;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        activity,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        POST_NOTIFICATIONS_REQUEST_CODE
                );
                return;
            }
        }

        startFilePicker();
    }

    private String extractFileNameFromUrl(String url) {
        try {
            String path = new URL(url).getPath();
            if (path != null && !path.isEmpty()) {
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                    String fileName = path.substring(lastSlash + 1);
                    if (!fileName.isEmpty() && fileName.contains(".")) {
                        return fileName;
                    }
                }
            }
        } catch (Exception e) {
        }

        return "download_" + System.currentTimeMillis() + ".bin";
    }

    private void startFilePicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, pendingFileName);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        activity.startActivityForResult(intent, CREATE_DOCUMENT_REQUEST_CODE);
    }

    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CREATE_DOCUMENT_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    activity.getContentResolver().takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );

                    pendingDestinationUri = uri;
                    startDownload(pendingDownloadUrl, pendingFileName, uri);
                }
            } else {
                pendingBlobData = null;
                Toast.makeText(activity, "用户取消了下载", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void handlePermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == POST_NOTIFICATIONS_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限授予后继续下载流程（普通下载或 blob 下载）
                startFilePicker();
            } else {
                Toast.makeText(activity, "没有通知权限，无法显示下载进度", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startDownload(String url, String fileName, Uri destinationUri) {
        if (url != null && url.startsWith("blob:") && pendingBlobData != null) {
            // blob 下载：解码 base64 写入文件
            BlobDownloadTask task = new BlobDownloadTask(pendingBlobData, fileName, destinationUri);
            activeDownloads.put(fileName, task);
            executorService.execute(task);
            showStartNotification(fileName);
            pendingBlobData = null;
        } else {
            // 普通 HTTP 下载
            DownloadTask task = new DownloadTask(url, fileName, destinationUri);
            activeDownloads.put(fileName, task);
            executorService.execute(task);
            showStartNotification(fileName);
        }
    }

    private void showStartNotification(String fileName) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("开始下载")
                .setContentText(fileName + " - 准备中...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(0, 0, true);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private class DownloadTask implements Runnable {
        private String url;
        private String fileName;
        private Uri destinationUri;
        private int progress = 0;

        public DownloadTask(String url, String fileName, Uri destinationUri) {
            this.url = url;
            this.fileName = fileName;
            this.destinationUri = destinationUri;
        }

        @Override
        public void run() {
            HttpURLConnection connection = null;
            java.io.InputStream input = null;
            OutputStream output = null;
            try {
                URL downloadUrl = new URL(url);
                connection = (HttpURLConnection) downloadUrl.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
                connection.connect();

                int fileLength = connection.getContentLength();
                input = connection.getInputStream();
                ContentResolver resolver = activity.getContentResolver();
                output = resolver.openOutputStream(destinationUri);

                if (output == null) {
                    showErrorNotification(fileName, "无法创建文件");
                    return;
                }

                byte[] buffer = new byte[8192];
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
                            showProgressNotification(fileName, progress);
                        }
                    }
                }

                output.flush();

                showCompleteNotification(fileName);

            } catch (Exception e) {
                showErrorNotification(fileName, e.getMessage());
            } finally {
                try {
                    if (output != null) {
                        output.close();
                    }
                    if (input != null) {
                        input.close();
                    }
                    if (connection != null) {
                        connection.disconnect();
                    }
                } catch (Exception e) {
                }
                activeDownloads.remove(fileName);
            }
        }
    }

    /**
     * Blob 下载任务：将 base64 DataURL 解码为二进制数据写入目标文件。
     */
    private class BlobDownloadTask implements Runnable {
        private String base64Data;
        private String fileName;
        private Uri destinationUri;

        public BlobDownloadTask(String base64Data, String fileName, Uri destinationUri) {
            this.base64Data = base64Data;
            this.fileName = fileName;
            this.destinationUri = destinationUri;
        }

        @Override
        public void run() {
            OutputStream output = null;
            try {
                // 解析 base64 DataURL：data:[<mime>];base64,<data>
                String base64 = base64Data;
                int commaIndex = base64.indexOf(',');
                if (commaIndex >= 0) {
                    base64 = base64.substring(commaIndex + 1);
                }

                byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);

                ContentResolver resolver = activity.getContentResolver();
                output = resolver.openOutputStream(destinationUri);

                if (output == null) {
                    showErrorNotification(fileName, "无法创建文件");
                    return;
                }

                output.write(data);
                output.flush();

                showCompleteNotification(fileName);

            } catch (Exception e) {
                showErrorNotification(fileName, e.getMessage());
            } finally {
                try {
                    if (output != null) {
                        output.close();
                    }
                } catch (Exception e) {
                }
                activeDownloads.remove(fileName);
            }
        }
    }

    private void showProgressNotification(String fileName, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载")
                .setContentText(fileName + " - " + progress + "%")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(100, progress, false);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void showCompleteNotification(String fileName) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(pendingDestinationUri, "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                activity,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("下载完成")
                .setContentText(fileName + " - 点击打开文件")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void showErrorNotification(String fileName, String error) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("下载失败")
                .setContentText(fileName + (error != null ? " - " + error : ""))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
}
