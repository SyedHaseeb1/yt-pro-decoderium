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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DownloadService — a bound + started foreground service that owns all active
 * file-download streams. Because the service is started (not only bound), Android
 * will keep the process alive even after the Activity is destroyed, so in-flight
 * downloads continue writing to disk.
 *
 * Lifecycle:
 *   • Activity calls startService(DownloadService) + bindService(DownloadService).
 *   • openStream(fileName) opens a MediaStore / legacy OutputStream in the service.
 *   • writeChunk(fileName, bytes) writes data — called from BinaryStreamManager
 *     which now delegates to the service.
 *   • closeStream(fileName) flushes and closes. When all streams are closed the
 *     service stops itself.
 *   • If the Activity is killed mid-download the service keeps running because it
 *     was started. When the Activity returns it rebinds with NO extra work needed.
 */
public class DownloadService extends Service {

    private static final String TAG = "YTPRO_DL_SVC";
    public static final String CHANNEL_ID = "Downloads";
    private static final int NOTIF_ID = 2;

    public static final String ACTION_CANCEL = "com.google.android.youtube.pro.ACTION_CANCEL";
    public static final String ACTION_DOWNLOAD = "com.google.android.youtube.pro.ACTION_DOWNLOAD";
    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_FILENAME = "extra_filename";
    public static final String EXTRA_MIME = "extra_mime";

    // ---- Binder ----
    public class LocalBinder extends Binder {
        public DownloadService getService() { return DownloadService.this; }
    }
    private final IBinder binder = new LocalBinder();

    // ---- State ----
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(4);

    // API 29+
    private final ConcurrentHashMap<String, OutputStream> fileStreams  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Uri>          fileUris     = new ConcurrentHashMap<>();
    // API 21-28
    private final ConcurrentHashMap<String, FileOutputStream> legacyStreams = new ConcurrentHashMap<>();

    // Progress tracking (bytes written per file)
    private final ConcurrentHashMap<String, Long> bytesWritten = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> totalBytes = new ConcurrentHashMap<>();
    private final java.util.Set<String> pendingFixes = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // Active stream counter — stop service when it reaches 0 AND we were started
    private final AtomicInteger activeStreams = new AtomicInteger(0);

    private NotificationManager notifManager;
    private boolean isForeground = false;

    // ---- Service lifecycle ----

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

