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

        // Load formats
        loadFormats(thumbnail, formatSpinner, downloadBtn);

        // Handle close button
        closeButton.setOnClickListener(v -> {
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
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                selectedFormat = null;
            }
        });

        // Build and show dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setView(view);
        dialog = builder.create();
        dialog.setCancelable(false);

        // Resume video based on original state
        dialog.setOnDismissListener(dismissDialog -> {
            resumeVideo();
        });

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        // Pause video RIGHT BEFORE showing dialog
        pauseVideo();

        dialog.show();
    }

    private void loadFormats(ImageView thumbnail, Spinner formatSpinner, View downloadBtn) {
        downloadBtn.setEnabled(false);

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

                        // Setup format spinner
                        SaveNowModels.FormatOption[] formats = SaveNowModels.FormatList.getDefaultFormats();
                        List<SaveNowModels.FormatOption> formatList = new ArrayList<>();
                        for (SaveNowModels.FormatOption format : formats) {
                            formatList.add(format);
                        }

                        ArrayAdapter<SaveNowModels.FormatOption> adapter = new ArrayAdapter<>(
                                activity,
                                android.R.layout.simple_spinner_item,
                                formatList
                        );
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        formatSpinner.setAdapter(adapter);

                        // Enable download button
                        downloadBtn.setEnabled(true);

                        Log.d(TAG, "Formats loaded successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "Error setting up UI", e);
                        Toast.makeText(activity, "Error loading formats", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    Log.e(TAG, "Error: " + error);
                    Toast.makeText(activity, error, Toast.LENGTH_SHORT).show();
                    downloadBtn.setEnabled(false);
                });
            }
        });
    }

    private void startDownloadFlow(View downloadBtn, TextView downloadMessage, View downloadProgress, ImageView downloadIcon, ProgressBar downloadLoader) {
        downloadBtn.setEnabled(false);
        downloadMessage.setText("Requesting...");
        downloadProgress.setVisibility(View.GONE);
        if (downloadIcon != null) downloadIcon.setVisibility(View.GONE);
        if (downloadLoader != null) downloadLoader.setVisibility(View.VISIBLE);

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

                // Step 3: Poll progress
                startProgressPolling(result.progress_url, downloadBtn, downloadMessage, downloadProgress, downloadIcon, downloadLoader, 0);
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    Log.e(TAG, "Error: " + error);
                    Toast.makeText(activity, error, Toast.LENGTH_SHORT).show();
                    downloadBtn.setEnabled(true);
                    downloadMessage.setText("Download");
                    downloadProgress.setVisibility(View.GONE);
                });
            }
        });
    }

    private void startProgressPolling(String progressUrl, View downloadBtn, TextView downloadMessage, View downloadProgress, ImageView downloadIcon, ProgressBar downloadLoader, int pollCount) {
        if (pollCount >= MAX_PROGRESS_POLLS) {
            mainHandler.post(() -> {
                Toast.makeText(activity, "Download timeout", Toast.LENGTH_SHORT).show();
                downloadBtn.setEnabled(true);
                downloadMessage.setText("Download");
                downloadProgress.setVisibility(View.GONE);
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
                        downloadMessage.setText("Retry");
                        downloadBtn.setEnabled(true);
                        if (downloadLoader != null) downloadLoader.setVisibility(View.GONE);
                        if (downloadIcon != null) downloadIcon.setVisibility(View.VISIBLE);
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
            internalDownloader = new DownloadManager(activity);
            internalDownloader.setProgressCallback(new DownloadManager.DownloadProgressCallback() {
                @Override
                public void onFormatDiscovered(List<DownloadManager.DownloadFormat> formats) {
                    // Not used in this flow
                }

                @Override
                public void onDownloadProgress(int percentage, long bytesDownloaded) {
                    if (isDownloading && !isPaused) {
                        mainHandler.post(() -> {
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
                        downloadProgress.setVisibility(View.GONE);
                        Toast.makeText(activity, "Downloaded: " + filePath, Toast.LENGTH_SHORT).show();
                        downloadMessage.setText("Completed!");
                        downloadBtn.setEnabled(false);
                    });
                }

                @Override
                public void onError(String message) {
                    mainHandler.post(() -> {
                        isDownloading = false;
                        downloadProgress.setVisibility(View.GONE);
                        Toast.makeText(activity, "Download failed: " + message, Toast.LENGTH_SHORT).show();
                        downloadMessage.setText("Retry");
                        downloadBtn.setEnabled(true);
                        if (downloadLoader != null) downloadLoader.setVisibility(View.GONE);
                        if (downloadIcon != null) downloadIcon.setVisibility(View.VISIBLE);
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
            Toast.makeText(activity, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            isDownloading = false;
            downloadBtn.setEnabled(true);
            downloadMessage.setText("Retry");
            downloadProgress.setVisibility(View.GONE);
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
            Toast.makeText(activity, "Download canceled", Toast.LENGTH_SHORT).show();
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
