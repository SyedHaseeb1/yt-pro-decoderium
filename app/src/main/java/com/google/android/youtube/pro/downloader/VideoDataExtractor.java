package com.google.android.youtube.pro.downloader;

import android.util.Log;

public class VideoDataExtractor {
    private static final String TAG = "YTPRO_VideoExtractor";

    public static class VideoData {
        public String videoId;
        public String title;
        public String channelName;
        public String thumbnailUrl;
        public long durationMs;
        public String viewCount;

        public VideoData(String videoId) {
            this.videoId = videoId;
            this.title = "Video";
            this.durationMs = 0;
        }

        @Override
        public String toString() {
            return "VideoData{" +
                    "videoId='" + videoId + '\'' +
                    ", title='" + title + '\'' +
                    ", duration=" + durationMs + "ms" +
                    '}';
        }
    }

    /**
     * Extracts video ID from YouTube URL
     */
    public static String extractVideoId(String url) {
        if (url == null) return null;

        // Format: youtube.com/watch?v=VIDEO_ID
        String pattern1 = "(?:youtube\\.com/watch\\?v=|youtu\\.be/)([a-zA-Z0-9_-]{11})";

        // Format: youtube.com/shorts/VIDEO_ID
        String pattern2 = "youtube\\.com/shorts/([a-zA-Z0-9_-]{11})";

        java.util.regex.Pattern p1 = java.util.regex.Pattern.compile(pattern1);
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile(pattern2);

        java.util.regex.Matcher m1 = p1.matcher(url);
        if (m1.find()) {
            return m1.group(1);
        }

        java.util.regex.Matcher m2 = p2.matcher(url);
        if (m2.find()) {
            return m2.group(1);
        }

        Log.w(TAG, "Could not extract video ID from: " + url);
        return null;
    }

    /**
     * Creates VideoData from extracted information
     */
    public static VideoData createVideoData(String url, String title, long durationMs) {
        String videoId = extractVideoId(url);
        if (videoId == null) {
            videoId = "unknown";
        }

        VideoData data = new VideoData(videoId);
        data.title = title != null ? title : "Video_" + videoId;
        data.durationMs = durationMs;

        Log.d(TAG, "Created: " + data);
        return data;
    }

    /**
     * Sanitizes filename for safe file storage
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null) {
            return "video_download";
        }

        return filename
            .replaceAll("[<>:\"/\\\\|?*]", "_")
            .replaceAll("\\s+", "_")
            .replaceAll("_+", "_")
            .replaceFirst("_+$", "")
            .replaceFirst("^_+", "");
    }

    /**
     * Formats bytes to human readable format
     */
    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";

        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.2f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}
