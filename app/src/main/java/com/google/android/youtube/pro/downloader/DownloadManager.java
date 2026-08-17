package com.google.android.youtube.pro.downloader;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.android.youtube.pro.DownloadService;

import java.io.File;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadManager {
    private static final String TAG = "YTPRO_DownloadMgr";
    private Context context;
    private Handler mainHandler;
    private Timer discoveryTimer;
    private Timer progressTimer;
    private String currentFilename;
    private DownloadState state = DownloadState.IDLE;
    private List<DownloadFormat> availableFormats;
    private DownloadProgressCallback progressCallback;
    private String currentVideoUrl;
    private VideoDataExtractor.VideoData currentVideoData;

    private DownloadService downloadService;
    private boolean isBound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            DownloadService.LocalBinder binder = (DownloadService.LocalBinder) service;
            downloadService = binder.getService();
            isBound = true;
            Log.d(TAG, "DownloadService bound");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            downloadService = null;
            Log.d(TAG, "DownloadService unbound");
        }
    };

    public enum DownloadState {
        IDLE, DISCOVERING_FORMATS, FORMATS_READY, DOWNLOADING, PAUSED, COMPLETED, ERROR, CANCELLED
    }

    public interface DownloadProgressCallback {
        void onFormatDiscovered(List<DownloadFormat> formats);
        void onDownloadProgress(int percentage, long bytesDownloaded);
        void onDownloadCompleted(String filePath);
        void onError(String message);
        void onStateChanged(DownloadState newState);
    }

    public static class DownloadFormat {
        public String quality;
        public String format; // "mp4", "3gp", "webm", etc.
        public String codec; // "H264", "VP9", etc.
        public String size;
        public String downloadUrl;
        public String formatKey; // SaveNow API key: "720", "mp3", "4k", etc.
        public int width;
        public int height;
        public int fps;

        public DownloadFormat(String quality, String format, String codec, String size) {
            this.quality = quality;
            this.format = format;
            this.codec = codec;
            this.size = size;
        }

        @Override
        public String toString() {
            return quality + " (" + format.toUpperCase() + " " + codec + ") - " + size;
        }
    }

    public DownloadManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.availableFormats = new ArrayList<>();
        
        // Ensure we use the application context for binding to avoid Activity leaks
        try {
            Intent intent = new Intent(this.context, DownloadService.class);
            this.context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind to DownloadService", e);
        }
    }

    public void cleanup() {
        this.progressCallback = null;
        if (isBound) {
            try {
                this.context.unbindService(connection);
            } catch (Exception e) {
                Log.w(TAG, "Error during unbind: " + e.getMessage());
            }
            isBound = false;
            downloadService = null;
        }
        stopProgressTracker();
    }

    public void setProgressCallback(DownloadProgressCallback callback) {
        this.progressCallback = callback;
    }

    public void startFormatDiscovery(VideoDataExtractor.VideoData videoData, long timeoutMs) {
        setDownloadState(DownloadState.DISCOVERING_FORMATS);

        // Store video data for later use in download URL construction
        currentVideoData = videoData;
        currentVideoUrl = videoData != null && videoData.videoId != null ?
            "https://www.youtube.com/watch?v=" + videoData.videoId :
            "https://www.youtube.com";

        new Thread(() -> {
            try {
                String apiUrl = "https://p.savenow.to/api/card2/?url=" + URLEncoder.encode(currentVideoUrl, "UTF-8");
                Log.d(TAG, "Discovering formats for: " + (videoData != null ? videoData.title : "Unknown"));

                availableFormats.clear();
                List<DownloadFormat> formats = fetchFormatsFromAPI(apiUrl);

                mainHandler.post(() -> {
                    if (!formats.isEmpty()) {
                        Log.d(TAG, "Discovered " + formats.size() + " formats");
                        availableFormats.addAll(formats);
                        setDownloadState(DownloadState.FORMATS_READY);
                        if (progressCallback != null) {
                            progressCallback.onFormatDiscovered(formats);
                        }
                    } else {
                        String error = "No formats found";
                        Log.e(TAG, error);
                        setDownloadState(DownloadState.ERROR);
                        if (progressCallback != null) {
                            progressCallback.onError(error);
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Format discovery failed", e);
                mainHandler.post(() -> {
                    String error = "Failed to discover formats: " + e.getMessage();
                    setDownloadState(DownloadState.ERROR);
                    if (progressCallback != null) {
                        progressCallback.onError(error);
                    }
                });
            }
        }).start();
    }

    private List<DownloadFormat> fetchFormatsFromAPI(String apiUrl) throws Exception {
        List<DownloadFormat> formats = new ArrayList<>();

        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            Log.e(TAG, "API returned status: " + responseCode);
            throw new Exception("API returned status: " + responseCode);
        }

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String readLine;
            while ((readLine = reader.readLine()) != null) {
                content.append(readLine).append("\n");
            }
        }

        String responseBody = content.toString();
        Log.d(TAG, "API Response length: " + responseBody.length());

        // Parse quality options from SaveNow response
        // Looking for patterns like: 720p, 480p, 360p, etc.
        formats.addAll(parseFormatOptions(responseBody));

        return formats;
    }

    private List<DownloadFormat> parseFormatOptions(String html) {
        List<DownloadFormat> formats = new ArrayList<>();

        // Extract hardcoded format options from SaveNow JavaScript code
        // Looking for the options array in the JavaScript source
        // Pattern: { key: "360", label: "MP4", quality: "360p" },
        Pattern optionPattern = Pattern.compile(
            "\\{\\s*key:\\s*['\"]([^'\"]+)['\"]\\s*,\\s*label:\\s*['\"]([^'\"]+)['\"]\\s*,\\s*quality:\\s*['\"]([^'\"]*)['\"](.*?)\\}",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher matcher = optionPattern.matcher(html);

        java.util.Set<String> addedFormats = new java.util.HashSet<>();

        while (matcher.find()) {
            String key = matcher.group(1).trim();      // e.g., "720", "mp3", "4k"
            String label = matcher.group(2).trim();     // e.g., "MP4", "MP3"
            String quality = matcher.group(3).trim();   // e.g., "360p", "", "128kbps"

            if (!addedFormats.contains(key) && !key.contains("$")) {  // Skip template strings
                addedFormats.add(key);

                DownloadFormat format = parseFormatFromSaveNow(key, label, quality);
                if (format != null) {
                    formats.add(format);
                    Log.d(TAG, "Found format: " + format.toString());
                }
            }
        }

        Log.d(TAG, "Parsed " + formats.size() + " format options from HTML");
        return formats;
    }

    private DownloadFormat parseFormatFromSaveNow(String key, String label, String apiQuality) {
        // Parse SaveNow format key and label into DownloadFormat
        // Examples:
        // key="360", label="MP4", apiQuality="360p" → DownloadFormat("360p", "mp4", "H264", "...")
        // key="mp3", label="MP3", apiQuality="" → DownloadFormat("MP3", "mp3", "MP3", "...")

        String quality = !apiQuality.isEmpty() ? apiQuality : label;
        String format = "mp4";
        String codec = "H264";
        String size = "unknown";

        switch (key.toLowerCase()) {
            // Video formats
            case "360":
                format = "mp4";
                codec = "H264";
                quality = "360p";
                size = "~20MB";
                break;
            case "480":
                format = "mp4";
                codec = "H264";
                quality = "480p";
                size = "~40MB";
                break;
            case "720":
                format = "mp4";
                codec = "H264";
                quality = "720p";
                size = "~60MB";
                break;
            case "1080":
                format = "mp4";
                codec = "H264";
                quality = "1080p";
                size = "~100MB";
                break;
            case "1440":
                format = "mp4";
                codec = "H264";
                quality = "1440p";
                size = "~150MB";
                break;
            case "4k":
                format = "mp4";
                codec = "VP9";
                quality = "4K (2160p)";
                size = "~200MB";
                break;
            case "8k":
                format = "mp4";
                codec = "VP9";
                quality = "8K (4320p)";
                size = "~400MB";
                break;
            case "webm":
                format = "webm";
                codec = "VP9";
                quality = "WEBM Audio";
                size = "~10MB";
                break;

            // Audio formats
            case "mp3":
                format = "mp3";
                codec = "MP3";
                quality = "MP3 (128kbps)";
                size = "~5MB";
                break;
            case "m4a":
                format = "m4a";
                codec = "AAC";
                quality = "M4A (256kbps)";
                size = "~10MB";
                break;
            case "aac":
                format = "aac";
                codec = "AAC";
                quality = "AAC (256kbps)";
                size = "~10MB";
                break;
            case "flac":
                format = "flac";
                codec = "FLAC";
                quality = "FLAC (Lossless)";
                size = "~50MB";
                break;
            case "ogg":
                format = "ogg";
                codec = "Vorbis";
                quality = "OGG (128kbps)";
                size = "~5MB";
                break;
            case "opus":
                format = "opus";
                codec = "Opus";
                quality = "OPUS (128kbps)";
                size = "~5MB";
                break;
            case "wav":
                format = "wav";
                codec = "PCM";
                quality = "WAV (Lossless)";
                size = "~100MB";
                break;
            default:
                // Unknown format - use label as quality
                quality = label;
                format = key.toLowerCase();
                Log.w(TAG, "Unknown format key: " + key);
                break;
        }

        DownloadFormat df = new DownloadFormat(quality, format, codec, size);
        df.formatKey = key;  // Store the SaveNow API key
        return df;
    }

    public void startDownload(DownloadFormat format, String videoTitle) {
        setDownloadState(DownloadState.DOWNLOADING);
        Log.d(TAG, "Starting download: " + format.quality);

        mainHandler.post(() -> {
            try {
                // Construct download URL for selected format
                String downloadUrl = constructDownloadUrl(format);
                Log.d(TAG, "Download URL: " + downloadUrl);

                currentFilename = videoTitle + "_" + format.quality + "." + format.format;
                String mimePrefix = format.format.matches("mp3|m4a|aac|flac|ogg|opus|wav") ? "audio/" : "video/";

                // Use Intent to start download immediately in the Service
                Intent intent = new Intent(this.context, DownloadService.class);
                intent.setAction(DownloadService.ACTION_DOWNLOAD);
                intent.putExtra(DownloadService.EXTRA_URL, downloadUrl);
                intent.putExtra(DownloadService.EXTRA_FILENAME, currentFilename);
                intent.putExtra(DownloadService.EXTRA_MIME, mimePrefix + format.format);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    this.context.startForegroundService(intent);
                } else {
                    this.context.startService(intent);
                }
                
                // Start progress tracker to update the UI dialog
                startServiceProgressTracker();

            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                setDownloadState(DownloadState.ERROR);
                if (progressCallback != null) {
                    progressCallback.onError("Download failed: " + e.getMessage());
                }
            }
        });
    }

    private void startServiceProgressTracker() {
        stopProgressTracker();
        progressTimer = new Timer();
        progressTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (downloadService != null && currentFilename != null) {
                    int progress = downloadService.getProgress(currentFilename);
                    long bytes = downloadService.getBytesDownloaded(currentFilename);
                    boolean isDownloading = downloadService.isDownloading(currentFilename);

                    mainHandler.post(() -> {
                        if (progress >= 0 && progressCallback != null) {
                            progressCallback.onDownloadProgress(progress, bytes);
                        }
                        
                        if (!isDownloading && state == DownloadState.DOWNLOADING) {
                            // Check if there are any pending fixes in the service
                            if (downloadService != null && downloadService.isDownloading(currentFilename)) {
                                // Still processing/fixing... wait for next poll
                                return;
                            }
                            checkDownloadCompletion();
                        }
                    });
                }
            }
        }, 0, 1000);
    }

    private void checkDownloadCompletion() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File ytProDir = new File(downloadDir, "YTPRO");
        File file = new File(ytProDir, currentFilename);
        
        if (file.exists()) {
            stopProgressTracker();
            setDownloadState(DownloadState.COMPLETED);
            if (progressCallback != null) {
                progressCallback.onDownloadCompleted(file.getAbsolutePath());
            }
        }
    }

    private void stopProgressTracker() {
        if (progressTimer != null) {
            progressTimer.cancel();
            progressTimer = null;
        }
    }

    private String constructDownloadUrl(DownloadFormat format) {
        // Build SaveNow download URL with the format key AND video URL
        // The API requires: /api/v2/download?format={key}&url={encoded_video_url}
        if (format.downloadUrl != null && !format.downloadUrl.isEmpty()) {
            return format.downloadUrl;
        }

        try {
            String formatKey = format.formatKey != null ? format.formatKey : format.format;
            String encodedUrl = URLEncoder.encode(currentVideoUrl, "UTF-8");
            String downloadUrl = "https://p.savenow.to/api/v2/download?format=" + formatKey + "&url=" + encodedUrl;
            Log.d(TAG, "Constructed download URL: " + downloadUrl);
            return downloadUrl;
        } catch (Exception e) {
            Log.e(TAG, "Failed to construct download URL", e);
            return "https://p.savenow.to/api/v2/download?format=" + (format.formatKey != null ? format.formatKey : format.format);
        }
    }

    public void pauseDownload() {
        if (state == DownloadState.DOWNLOADING) {
            setDownloadState(DownloadState.PAUSED);
            Log.d(TAG, "Download paused");
        }
    }

    public void resumeDownload() {
        if (state == DownloadState.PAUSED) {
            setDownloadState(DownloadState.DOWNLOADING);
            Log.d(TAG, "Download resumed");
        }
    }

    public void cancelActiveDownload() {
        Log.d(TAG, "Cancelling download");
        stopProgressTracker();
        if (discoveryTimer != null) {
            discoveryTimer.cancel();
            discoveryTimer = null;
        }
        
        if (isBound && downloadService != null && currentFilename != null) {
            Intent intent = new Intent(this.context, DownloadService.class);
            intent.setAction(DownloadService.ACTION_CANCEL);
            intent.putExtra(DownloadService.EXTRA_FILENAME, currentFilename);
            this.context.startService(intent);
        }

        setDownloadState(DownloadState.CANCELLED);
    }

    private void setDownloadState(DownloadState newState) {
        mainHandler.post(() -> {
            if (this.state != newState) {
                this.state = newState;
                Log.d(TAG, "State changed to: " + newState);
                if (progressCallback != null) {
                    progressCallback.onStateChanged(newState);
                }
            }
        });
    }

    public DownloadState getCurrentState() {
        return state;
    }

    public List<DownloadFormat> getAvailableFormats() {
        return new ArrayList<>(availableFormats);
    }
}
