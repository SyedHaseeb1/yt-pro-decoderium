package com.google.android.youtube.pro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import com.google.android.youtube.pro.utils.MediaMuxerUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadService extends Service {

    private static final String TAG = "YTPRO_DL_SVC";
    public static final String CHANNEL_ID = "Downloads";
    private static final int NOTIF_ID = 2;

    public static final String ACTION_CANCEL = "com.google.android.youtube.pro.ACTION_CANCEL";
    public static final String ACTION_DOWNLOAD = "com.google.android.youtube.pro.ACTION_DOWNLOAD";
    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_FILENAME = "extra_filename";
    public static final String EXTRA_MIME = "extra_mime";

    public class LocalBinder extends Binder {
        public DownloadService getService() { return DownloadService.this; }
    }
    private final IBinder binder = new LocalBinder();

    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(4);

    private final ConcurrentHashMap<String, OutputStream> fileStreams  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Uri>          fileUris     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FileOutputStream> legacyStreams = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> bytesWritten = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> totalBytes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> currentStatus = new ConcurrentHashMap<>();
    private final java.util.Set<String> pendingFixes = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    
    private long lastNotificationTime = 0;
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    private NotificationManager notifManager;
    private boolean isForeground = false;

    @Override
    public void onCreate() {
        super.onCreate();
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_CANCEL.equals(action)) {
                String fileName = intent.getStringExtra(EXTRA_FILENAME);
                if (fileName != null) cancelDownload(fileName);
            } else if (ACTION_DOWNLOAD.equals(action)) {
                String url = intent.getStringExtra(EXTRA_URL);
                String fileName = intent.getStringExtra(EXTRA_FILENAME);
                String mime = intent.getStringExtra(EXTRA_MIME);
                if (url != null && fileName != null) {
                    startUrlDownload(url, fileName, mime);
                }
            }
        }
        if (!isForeground) promoteToForeground();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "App swiped away. Continuing downloads in background...");
        super.onTaskRemoved(rootIntent);
    }

    private void cancelDownload(String fileName) {
        Log.d(TAG, "Cancelling download: " + fileName);
        closeStreamInternal(fileName);
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public boolean onUnbind(Intent intent) { return true; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    public void startUrlDownload(String urlString, String fileName, String mimeType) {
        ioExecutor.execute(() -> {
            HttpURLConnection connection = null;
            InputStream input = null;
            try {
                activeStreams.incrementAndGet();
                currentStatus.put(fileName, "Connecting...");
                updateNotificationImmediate();

                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.connect();

                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    activeStreams.decrementAndGet();
                    currentStatus.remove(fileName);
                    updateNotificationImmediate();
                    return;
                }

                long fileLength = connection.getContentLength();
                if (fileLength > 0) totalBytes.put(fileName, fileLength);

                input = connection.getInputStream();
                openStreamInternal(fileName, mimeType);

                byte[] data = new byte[8192];
                long total = 0;
                int count;
                currentStatus.put(fileName, "Downloading...");
                while ((count = input.read(data)) != -1) {
                    total += count;
                    writeChunkInternal(fileName, data, count);
                    bytesWritten.put(fileName, total);
                    updateNotificationThrottled();
                }

                closeStreamInternal(fileName);

            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + fileName, e);
                activeStreams.decrementAndGet();
                currentStatus.remove(fileName);
                updateNotificationImmediate();
            } finally {
                try { if (input != null) input.close(); } catch (Exception ignored) {}
                try { if (connection != null) connection.disconnect(); } catch (Exception ignored) {}
            }
        });
    }

    private void openStreamInternal(String fileName, String mimeType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download/YTPRO");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri uri = resolver.insert(MediaStore.Downloads.getContentUri("external"), cv);
                if (uri == null) return;
                OutputStream os = resolver.openOutputStream(uri);
                if (os == null) return;

                fileStreams.put(fileName, os);
                fileUris.put(fileName, uri);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YTPRO");
                if (!dir.exists()) dir.mkdirs();
                legacyStreams.put(fileName, new FileOutputStream(new File(dir, fileName), true));
            }
            bytesWritten.put(fileName, 0L);
        } catch (Exception e) {
            Log.e(TAG, "openStream failed", e);
        }
    }

    private void writeChunkInternal(String fileName, byte[] data, int length) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                OutputStream os = fileStreams.get(fileName);
                if (os != null) os.write(data, 0, length);
            } else {
                FileOutputStream fos = legacyStreams.get(fileName);
                if (fos != null) fos.write(data, 0, length);
            }
        } catch (Exception e) {
            Log.e(TAG, "write failed", e);
        }
    }

    private void closeStreamInternal(String fileName) {
        try {
            Uri currentUri = null;
            File currentFile = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                OutputStream os = fileStreams.remove(fileName);
                if (os != null) { os.flush(); os.close(); }
                currentUri = fileUris.remove(fileName);
                if (currentUri != null) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(currentUri, cv, null, null);
                }
            } else {
                FileOutputStream fos = legacyStreams.remove(fileName);
                if (fos != null) { fos.flush(); fos.close(); }
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YTPRO");
                currentFile = new File(dir, fileName);
            }

            currentStatus.put(fileName, "Processing...");

            if (fileName.endsWith(".mp4") || fileName.endsWith(".webm") || fileName.endsWith(".m4a")) {
                pendingFixes.add(fileName);
                activeStreams.decrementAndGet();
                updateNotificationImmediate();

                MediaMuxerUtils.MuxCallback callback = new MediaMuxerUtils.MuxCallback() {
                    @Override
                    public void onSuccess(File outputFile) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                            Toast.makeText(getApplicationContext(), "Saved: " + outputFile.getName(), Toast.LENGTH_LONG).show()
                        );
                        finalizeFile(fileName);
                    }
                    @Override
                    public void onFailure(Exception e) {
                        Log.e(TAG, "Fix failed for: " + fileName, e);
                        finalizeFile(fileName);
                    }
                };
                if (currentUri != null) MediaMuxerUtils.fixSeekability(this, currentUri, fileName, callback);
                else if (currentFile != null) MediaMuxerUtils.fixSeekability(this, currentFile, callback);
                else finalizeFile(fileName);
            } else {
                activeStreams.decrementAndGet();
                updateNotificationImmediate();
                if (currentFile != null) MediaScannerConnection.scanFile(this, new String[]{currentFile.getAbsolutePath()}, null, null);
                finalizeFile(fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "closeStream failed", e);
            finalizeFile(fileName);
        }
    }

    private void finalizeFile(String fileName) {
        showCompletionNotification(fileName);
        pendingFixes.remove(fileName);
        currentStatus.remove(fileName);
        bytesWritten.remove(fileName);
        totalBytes.remove(fileName);
        if (activeStreams.get() <= 0 && pendingFixes.isEmpty()) {
            isForeground = false;
            stopForeground(true);
            stopSelf();
        } else {
            updateNotificationImmediate();
        }
    }

    private void showCompletionNotification(String fileName) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.notification)
                .setContentTitle("Download Complete")
                .setContentText("Saved: " + fileName)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(pi)
                .build();
        notifManager.notify(fileName.hashCode(), n);
    }

    private void updateNotificationThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastNotificationTime > 1200) {
            updateNotificationImmediate();
            lastNotificationTime = now;
        }
    }

    private void updateNotificationImmediate() {
        if (!isForeground) return;
        int count = activeStreams.get() + pendingFixes.size();
        if (count <= 0) return;

        String title = "YT PRO Downloader";
        String text = "";
        int progress = -1;

        String fileName = "";
        if (!currentStatus.isEmpty()) fileName = currentStatus.keySet().iterator().next();
        else if (!pendingFixes.isEmpty()) fileName = pendingFixes.iterator().next();

        if (!fileName.isEmpty()) {
            title = fileName;
            String status = currentStatus.getOrDefault(fileName, "Downloading...");
            if (pendingFixes.contains(fileName)) {
                text = "Processing (fixing seek bar)...";
                progress = 100;
            } else {
                Long total = totalBytes.get(fileName);
                Long written = bytesWritten.get(fileName);
                if (total != null && total > 0 && written != null) {
                    progress = (int) (written * 100 / total);
                    text = progress + "% (" + formatBytes(written) + " / " + formatBytes(total) + ")";
                } else if (written != null) {
                    text = formatBytes(written) + " downloaded";
                } else {
                    text = status;
                }
            }
        } else if (count > 1) {
            title = "Downloading " + count + " files";
            long totalWritten = 0;
            for (Long b : bytesWritten.values()) totalWritten += b;
            text = formatBytes(totalWritten) + " total downloaded";
        }

        if (progress >= 0) notifManager.notify(NOTIF_ID, buildProgressNotification(title, text, progress));
        else notifManager.notify(NOTIF_ID, buildNotification(title, text));
    }

    private Notification buildNotification(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.notification).setContentTitle(title).setContentText(text).setOngoing(true).setContentIntent(pi).build();
    }

    private Notification buildProgressNotification(String title, String text, int progress) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent cancelIntent = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL).putExtra(EXTRA_FILENAME, title);
        PendingIntent cancelPi = PendingIntent.getService(this, title.hashCode(), cancelIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.notification).setContentTitle(title).setContentText(text).setProgress(100, progress, false).setOngoing(true).setContentIntent(pi).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi).build();
    }

    public int getProgress(String fileName) {
        Long written = bytesWritten.get(fileName);
        Long total = totalBytes.get(fileName);
        if (written != null && total != null && total > 0) return (int) (written * 100 / total);
        return -1;
    }

    public long getBytesDownloaded(String fileName) { return bytesWritten.getOrDefault(fileName, 0L); }

    public boolean isDownloading(String fileName) {
        return bytesWritten.containsKey(fileName) || pendingFixes.contains(fileName);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            notifManager.createNotificationChannel(ch);
        }
    }

    private void promoteToForeground() {
        isForeground = true;
        Notification notification = buildNotification("YT PRO Downloader", "Starting...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }
}