        // Service is started so it outlives the bound Activity.
        // We promote to foreground immediately so Android won't kill us.
        if (!isForeground) promoteToForeground();
        return START_STICKY; // restart if killed by system
    }

    private void cancelDownload(String fileName) {
        Log.d(TAG, "Cancelling download: " + fileName);
        closeStreamInternal(fileName);
        // We might need to stop the thread if it's a URL download.
        // For now, closeStreamInternal will stop the writing.
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public boolean onUnbind(Intent intent) {
        // Return true so onRebind is called when Activity reconnects.
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        Log.d(TAG, "Activity rebound — " + activeStreams.get() + " active stream(s)");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    // ---- Public API (called from BinaryStreamManager on a background thread) ----

    /**
     * Downloads a file from a URL and saves it to the Downloads/YTPRO folder.
     */
    public void startUrlDownload(String urlString, String fileName, String mimeType) {
        ioExecutor.execute(() -> {
            HttpURLConnection connection = null;
            InputStream input = null;
            try {
                activeStreams.incrementAndGet();
                if (!isForeground) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(this::promoteToForeground);
                }

                URL url = new URL(urlString);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.connect();

                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Server returned HTTP " + responseCode + " for " + fileName);
                    activeStreams.decrementAndGet();
                    return;
                }

                long fileLength = connection.getContentLength();
                if (fileLength > 0) {
                    totalBytes.put(fileName, fileLength);
                }

                input = connection.getInputStream();
                openStreamInternal(fileName, mimeType);

                byte[] data = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(data)) != -1) {
                    total += count;
                    writeChunkInternal(fileName, data, count);
                    bytesWritten.put(fileName, total);
                    updateNotification();
                }

                closeStreamInternal(fileName);
                Log.d(TAG, "Download finished: " + fileName);
                
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                    Toast.makeText(getApplicationContext(), "Download complete: " + fileName, Toast.LENGTH_SHORT).show()
                );

            } catch (Exception e) {
                Log.e(TAG, "startUrlDownload failed for " + fileName + ": " + e.getMessage());
                activeStreams.decrementAndGet();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> 
                    Toast.makeText(getApplicationContext(), "Download failed: " + fileName, Toast.LENGTH_SHORT).show()
                );
            } finally {
                try { if (input != null) input.close(); } catch (Exception ignored) {}
                try { if (connection != null) connection.disconnect(); } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Opens a new output stream for the given file name.
     * Must be called before any writeChunk() calls for this file.
     */
    public void openStream(String fileName) {
        ioExecutor.execute(() -> {
            openStreamInternal(fileName, getMimeType(fileName));
            activeStreams.incrementAndGet();
            updateNotification();
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

                Uri uri = resolver.insert(
                        MediaStore.Downloads.getContentUri("external"), cv);
                if (uri == null) { Log.e(TAG, "MediaStore insert null: " + fileName); return; }

                OutputStream os = resolver.openOutputStream(uri);
                if (os == null) { Log.e(TAG, "openOutputStream null: " + fileName); return; }

                fileStreams.put(fileName, os);
                fileUris.put(fileName, uri);
            } else {
                File dir = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), "YTPRO");
                if (!dir.exists()) dir.mkdirs();
                legacyStreams.put(fileName, new FileOutputStream(new File(dir, fileName), true));
            }

            bytesWritten.put(fileName, 0L);
            Log.d(TAG, "Stream opened: " + fileName);
        } catch (Exception e) {
            Log.e(TAG, "openStreamInternal failed: " + e.getMessage());
        }
    }

    /**
     * Writes a chunk of bytes for the given file.
     * Safe to call from any thread.
     */
    public void writeChunk(String fileName, byte[] data) {
        ioExecutor.execute(() -> {
            writeChunkInternal(fileName, data, data.length);
            updateNotification();
        });
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
            long prev = bytesWritten.getOrDefault(fileName, 0L);
            bytesWritten.put(fileName, prev + length);
        } catch (Exception e) {
            Log.e(TAG, "writeChunkInternal failed: " + e.getMessage());
        }
    }

    /**
     * Closes and finalises the stream for the given file.
     * When all streams are done the service stops itself.
     */
    public void closeStream(String fileName) {
        ioExecutor.execute(() -> {
            closeStreamInternal(fileName);
            int remaining = activeStreams.get();
            if (remaining <= 0) {
                stopSelf();
            } else {
                updateNotification();
            }
        });
    }

    private void closeStreamInternal(String fileName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                OutputStream os = fileStreams.remove(fileName);
                if (os != null) { os.flush(); os.close(); }

                Uri uri = fileUris.remove(fileName);
                if (uri != null) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, cv, null, null);
                    
                    // Trigger seekability fix for video/audio files
                    if (fileName.endsWith(".mp4") || fileName.endsWith(".webm") || fileName.endsWith(".m4a")) {
                        Log.d(TAG, "Download finished, fixing seekability for: " + fileName);
                        pendingFixes.add(fileName);
                        com.google.android.youtube.pro.utils.MediaMuxerUtils.fixSeekability(this, uri, fileName, new com.google.android.youtube.pro.utils.MediaMuxerUtils.MuxCallback() {
                            @Override
                            public void onSuccess(File outputFile) {
                                pendingFixes.remove(fileName);
                                Log.d(TAG, "Seekability fix successful for: " + fileName);
                            }

                            @Override
                            public void onFailure(Exception e) {
                                pendingFixes.remove(fileName);
                                Log.e(TAG, "Seekability fix failed for: " + fileName, e);
                            }
                        });
                    }
                }
            } else {
                FileOutputStream fos = legacyStreams.remove(fileName);
                if (fos != null) { fos.flush(); fos.close(); }

                File dir = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), "YTPRO");
                File file = new File(dir, fileName);
                if (file.exists()) {
                    // Trigger seekability fix for video/audio files
                    if (fileName.endsWith(".mp4") || fileName.endsWith(".webm") || fileName.endsWith(".m4a")) {
                        Log.d(TAG, "Download finished, fixing seekability for: " + fileName);
                        pendingFixes.add(fileName);
                        com.google.android.youtube.pro.utils.MediaMuxerUtils.fixSeekability(this, file, new com.google.android.youtube.pro.utils.MediaMuxerUtils.MuxCallback() {
                            @Override
                            public void onSuccess(File outputFile) {
                                pendingFixes.remove(fileName);
                                Log.d(TAG, "Seekability fix successful for: " + fileName);
                            }

                            @Override
                            public void onFailure(Exception e) {
                                pendingFixes.remove(fileName);
                                Log.e(TAG, "Seekability fix failed for: " + fileName, e);
                            }
                        });
                    } else {
                        MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);
                    }
                }
            }

            bytesWritten.remove(fileName);
            totalBytes.remove(fileName);
            int remaining = activeStreams.decrementAndGet();
            Log.d(TAG, "Stream closed: " + fileName + " | remaining: " + remaining);

            if (remaining <= 0) {
                isForeground = false;
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "closeStreamInternal failed: " + e.getMessage());
        }
    }

    /** Returns the URI for a file still in progress (for MediaMuxer). */
    public Uri getUriForFile(String fileName) {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ? fileUris.get(fileName) : null;
    }

    /** @return number of active in-flight downloads */
    public int getActiveDownloadCount() { return activeStreams.get(); }

    /** @return progress percentage (0-100) or -1 if unknown */
    public int getProgress(String fileName) {
        Long written = bytesWritten.get(fileName);
        Long total = totalBytes.get(fileName);
        if (written != null && total != null && total > 0) {
            return (int) (written * 100 / total);
        }
        return -1;
    }

    /** @return bytes downloaded for this file */
    public long getBytesDownloaded(String fileName) {
        return bytesWritten.getOrDefault(fileName, 0L);
    }

    /** @return true if the file is currently downloading or being fixed */
    public boolean isDownloading(String fileName) {
        return bytesWritten.containsKey(fileName) || pendingFixes.contains(fileName);
    }

    // ---- Notifications ----

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Active file downloads");
            ch.setSound(null, null);
            notifManager.createNotificationChannel(ch);
        }
    }

    private void promoteToForeground() {
        isForeground = true;
        Notification notification = buildNotification("Starting download…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void updateNotification() {
        if (!isForeground) return;
        int count = activeStreams.get();
        if (count <= 0) return;

        long totalWritten = 0;
        for (Long b : bytesWritten.values()) totalWritten += b;

        String title = "Downloading " + count + " file" + (count == 1 ? "" : "s");
        String text = formatBytes(totalWritten) + " downloaded";

        // If there's only one file, show its name and progress
        if (count == 1 && !bytesWritten.isEmpty()) {
            String fileName = bytesWritten.keySet().iterator().next();
            title = fileName;
            Long total = totalBytes.get(fileName);
            if (total != null && total > 0) {
                int progress = (int) (bytesWritten.get(fileName) * 100 / total);
                text = progress + "% (" + formatBytes(bytesWritten.get(fileName)) + " / " + formatBytes(total) + ")";
                notifManager.notify(NOTIF_ID, buildProgressNotification(title, text, progress));
                return;
            }
        }

        notifManager.notify(NOTIF_ID, buildNotification(title, text));
    }

    private Notification buildNotification(String text) {
        return buildNotification("YT PRO Download", text);
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setSmallIcon(R.drawable.notification)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private Notification buildProgressNotification(String title, String text, int progress) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent cancelIntent = new Intent(this, DownloadService.class);
        cancelIntent.setAction(ACTION_CANCEL);
        cancelIntent.putExtra(EXTRA_FILENAME, title);
        PendingIntent cancelPi = PendingIntent.getService(
                this, title.hashCode(), cancelIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setSmallIcon(R.drawable.notification)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi)
                .build();
    }

    // ---- Helpers ----

    private static String getMimeType(String fileName) {
        if (fileName.endsWith(".webm")) return "video/webm";
        if (fileName.endsWith(".mp4"))  return "video/mp4";
        if (fileName.endsWith(".m4a"))  return "audio/mp4";
        if (fileName.endsWith(".opus")) return "audio/ogg";
        return "application/octet-stream";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }
}
