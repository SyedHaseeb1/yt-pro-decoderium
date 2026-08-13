package com.google.android.youtube.pro.downloader;

import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class SaveNowApiClient {
    private static final String TAG = "SaveNowApiClient";
    private static final String BASE_URL = "https://p.savenow.to/api";

    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public void getFormats(String videoUrl, ApiCallback<SaveNowModels.DownloadResponse> callback) {
        new Thread(() -> {
            try {
                String encodedUrl = Uri.encode(videoUrl);
                String apiUrl = BASE_URL + "/card2/?url=" + encodedUrl;

                Log.d(TAG, "Loading formats from: " + apiUrl);

                String htmlResponse = fetchHtml(apiUrl);
                SaveNowModels.DownloadResponse response = extractFromHtml(htmlResponse, videoUrl);

                callback.onSuccess(response);
            } catch (Exception e) {
                Log.e(TAG, "Error loading formats", e);
                callback.onError("Failed to load formats: " + e.getMessage());
            }
        }).start();
    }

    private SaveNowModels.DownloadResponse extractFromHtml(String html, String videoUrl) {
        SaveNowModels.DownloadResponse response = new SaveNowModels.DownloadResponse();
        response.info = new SaveNowModels.DownloadResponse.VideoInfo();

        // Extract thumbnail from cardThumbnail element
        String bgImagePattern = "id=\"cardThumbnail\"[^>]*style=\"[^\"]*background-image:\\s*url\\(([^)]+)\\)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(bgImagePattern);
        java.util.regex.Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            response.thumbnail_url = matcher.group(1).replace("'", "").replace("\"", "");
            response.info.image = response.thumbnail_url;
        }

        // Extract title from cardTitle element
        String titlePattern = "id=\"cardTitle\"[^>]*>([^<]+)</";
        pattern = java.util.regex.Pattern.compile(titlePattern);
        matcher = pattern.matcher(html);
        if (matcher.find()) {
            response.title = matcher.group(1).trim();
            response.info.title = response.title;
        }

        return response;
    }

    public void requestDownload(String videoUrl, String format, ApiCallback<SaveNowModels.DownloadResponse> callback) {
        new Thread(() -> {
            try {
                String encodedUrl = Uri.encode(videoUrl);
                String apiUrl = BASE_URL + "/v2/download?button=1&format=" + format + "&url=" + encodedUrl + "&iframe_source=youtube.com";

                Log.d(TAG, "Requesting download: " + apiUrl);

                JSONObject json = fetchJson(apiUrl);
                SaveNowModels.DownloadResponse response = SaveNowModels.DownloadResponse.fromJson(json);
                callback.onSuccess(response);
            } catch (Exception e) {
                Log.e(TAG, "Error requesting download", e);
                callback.onError("Failed to request download: " + e.getMessage());
            }
        }).start();
    }

    public void pollProgress(String progressUrl, ApiCallback<SaveNowModels.ProgressResponse> callback) {
        new Thread(() -> {
            try {
                Log.d(TAG, "Polling progress: " + progressUrl);

                JSONObject json = fetchJson(progressUrl);
                SaveNowModels.ProgressResponse response = SaveNowModels.ProgressResponse.fromJson(json);
                callback.onSuccess(response);
            } catch (Exception e) {
                Log.e(TAG, "Error polling progress", e);
                callback.onError("Failed to poll progress: " + e.getMessage());
            }
        }).start();
    }

    private String fetchHtml(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP Error: " + responseCode);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
            reader.close();

            return response.toString();
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject fetchJson(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP Error: " + responseCode);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            String responseText = response.toString();
            Log.d(TAG, "API Response length: " + responseText.length());

            return new JSONObject(responseText);
        } finally {
            connection.disconnect();
        }
    }
}
