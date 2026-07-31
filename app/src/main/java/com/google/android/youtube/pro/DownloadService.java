package com.google.android.youtube.pro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
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
        // Service is started so it outlives the bound Activity.
        // We promote to foreground immediately so Android won't kill us.
        if (!isForeground) promoteToForeground();
        return START_STICKY; // restart if killed by system
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
     * Opens a new output stream for the given file name.
     * Must be called before any writeChunk() calls for this file.
     */
    public void openStream(String fileName) {
        ioExecutor.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = getContentResolver();
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    cv.put(MediaStore.Downloads.MIME_TYPE, getMimeType(fileName));
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
                activeStreams.incrementAndGet();
                updateNotification();
                Log.d(TAG, "Stream opened: " + fileName);

            } catch (Exception e) {
                Log.e(TAG, "openStream failed: " + e.getMessage());
            }
        });
    }

    /**
     * Writes a chunk of bytes for the given file.
     * Safe to call from any thread.
     */
    public void writeChunk(String fileName, byte[] data) {
        ioExecutor.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    OutputStream os = fileStreams.get(fileName);
                    if (os != null) os.write(data);
                } else {
                    FileOutputStream fos = legacyStreams.get(fileName);
                    if (fos != null) fos.write(data);
                }
                long prev = bytesWritten.getOrDefault(fileName, 0L);
                bytesWritten.put(fileName, prev + data.length);
                updateNotification();
            } catch (Exception e) {
                Log.e(TAG, "writeChunk failed: " + e.getMessage());
            }
        });
    }

    /**
     * Closes and finalises the stream for the given file.
     * When all streams are done the service stops itself.
     */
    public void closeStream(String fileName) {
        ioExecutor.execute(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    OutputStream os = fileStreams.remove(fileName);
                    if (os != null) { os.flush(); os.close(); }

                    Uri uri = fileUris.remove(fileName);
                    if (uri != null) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Downloads.IS_PENDING, 0);
                        getContentResolver().update(uri, cv, null, null);
                    }
                } else {
                    FileOutputStream fos = legacyStreams.remove(fileName);
                    if (fos != null) { fos.flush(); fos.close(); }
                }

                bytesWritten.remove(fileName);
                int remaining = activeStreams.decrementAndGet();
                Log.d(TAG, "Stream closed: " + fileName + " | remaining: " + remaining);

                if (remaining <= 0) {
                    // All done — stop the foreground service gracefully.
                    stopForeground(true);
                    isForeground = false;
                    stopSelf();
                } else {
                    updateNotification();
                }
            } catch (Exception e) {
                Log.e(TAG, "closeStream failed: " + e.getMessage());
            }
        });
    }

    /** Returns the URI for a file still in progress (for MediaMuxer). */
    public Uri getUriForFile(String fileName) {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ? fileUris.get(fileName) : null;
    }

    /** @return number of active in-flight downloads */
    public int getActiveDownloadCount() { return activeStreams.get(); }

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
        startForeground(NOTIF_ID, buildNotification("Starting download…"));
    }

    private void updateNotification() {
        if (!isForeground) return;
        int count = activeStreams.get();
        long total = 0;
        for (Long b : bytesWritten.values()) total += b;

        String msg = count + " file" + (count == 1 ? "" : "s") +
                " — " + formatBytes(total) + " written";
        notifManager.notify(NOTIF_ID, buildNotification(msg));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return b.setSmallIcon(R.drawable.notification)
                .setContentTitle("YT PRO Download")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
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
