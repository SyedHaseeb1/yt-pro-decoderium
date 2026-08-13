package com.google.android.youtube.pro.downloader;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONObject;

public class DownloadHandler {
    private static final String TAG = "DownloadHandler";
    private Activity activity;
    private Handler mainHandler;
    private WebView webView;

    public DownloadHandler(Activity activity) {
        this.activity = activity;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.webView = null;
    }

    public DownloadHandler(Activity activity, WebView webView) {
        this.activity = activity;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.webView = webView;
    }

    @JavascriptInterface
    public void openDownloadDialog(String videoDataJson) {
        mainHandler.post(() -> {
            try {
                JSONObject json = new JSONObject(videoDataJson);
                String videoUrl = json.optString("url", "https://www.youtube.com");
                String videoTitle = json.optString("title", "video");

                Log.d(TAG, "Opening download dialog for: " + videoTitle);

                // Use new SaveNow native implementation with WebView disabled
                SaveNowDownloadDialog dialog = webView != null
                    ? new SaveNowDownloadDialog(activity, webView)
                    : new SaveNowDownloadDialog(activity);
                dialog.show(videoUrl, videoTitle);

            } catch (Exception e) {
                Log.e(TAG, "Error parsing video data", e);
            }
        });
    }
}
