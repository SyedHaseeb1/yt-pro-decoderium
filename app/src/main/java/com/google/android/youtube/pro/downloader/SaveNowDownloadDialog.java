package com.google.android.youtube.pro.downloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.youtube.pro.R;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class SaveNowDownloadDialog {
    private static final String TAG = "SaveNowDownloadDialog";
    private static final int MAX_PROGRESS_POLLS = 30;
    private static final int POLL_INTERVAL = 1000;

    private Activity activity;
    private Handler mainHandler;
    private SaveNowApiClient apiClient;
    private AlertDialog dialog;
    private WebView webView;
    private String videoUrl;
    private String videoTitle;
    private String selectedFormat;
    private long downloadId = -1;
    private boolean isDownloading = false;
    private boolean isPaused = false;
    private String downloadFilePath;
    private String downloadFileUrl;
    private boolean wasVideoPlaying = true;
    private DownloadManager internalDownloader;
    private int currentMaxPolls = MAX_PROGRESS_POLLS;

    public SaveNowDownloadDialog(Activity activity) {
        this.activity = activity;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.apiClient = new SaveNowApiClient();
        this.webView = null;
    }

    public SaveNowDownloadDialog(Activity activity, WebView webView) {
        this.activity = activity;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.apiClient = new SaveNowApiClient();
        this.webView = webView;
    }

    public void show(String videoUrl, String videoTitle) {
        this.videoUrl = videoUrl;
        this.videoTitle = videoTitle;

        Log.d(TAG, "Opening download dialog for: " + videoTitle);

        LayoutInflater inflater = LayoutInflater.from(activity);
        View view = inflater.inflate(R.layout.dialog_download, null);

        // Setup UI elements
        ImageView closeButton = view.findViewById(R.id.closeButton);
        ImageView thumbnail = view.findViewById(R.id.cardThumbnail);
        TextView titleView = view.findViewById(R.id.cardTitle);
        TextView urlView = view.findViewById(R.id.cardUrl);
        Spinner formatSpinner = view.findViewById(R.id.cardFormat);
        View downloadBtn = view.findViewById(R.id.downloadButton);
        TextView downloadMessage = view.findViewById(R.id.downloadMessage);
        View downloadProgress = view.findViewById(R.id.downloadProgress);
        ImageView downloadIcon = view.findViewById(R.id.downloadIcon);
        ProgressBar downloadLoader = view.findViewById(R.id.downloadLoader);

        // Set initial values
        titleView.setText(videoTitle);
        urlView.setText(videoUrl);

        // Handle close button (cancel all operations)
        closeButton.setOnClickListener(v -> {
            if (internalDownloader != null) {
                internalDownloader.cancelActiveDownload();
                internalDownloader.cleanup();
            }
            isDownloading = false;
            isPaused = false;
            if (dialog != null) {
                dialog.dismiss();
            }
        });

        // Handle download button (Download / Pause / Resume)
        downloadBtn.setOnClickListener(v -> {
            if (isDownloading && !isPaused) {
                // Currently downloading → Pause
                pauseDownload(downloadBtn, downloadMessage);
            } else if (isPaused) {
                // Currently paused → Resume
                resumeDownload(downloadBtn, downloadMessage, downloadProgress);
            } else {
                // Not downloading → Start download
                if (selectedFormat != null) {
                    startDownloadFlow(downloadBtn, downloadMessage, downloadProgress, downloadIcon, downloadLoader);
                } else {
                    Toast.makeText(activity, "Please select a format", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Handle format selection
        formatSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                SaveNowModels.FormatOption format = (SaveNowModels.FormatOption) parent.getItemAtPosition(position);
                selectedFormat = format.key;
                Log.d(TAG, "Selected format: " + selectedFormat);

                // Show 4K+ warning for 4K and above resolutions
                if (selectedFormat != null && isHighResolution(selectedFormat)) {
                    downloadMessage.setText("⚠️ 4K+ may timeout - API limitation");
                } else {
                    downloadMessage.setText("Ready to download");
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                selectedFormat = null;
                downloadMessage.setText("Select quality");
            }
        });

        // Build and show dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setView(view);
        dialog = builder.create();
        dialog.setCancelable(false);

        // Resume video based on original state
        dialog.setOnDismissListener(dismissDialog -> {
            if (internalDownloader != null) {
                internalDownloader.cleanup();
            }
            resumeVideo();
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        // Pause video RIGHT BEFORE showing dialog
        pauseVideo();

        dialog.show();

        // Load formats AFTER dialog is shown
        loadFormats(thumbnail, formatSpinner, downloadBtn, downloadMessage);
    }

    private void loadFormats(ImageView thumbnail, Spinner formatSpinner, View downloadBtn, TextView downloadMessage) {
        downloadBtn.setEnabled(false);
        formatSpinner.setEnabled(false);
        downloadMessage.setText("Loading formats...");

        try {
            ProgressBar formatLoader = dialog.findViewById(R.id.downloadLoader);
            if (formatLoader != null) {
                formatLoader.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not find loader view", e);
        }

        // Generate YouTube thumbnail URL from video ID
        String videoId = extractVideoId(videoUrl);
        if (videoId != null && !videoId.isEmpty()) {
            String thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
            Log.d(TAG, "Loading thumbnail from YouTube: " + thumbnailUrl);
            loadThumbnail(thumbnail, thumbnailUrl);
        }

        apiClient.getFormats(videoUrl, new SaveNowApiClient.ApiCallback<SaveNowModels.DownloadResponse>() {
            @Override
            public void onSuccess(SaveNowModels.DownloadResponse result) {
                mainHandler.post(() -> {
                    try {
                        // Update max polls if provided by API
                        if (result.max_polls > 0) {
                            currentMaxPolls = result.max_polls;
                            Log.d(TAG, "Max polls updated from API: " + currentMaxPolls);
                        }

                        // Setup format spinner
                        List<SaveNowModels.FormatOption> formatList = new ArrayList<>();
                        if (result.formats != null && !result.formats.isEmpty()) {
                            formatList.addAll(result.formats);
                        } else {
                            // Fallback to defaults
                            SaveNowModels.FormatOption[] defaults = SaveNowModels.FormatList.getDefaultFormats();
                            for (SaveNowModels.FormatOption format : defaults) {
                                formatList.add(format);
                            }
                        }

                        ArrayAdapter<SaveNowModels.FormatOption> adapter = new ArrayAdapter<>(
                                activity,
                                android.R.layout.simple_spinner_item,
                                formatList
                        );
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        formatSpinner.setAdapter(adapter);

                        // Enable controls
                        formatSpinner.setEnabled(true);
                        downloadBtn.setEnabled(true);
                        downloadMessage.setText("Select quality");

                        try {
                            ProgressBar loader = dialog.findViewById(R.id.downloadLoader);
                            if (loader != null) {
                                loader.setVisibility(View.GONE);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Could not hide loader view", e);
                        }

                        Log.d(TAG, "Formats loaded successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "Error setting up UI", e);
                        showErrorDialog("Error loading formats: " + e.getMessage());
                    }
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    Log.e(TAG, "Error: " + error);
                    showErrorDialog(error);
                });
            }
        });
    }

    private void closeDialogWithError(String errorMessage) {
        if (internalDownloader != null) {
            internalDownloader.cancelActiveDownload();
            internalDownloader.cleanup();
        }

        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }

        new AlertDialog.Builder(activity)
                .setTitle("Download Error")
                .setMessage(errorMessage)
                .setPositiveButton("OK", (d, w) -> d.dismiss())
                .show();
    }

    private void startDownloadFlow(View downloadBtn, TextView downloadMessage, View downloadProgress, ImageView downloadIcon, ProgressBar downloadLoader) {
        downloadBtn.setEnabled(false);
        downloadMessage.setText("Requesting...");
        downloadProgress.setVisibility(View.GONE);
        if (downloadIcon != null) downloadIcon.setVisibility(View.GONE);
        if (downloadLoader != null) downloadLoader.setVisibility(View.VISIBLE);

        // Disable format selection during download
        View formatSpinner = dialog.findViewById(R.id.cardFormat);
        if (formatSpinner != null) {
            formatSpinner.setEnabled(false);
        }

        // Step 2: Request download
        apiClient.requestDownload(videoUrl, selectedFormat, new SaveNowApiClient.ApiCallback<SaveNowModels.DownloadResponse>() {
            @Override
            public void onSuccess(SaveNowModels.DownloadResponse result) {
                if (!result.success) {
                    mainHandler.post(() -> {
                        Toast.makeText(activity, "Download request failed", Toast.LENGTH_SHORT).show();
                        downloadBtn.setEnabled(true);
                        downloadMessage.setText("Download");
                        downloadProgress.setVisibility(View.GONE);
                    });
                    return;
                }

                Log.d(TAG, "Download task created: " + result.id);
                
                // Update max polls if provided in this step
                if (result.max_polls > 0) {
                    currentMaxPolls = result.max_polls;
                }

                // Step 3: Poll progress
                startProgressPolling(result.progress_url, downloadBtn, downloadMessage, downloadProgress, downloadIcon, downloadLoader, 0);
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    Log.e(TAG, "Error: " + error);
                    showErrorDialog(error);
                });
            }
        });
    }

    private void startProgressPolling(String progressUrl, View downloadBtn, TextView downloadMessage, View downloadProgress, ImageView downloadIcon, ProgressBar downloadLoader, int pollCount) {
        if (pollCount >= currentMaxPolls) {
            mainHandler.post(() -> {
                String timeoutMsg = "Download preparation timeout. Video may not be downloadable or API may have restrictions.";
                Log.e(TAG, "Download polling timeout after " + pollCount + " polls");
                showErrorDialog(timeoutMsg);
            });
            return;
        }

        mainHandler.postDelayed(() -> {
            apiClient.pollProgress(progressUrl, new SaveNowApiClient.ApiCallback<SaveNowModels.ProgressResponse>() {
                @Override
                public void onSuccess(SaveNowModels.ProgressResponse result) {
                    mainHandler.post(() -> {
                        int percentage = (result.progress / 10);
                        downloadMessage.setText("Preparing... " + percentage + "%");

                        // Update progress width
                        int progressWidth = (result.progress / 10); // Convert 0-1000 to 0-100
                        downloadProgress.getLayoutParams().width = (int) (downloadBtn.getWidth() * progressWidth / 100f);
                        downloadProgress.setVisibility(View.VISIBLE);

                        if (result.success == 1) {
                            // Step 4: Download file
                            // Use the actual format returned by the API if possible
                            String apiFormat = result.format != null && !result.format.isEmpty() ? result.format.toLowerCase() : selectedFormat;
                            downloadFile(result.download_url, result.title, apiFormat, downloadBtn, downloadMessage, downloadProgress, downloadIcon, downloadLoader);
                        } else if (result.progress >= 1000 && (result.download_url == null || result.download_url.isEmpty())) {
                            // API finished processing but download not available (error state)
                            // Use API's text message directly - it's already user-friendly
                            String errorMsg = result.text != null && !result.text.isEmpty()
                                ? result.text
                                : "This video cannot be downloaded. Try a different video.";

                            Log.e(TAG, "API error: " + errorMsg);
                            showErrorDialog(errorMsg);
                        } else {
                            // Continue polling
                            startProgressPolling(progressUrl, downloadBtn, downloadMessage, downloadProgress, downloadIcon, downloadLoader, pollCount + 1);
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    mainHandler.post(() -> {
                        Log.e(TAG, "Poll error: " + error);
                        showErrorDialog(error);
                    });
                }
            });
        }, POLL_INTERVAL);
    }

    private void downloadFile(String downloadUrl, String fileName, String actualFormat, View downloadBtn, TextView downloadMessage, View downloadProgress, ImageView downloadIcon, ProgressBar downloadLoader) {
        Log.d(TAG, "Download URL ready: " + downloadUrl + " (Format: " + actualFormat + ")");

        try {
            // Store for resume functionality
            downloadFileUrl = downloadUrl;
            isDownloading = true;
            isPaused = false;
            downloadMessage.setText("Downloading... 0%");
            downloadBtn.setEnabled(true);  // Keep enabled for pause

            // Determine format details based on actual format from API or selected key
            String formatType = determineFormatType(actualFormat);
            String quality = determineQuality(actualFormat);
            String codec = determineCodec(actualFormat);

            // Create DownloadFormat object for the existing DownloadManager API
            DownloadManager.DownloadFormat format = new DownloadManager.DownloadFormat(
                    quality,
                    formatType,
                    codec,
                    "Unknown"
            );
            format.downloadUrl = downloadUrl;
            format.formatKey = selectedFormat;

            // Use DownloadManager to handle the actual file download
            // Pass Application Context to ensure the service binding is decoupled from Activity lifecycle
            internalDownloader = new DownloadManager(activity.getApplicationContext());
            internalDownloader.setProgressCallback(new DownloadManager.DownloadProgressCallback() {
                @Override
                public void onFormatDiscovered(List<DownloadManager.DownloadFormat> formats) {
                    // Not used in this flow
                }

                @Override
                public void onDownloadProgress(int percentage, long bytesDownloaded) {
                    if (isDownloading && !isPaused) {
                        mainHandler.post(() -> {
                            if (dialog == null || !dialog.isShowing()) return;
                            downloadMessage.setText("Downloading... " + percentage + "%");
                            int progressWidth = (int) (downloadBtn.getWidth() * percentage / 100f);
                            downloadProgress.getLayoutParams().width = progressWidth;
                        });
                    }
                }

                @Override
                public void onDownloadCompleted(String filePath) {
                    mainHandler.post(() -> {
                        isDownloading = false;
                        isPaused = false;
                        downloadFilePath = filePath;
                        
                        if (dialog != null && dialog.isShowing()) {
                            downloadProgress.setVisibility(View.GONE);
                            downloadMessage.setText("Completed!");
                            downloadBtn.setEnabled(false);
                        }
                        
                        if (internalDownloader != null) {
                            internalDownloader.cleanup();
                        }
                    });
                }

                @Override
                public void onError(String message) {
                    mainHandler.post(() -> {
                        isDownloading = false;
                        showErrorDialog("Download failed: " + message);

                        if (internalDownloader != null) {
                            internalDownloader.cleanup();
                        }
                    });
                }

                @Override
                public void onStateChanged(DownloadManager.DownloadState newState) {
                    Log.d(TAG, "Download state changed: " + newState);
                    if (newState == DownloadManager.DownloadState.DOWNLOADING || newState == DownloadManager.DownloadState.COMPLETED) {
                        mainHandler.post(() -> {
                            if (dialog != null && dialog.isShowing()) {
                                dialog.dismiss();
                            }
                        });
                    }
                }
            });

            internalDownloader.startDownload(format, fileName.isEmpty() ? videoTitle : fileName);

        } catch (Exception e) {
            Log.e(TAG, "Error downloading file", e);
            showErrorDialog("Error: " + e.getMessage());
            isDownloading = false;
        }
    }

    private void pauseVideo() {
        if (webView != null) {
            try {
                webView.evaluateJavascript(
                        "(function() { " +
                                "  const video = document.querySelector('video'); " +
                                "  if (video) { " +
                                "    window.ytproWasPlaying = !video.paused; " +
                                "    console.log('PAUSE: Video was playing:', window.ytproWasPlaying); " +
                                "    video.pause(); " +
                                "    console.log('PAUSE: Dialog paused video'); " +
                                "  } " +
                                "})()",
                        null
                );
                Log.d(TAG, "Video paused");
            } catch (Exception e) {
                Log.e(TAG, "Error pausing video", e);
            }
        }
    }

    private void resumeVideo() {
        if (webView != null) {
            try {
                webView.evaluateJavascript(
                        "(function() { " +
                                "  const video = document.querySelector('video'); " +
                                "  if (video && window.ytproWasPlaying) { " +
                                "    video.play(); " +
                                "    console.log('RESUME: Restoring video to playing state'); " +
                                "  } else { " +
                                "    console.log('RESUME: Video was paused, keeping paused'); " +
                                "  } " +
                                "})()",
                        null
                );
                Log.d(TAG, "Video state restored");
            } catch (Exception e) {
                Log.e(TAG, "Error resuming video", e);
            }
        }
    }

    private void pauseDownload(View downloadBtn, TextView downloadMessage) {
        if (internalDownloader != null) {
            internalDownloader.cancelActiveDownload();

            isPaused = true;
            isDownloading = false;
            downloadMessage.setText("Resume");
            Log.d(TAG, "Download canceled via internalDownloader");
        }
    }

    private void resumeDownload(View downloadBtn, TextView downloadMessage, View downloadProgress) {
        if (isPaused && downloadFileUrl != null) {
            isPaused = false;
            downloadMessage.setText("Downloading... 0%");
            downloadProgress.setVisibility(View.VISIBLE);

            // Re-start the download from the beginning
            downloadFile(downloadFileUrl, videoTitle, selectedFormat, downloadBtn, downloadMessage, downloadProgress, null, null);
            Log.d(TAG, "Download resumed");
        }
    }

    private String extractVideoId(String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) {
            return null;
        }

        // Pattern for youtube.com/watch?v=VIDEO_ID
        java.util.regex.Pattern pattern1 = java.util.regex.Pattern.compile("(?:youtube\\.com\\/watch\\?v=|youtu\\.be\\/|m\\.youtube\\.com\\/watch\\?v=)([a-zA-Z0-9_-]{11})");
        java.util.regex.Matcher matcher1 = pattern1.matcher(videoUrl);
        if (matcher1.find()) {
            return matcher1.group(1);
        }

        // Pattern for short URL youtu.be/VIDEO_ID
        java.util.regex.Pattern pattern2 = java.util.regex.Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})");
        java.util.regex.Matcher matcher2 = pattern2.matcher(videoUrl);
        if (matcher2.find()) {
            return matcher2.group(1);
        }

        Log.w(TAG, "Could not extract video ID from URL: " + videoUrl);
        return null;
    }

    private String determineFormatType(String formatKey) {
        switch (formatKey) {
            case "mp3":
            case "m4a":
            case "aac":
            case "flac":
            case "ogg":
            case "opus":
            case "wav":
            case "webm":
                return formatKey;
            case "360":
            case "480":
            case "720":
            case "1080":
            case "1440":
            case "4k":
            case "8k":
                return "mp4";
            default:
                return "mp4";
        }
    }

    private String determineQuality(String formatKey) {
        switch (formatKey) {
            case "360":
                return "360p";
            case "480":
                return "480p";
            case "720":
                return "720p";
            case "1080":
                return "1080p";
            case "1440":
                return "1440p";
            case "4k":
                return "4K";
            case "8k":
                return "8K";
            case "mp3":
                return "MP3";
            case "m4a":
                return "M4A";
            case "aac":
                return "AAC";
            case "flac":
                return "FLAC";
            case "ogg":
                return "OGG";
            case "opus":
                return "OPUS";
            case "wav":
                return "WAV";
            case "webm":
                return "WEBM";
            default:
                return formatKey.toUpperCase();
        }
    }

    private String determineCodec(String formatKey) {
        if (formatKey.matches("360|480|720|1080|1440|4k|8k")) {
            return "H264";
        }
        // Audio formats don't have specific codecs in this context
        return formatKey.toUpperCase();
    }

    private boolean isHighResolution(String formatKey) {
        if (formatKey == null) return false;
        String lower = formatKey.toLowerCase();
        // Check for 4K, 8K, and above
        return lower.matches("4k|8k|2160|4320|5k|6k|7k") ||
               lower.contains("4k") ||
               lower.contains("8k") ||
               lower.contains("2160") ||
               lower.contains("4320");
    }

    private String getUserFriendlyError(String technicalError) {
        if (technicalError == null) {
            return "Something went wrong. Please try again.";
        }

        String lower = technicalError.toLowerCase();

        // If message is already simple/user-friendly from API, use it directly
        if (isSimpleUserMessage(technicalError)) {
            return technicalError;
        }

        // Parse technical errors
        if (lower.contains("502") || lower.contains("503") || lower.contains("overloaded")) {
            return "The download server is busy right now.\n\nPlease wait a few minutes and try again.";
        }

        if (lower.contains("504") || lower.contains("timeout") || lower.contains("preparation timeout")) {
            return "The download is taking too long.\n\nThis video might be:\n• Protected by YouTube\n• Too large to process\n• Temporarily unavailable\n\nTry a lower quality or a different video.";
        }

        if (lower.contains("not available for download")) {
            return "This video cannot be downloaded.\n\nSome videos are protected by YouTube or the content creator.\n\nTry a different video.";
        }

        if (lower.contains("400") || lower.contains("bad request")) {
            return "The video URL is invalid or the video is no longer available.\n\nPlease check the URL and try again.";
        }

        if (lower.contains("404") || lower.contains("not found")) {
            return "This video could not be found.\n\nThe video might have been deleted or made private.\n\nPlease check the URL.";
        }

        if (lower.contains("network") || lower.contains("connection")) {
            return "Network error. Please check your internet connection and try again.";
        }

        if (lower.contains("failed to load formats")) {
            return "Could not get video details.\n\nPlease check your internet connection and try again.";
        }

        if (lower.contains("download failed")) {
            return "The file could not be saved to your device.\n\nPlease check:\n• You have enough storage space\n• You gave the app permission to save files\n\nThen try again.";
        }

        if (lower.contains("error loading formats")) {
            return "Could not load video options.\n\nPlease try again.";
        }

        // Fallback for unknown errors
        return "Something went wrong.\n\nPlease try again. If the problem continues, try:\n• Checking your internet connection\n• Restarting the app\n• Trying a different video";
    }

    private boolean isSimpleUserMessage(String message) {
        if (message == null) return false;

        String lower = message.toLowerCase();

        // Check for API messages that are already user-friendly
        return lower.contains("4k") ||
               lower.contains("8k") ||
               lower.contains("not supported") ||
               lower.contains("not available") ||
               lower.contains("protected") ||
               lower.contains("restricted") ||
               lower.contains("please select") ||
               lower.contains("try") ||
               lower.contains("lower quality");
    }

    private void showErrorDialog(String errorMessage) {
        // Dismiss the current download dialog if showing
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }

        // Cleanup downloader
        if (internalDownloader != null) {
            internalDownloader.cancelActiveDownload();
            internalDownloader.cleanup();
        }

        // Create error dialog
        LayoutInflater inflater = LayoutInflater.from(activity);
        View errorView = inflater.inflate(R.layout.dialog_error, null);

        // Convert technical error to user-friendly message
        String userMessage = getUserFriendlyError(errorMessage);
        Log.d(TAG, "Technical error: " + errorMessage);
        Log.d(TAG, "User message: " + userMessage);

        // Set error message
        TextView errorMsg = errorView.findViewById(R.id.errorMessage);
        if (errorMsg != null) {
            errorMsg.setText(userMessage);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setView(errorView);
        AlertDialog errorDialog = builder.create();
        errorDialog.setCancelable(false);

        if (errorDialog.getWindow() != null) {
            errorDialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        // Handle OK button
        View okButton = errorView.findViewById(R.id.errorOkButton);
        if (okButton != null) {
            okButton.setOnClickListener(v -> {
                errorDialog.dismiss();
                resumeVideo();
            });
        }

        errorDialog.show();
    }

    private void loadThumbnail(ImageView imageView, String imageUrl) {
        new Thread(() -> {
            try {
                URL url = new URL(imageUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.connect();

                InputStream input = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                input.close();

                mainHandler.post(() -> {
                    imageView.setImageBitmap(bitmap);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading thumbnail", e);
            }
        }).start();
    }
    
}
