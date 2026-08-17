package com.google.android.youtube.pro.downloader;

import android.app.Activity;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

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
    private Activity activity;
    private Handler mainHandler;
    private Timer discoveryTimer;
    private Timer progressTimer;
    private long currentDownloadId = -1;
    private String currentFilename;
    private DownloadState state = DownloadState.IDLE;
    private List<DownloadFormat> availableFormats;
    private DownloadProgressCallback progressCallback;
    private String currentVideoUrl;
    private VideoDataExtractor.VideoData currentVideoData;

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

    public DownloadManager(Activity activity) {
        this.activity = activity;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.availableFormats = new ArrayList<>();
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

                // Use existing DownloadUtils to enqueue download (must be on main thread for Toast)
                currentFilename = videoTitle + "_" + format.quality + "." + format.format;
                
                // Determine correct MIME category
                String mimePrefix = format.format.matches("mp3|m4a|aac|flac|ogg|opus|wav") ? "audio/" : "video/";
                
                currentDownloadId = com.google.android.youtube.pro.utils.DownloadUtils.downloadFile(
                    activity,
                    currentFilename,
                    downloadUrl,
                    mimePrefix + format.format
                );

                if (currentDownloadId != -1) {
                    startProgressTracker();
                } else {
                    setDownloadState(DownloadState.ERROR);
                    if (progressCallback != null) {
                        progressCallback.onError("Failed to start download");
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                setDownloadState(DownloadState.ERROR);
                if (progressCallback != null) {
                    progressCallback.onError("Download failed: " + e.getMessage());
                }
            }
        });
    }

    private void startProgressTracker() {
        stopProgressTracker();
        progressTimer = new Timer();
        progressTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                queryProgress();
            }
        }, 0, 1000);
    }

    private void stopProgressTracker() {
        if (progressTimer != null) {
            progressTimer.cancel();
            progressTimer = null;
        }
    }

    private void queryProgress() {
        if (currentDownloadId == -1) return;

        android.app.DownloadManager dm = (android.app.DownloadManager) activity.getSystemService(android.content.Context.DOWNLOAD_SERVICE);
        android.app.DownloadManager.Query query = new android.app.DownloadManager.Query();
        query.setFilterById(currentDownloadId);

        try (android.database.Cursor cursor = dm.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                int bytesDownloadedColumn = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                int bytesTotalColumn = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                int statusColumn = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);

                if (bytesDownloadedColumn != -1 && bytesTotalColumn != -1 && statusColumn != -1) {
                    int bytesDownloaded = cursor.getInt(bytesDownloadedColumn);
                    int bytesTotal = cursor.getInt(bytesTotalColumn);
                    int status = cursor.getInt(statusColumn);

                    if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                        stopProgressTracker();
                        
                        // Trigger MediaScanner and fix seekability so the video becomes seekable in players
                        mainHandler.postDelayed(() -> {
                            try {
                                String sanitizedName = currentFilename.replaceAll("[\\\\/:*?\"<>|]", "_");
                                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                                File ytProDir = new File(downloadDir, "YTPRO");
                                File resultFile = new File(ytProDir, sanitizedName);
                                
                                Log.d(TAG, "Finalizing seekability for: " + resultFile.getAbsolutePath());

                                if (resultFile.exists() && resultFile.length() > 0) {
                                    Log.d(TAG, "Download finished, fixing seekability for: " + resultFile.getAbsolutePath());
                                    
                                    // Use MediaMuxer to fix seekability (especially for DASH/fragmented MP4s)
                                    com.google.android.youtube.pro.utils.MediaMuxerUtils.fixSeekability(activity, resultFile, new com.google.android.youtube.pro.utils.MediaMuxerUtils.MuxCallback() {
                                        @Override
                                        public void onSuccess(File outputFile) {
                                            Log.d(TAG, "Seekability fixed successfully for: " + outputFile.getAbsolutePath());
                                            mainHandler.post(() -> {
                                                setDownloadState(DownloadState.COMPLETED);
                                                if (progressCallback != null) {
                                                    progressCallback.onDownloadProgress(100, bytesTotal);
                                                    progressCallback.onDownloadCompleted(outputFile.getAbsolutePath());
                                                }
                                            });
                                        }

                                        @Override
                                        public void onFailure(Exception e) {
                                            Log.e(TAG, "Failed to fix seekability: " + e.getMessage());
                                            // Even if fix fails, notify completion of the original file
                                            mainHandler.post(() -> {
                                                setDownloadState(DownloadState.COMPLETED);
                                                if (progressCallback != null) {
                                                    progressCallback.onDownloadProgress(100, bytesTotal);
                                                    progressCallback.onDownloadCompleted(resultFile.getAbsolutePath());
                                                }
                                            });
                                        }
                                    });
                                } else {
                                    // Try fallback if file not found in expected location
                                    queryAndScanFallback(dm, currentDownloadId);
                                    mainHandler.post(() -> {
                                        setDownloadState(DownloadState.COMPLETED);
                                        if (progressCallback != null) {
                                            progressCallback.onDownloadProgress(100, bytesTotal);
                                            progressCallback.onDownloadCompleted("Download complete");
                                        }
                                    });
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to trigger MediaScanner", e);
                            }
                        }, 2000); // 2s delay to ensure file is finalized by system
                    } else if (status == android.app.DownloadManager.STATUS_FAILED) {
                        stopProgressTracker();
                        mainHandler.post(() -> {
                            setDownloadState(DownloadState.ERROR);
                            if (progressCallback != null) {
                                progressCallback.onError("Download failed in system manager");
                            }
                        });
                    } else if (bytesTotal > 0) {
                        int dl_progress = (int) ((bytesDownloaded * 100L) / bytesTotal);
                        mainHandler.post(() -> {
                            if (progressCallback != null) {
                                progressCallback.onDownloadProgress(dl_progress, bytesDownloaded);
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying progress", e);
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

    private void queryAndScanFallback(android.app.DownloadManager dm, long downloadId) {
        android.app.DownloadManager.Query query = new android.app.DownloadManager.Query();
        query.setFilterById(downloadId);
        try (android.database.Cursor cursor = dm.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                int localUriColumn = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                if (localUriColumn != -1) {
                    String localUri = cursor.getString(localUriColumn);
                    if (localUri != null) {
                        android.net.Uri uri = android.net.Uri.parse(localUri);
                        String path = uri.getPath();
                        if (path != null) {
                            File file = new File(path);
                            if (file.exists()) {
                                MediaScannerConnection.scanFile(activity, new String[]{file.getAbsolutePath()}, null, null);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fallback scan failed", e);
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
        if (currentDownloadId != -1) {
            android.app.DownloadManager dm = (android.app.DownloadManager) activity.getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            dm.remove(currentDownloadId);
            currentDownloadId = -1;
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
