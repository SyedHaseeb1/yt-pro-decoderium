package com.google.android.youtube.pro.utils;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import com.google.android.youtube.pro.R;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class DownloadUtils {

    public static long downloadFile(Activity activity, String filename, String url, String mtype) {
        if (activity == null) return -1;

        // Ensure we are on the UI thread for permission checks and Toasts
        activity.runOnUiThread(() -> {
            Context appContext = activity.getApplicationContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                    activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {

                Toast.makeText(appContext, appContext.getString(R.string.grant_storage), Toast.LENGTH_SHORT).show();
                activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            }
        });

        try {
            // Sanitize filename: replace invalid characters with underscores
            String sanitizedName = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
            
            // Fix MIME type if it's a known format but generic type
            String correctedMimeType = mtype;
            if (sanitizedName.toLowerCase().endsWith(".mp3")) correctedMimeType = "audio/mpeg";
            else if (sanitizedName.toLowerCase().endsWith(".m4a")) correctedMimeType = "audio/mp4";
            else if (sanitizedName.toLowerCase().endsWith(".mp4")) correctedMimeType = "video/mp4";

            DownloadManager downloadManager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));

            request.setTitle(sanitizedName)
                    .setDescription(sanitizedName)
                    .setMimeType(correctedMimeType)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "YTPRO/" + sanitizedName)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE | DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            // Allow the file to be scanned by MediaScanner so duration/seeking works
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                request.allowScanningByMediaScanner();
            }

            long id = downloadManager.enqueue(request);
            Context appContext = activity.getApplicationContext();
            activity.runOnUiThread(() -> Toast.makeText(appContext, appContext.getString(R.string.dl_started), Toast.LENGTH_SHORT).show());
            return id;
        } catch (Exception e) {
            Context appContext = activity.getApplicationContext();
            activity.runOnUiThread(() -> Toast.makeText(appContext, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            return -1;
        }
    }
}
