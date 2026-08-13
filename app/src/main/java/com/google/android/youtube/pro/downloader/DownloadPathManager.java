package com.google.android.youtube.pro.downloader;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;

public class DownloadPathManager {
    private static final String TAG = "DownloadPathManager";
    private static final String APP_FOLDER = "YTPro";
    private static final String VIDEOS_FOLDER = "Videos";
    private static final String AUDIO_FOLDER = "Audio";

    private Context context;

    public DownloadPathManager(Context context) {
        this.context = context;
    }

    /**
     * Get the downloads directory for the app
     */
    public File getAppDownloadsDir() {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File appDir = new File(downloadsDir, APP_FOLDER);

        if (!appDir.exists()) {
            if (appDir.mkdirs()) {
                Log.d(TAG, "Created app folder: " + appDir.getAbsolutePath());
            }
        }

        return appDir;
    }

    /**
     * Get the videos subdirectory
     */
    public File getVideosDir() {
        File appDir = getAppDownloadsDir();
        File videosDir = new File(appDir, VIDEOS_FOLDER);

        if (!videosDir.exists()) {
            if (videosDir.mkdirs()) {
                Log.d(TAG, "Created videos folder: " + videosDir.getAbsolutePath());
            }
        }

        return videosDir;
    }

    /**
     * Get the audio subdirectory
     */
    public File getAudioDir() {
        File appDir = getAppDownloadsDir();
        File audioDir = new File(appDir, AUDIO_FOLDER);

        if (!audioDir.exists()) {
            if (audioDir.mkdirs()) {
                Log.d(TAG, "Created audio folder: " + audioDir.getAbsolutePath());
            }
        }

        return audioDir;
    }

    /**
     * Get the appropriate directory based on format
     */
    public File getDownloadDir(String format) {
        // Audio formats
        if (format.equals("mp3") || format.equals("m4a") || format.equals("aac") ||
            format.equals("flac") || format.equals("ogg") || format.equals("opus") ||
            format.equals("wav") || format.equals("webm")) {
            return getAudioDir();
        }

        // Video formats (default)
        return getVideosDir();
    }

    /**
     * Get the full file path for a download
     */
    public File getDownloadFile(String filename, String format) {
        File dir = getDownloadDir(format);
        return new File(dir, filename);
    }
}
