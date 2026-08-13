package com.google.android.youtube.pro.downloader;

import org.json.JSONObject;

public class SaveNowModels {

    public static class FormatOption {
        public String key;
        public String label;
        public String quality;

        public FormatOption(String key, String label, String quality) {
            this.key = key;
            this.label = label;
            this.quality = quality;
        }

        @Override
        public String toString() {
            return quality.isEmpty() ? label : label + " (" + quality + ")";
        }
    }

    public static class FormatList {
        public String title;
        public String thumbnailUrl;
        public FormatOption[] formats;

        public FormatList(String title, String thumbnailUrl) {
            this.title = title;
            this.thumbnailUrl = thumbnailUrl;
            this.formats = getDefaultFormats();
        }

        public static FormatOption[] getDefaultFormats() {
            return new FormatOption[]{
                    new FormatOption("mp3", "MP3", ""),
                    new FormatOption("m4a", "M4A", ""),
                    new FormatOption("360", "MP4", "360p"),
                    new FormatOption("480", "MP4", "480p"),
                    new FormatOption("720", "MP4", "720p"),
                    new FormatOption("1080", "MP4", "1080p"),
                    new FormatOption("1440", "MP4", "1440p"),
                    new FormatOption("4k", "MP4", "4K"),
                    new FormatOption("8k", "MP4", "8K"),
                    new FormatOption("webm", "WEBM", "Audio"),
                    new FormatOption("aac", "AAC", ""),
                    new FormatOption("flac", "FLAC", ""),
                    new FormatOption("ogg", "OGG", ""),
                    new FormatOption("opus", "OPUS", ""),
                    new FormatOption("wav", "WAV", ""),
            };
        }
    }

    public static class DownloadResponse {
        public boolean success;
        public String id;
        public String progress_url;
        public String text;
        public String title;
        public String format;
        public String full_format;
        public String thumbnail_url;
        public VideoInfo info;

        public static class VideoInfo {
            public String title;
            public String image;
        }

        public static DownloadResponse fromJson(JSONObject json) throws Exception {
            DownloadResponse response = new DownloadResponse();
            response.success = json.optBoolean("success", false);
            response.id = json.optString("id", "");
            response.progress_url = json.optString("progress_url", "");
            response.text = json.optString("text", "");
            response.title = json.optString("title", "");
            response.format = json.optString("format", "");
            response.full_format = json.optString("full_format", "");
            response.thumbnail_url = json.optString("thumbnail_url", "");

            JSONObject infoJson = json.optJSONObject("info");
            if (infoJson != null) {
                response.info = new VideoInfo();
                response.info.title = infoJson.optString("title", "");
                response.info.image = infoJson.optString("image", "");
            }

            return response;
        }
    }

    public static class ProgressResponse {
        public int success;
        public int progress;
        public String text;
        public String title;
        public String format;
        public String full_format;
        public String download_url;

        public static ProgressResponse fromJson(JSONObject json) throws Exception {
            ProgressResponse response = new ProgressResponse();
            response.success = json.optInt("success", 0);
            response.progress = json.optInt("progress", 0);
            response.text = json.optString("text", "");
            response.title = json.optString("title", "");
            response.format = json.optString("format", "");
            response.full_format = json.optString("full_format", "");
            response.download_url = json.optString("download_url", "");
            return response;
        }
    }
}
