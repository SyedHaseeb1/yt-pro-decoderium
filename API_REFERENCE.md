# API Reference Guide

Complete API documentation for YTPro download system.

## 🔌 SaveNow.to API

Third-party service for video format detection and download preparation.

### 1. Get Available Formats

```
GET https://p.savenow.to/api/card2/?url={videoUrl}
```

**Purpose**: Retrieve available video formats and qualities for a URL

**Parameters**:
- `url` (string): YouTube video URL or ID

**Response**:
```json
{
  "success": true,
  "text": "",
  "title": "Video Title",
  "thumbnail_url": "https://...",
  "formats": [
    {
      "key": "360",
      "label": "MP4",
      "quality": "360p"
    },
    {
      "key": "4k",
      "label": "MP4",
      "quality": "4K"
    }
  ],
  "max_polls": 60
}
```

**Java Implementation**:
```java
SaveNowApiClient client = new SaveNowApiClient();
client.getFormats(videoUrl, new SaveNowApiClient.ApiCallback<SaveNowModels.DownloadResponse>() {
    @Override
    public void onSuccess(SaveNowModels.DownloadResponse result) {
        // result.formats contains available options
        for (FormatOption fmt : result.formats) {
            String key = fmt.key;        // "360", "4k", etc.
            String quality = fmt.quality; // "360p", "4K", etc.
        }
    }
    
    @Override
    public void onError(String error) {
        // Handle error
    }
});
```

---

### 2. Request Download Preparation

```
POST https://p.savenow.to/api/v2/download?button=1&format={format}&url={url}&iframe_source=youtube.com
```

**Purpose**: Start the video download preparation process

**Parameters**:
- `button` (int): Always 1
- `format` (string): Selected format key (e.g., "4k", "1080")
- `url` (string): Video URL (URL encoded)
- `iframe_source` (string): Always "youtube.com"

**Response**:
```json
{
  "success": 1,
  "id": "task-id-123",
  "progress_url": "https://p.savenow.to/api/v2/status?id=task-id-123",
  "text": "Preparing download...",
  "title": "Video Title"
}
```

**Java Implementation**:
```java
client.requestDownload(videoUrl, "4k", new SaveNowApiClient.ApiCallback<SaveNowModels.DownloadResponse>() {
    @Override
    public void onSuccess(SaveNowModels.DownloadResponse result) {
        if (result.success) {
            String progressUrl = result.progress_url;
            // Start polling progress
        }
    }
    
    @Override
    public void onError(String error) {
        // Handle error
    }
});
```

---

### 3. Poll Download Status

```
GET https://p.savenow.to/api/v2/status?id={taskId}
```

**Purpose**: Check download preparation progress (call repeatedly until ready)

**Response** (processing):
```json
{
  "success": 0,
  "progress": 500,
  "text": "Converting video...",
  "title": "Video Title"
}
```

**Response** (ready):
```json
{
  "success": 1,
  "progress": 1000,
  "download_url": "https://..../video.mp4",
  "title": "Video Title",
  "text": ""
}
```

**Response** (error):
```json
{
  "success": 0,
  "progress": 1000,
  "download_url": null,
  "text": "4K video not supported. Please select a lower quality."
}
```

**Java Implementation**:
```java
client.pollProgress(progressUrl, new SaveNowApiClient.ApiCallback<SaveNowModels.ProgressResponse>() {
    @Override
    public void onSuccess(SaveNowModels.ProgressResponse result) {
        int progress = result.progress / 10; // Convert 0-1000 to 0-100
        
        if (result.success == 1) {
            // Download ready
            String downloadUrl = result.download_url;
            // Start file download
        } else if (result.progress >= 1000 && result.download_url == null) {
            // Error occurred
            String errorMsg = result.text; // "4K video not supported..."
            // Show error to user
        } else {
            // Still processing, poll again
            pollProgress(progressUrl);
        }
    }
});
```

**Polling Configuration**:
- **Max Polls**: 30 (adjustable via `max_polls` in response)
- **Poll Interval**: 1 second
- **Max Wait**: ~30 seconds

---

## 🎬 DownloadManager API

Internal service for file download management.

```java
DownloadManager downloadMgr = new DownloadManager(context);
```

### Methods

**startDownload()**
```java
downloadMgr.startDownload(format, fileName);
```
Begins downloading file from URL stored in format.

**setProgressCallback()**
```java
downloadMgr.setProgressCallback(new DownloadManager.DownloadProgressCallback() {
    @Override
    public void onDownloadProgress(int percentage, long bytesDownloaded) {
        // Update UI with progress
    }
    
    @Override
    public void onDownloadCompleted(String filePath) {
        // File saved to filePath
    }
    
    @Override
    public void onError(String message) {
        // Download failed
    }
});
```

**cancelActiveDownload()**
```java
downloadMgr.cancelActiveDownload();
```
Stops the current download (used for pause/cancel).

**cleanup()**
```java
downloadMgr.cleanup();
```
Releases service binding and resources (call in onDestroy).

---

## 🎨 MediaMuxerUtils API

Video remuxing for codec compatibility.

```java
MediaMuxerUtils.fixSeekability(context, uri, new MediaMuxerUtils.MuxCallback() {
    @Override
    public void onSuccess(File outputFile) {
        // File remuxed and moved to Downloads/YTPRO
    }
    
    @Override
    public void onFailure(Exception e) {
        // Remuxing failed
    }
});
```

### Codec Support

| Codec | Container | Handler |
|-------|-----------|---------|
| AV1 | WebM | FFmpeg |
| VP9 | WebM | MediaMuxer |
| H.264 | MP4 | MediaMuxer |
| H.265 | MP4 | MediaMuxer |

### Audio Codec Handling

| Audio | Video | Decision |
|-------|-------|----------|
| Opus | VP9 | → WebM (Opus only in WebM) |
| Vorbis | VP9 | → WebM |
| AAC | H.264 | → MP4 |
| Opus | H.264 | → WebM (convert for compatibility) |

---

## 📊 Data Models

### FormatOption
```java
class FormatOption {
    String key;      // "360", "480", "720", "1080", "1440", "4k", "8k"
    String label;    // "MP4", "WEBM"
    String quality;  // "360p", "4K"
}
```

### DownloadResponse
```java
class DownloadResponse {
    boolean success;
    String id;
    String progress_url;
    String text;
    String title;
    String format;
    String thumbnail_url;
    int max_polls;
    List<FormatOption> formats;
}
```

### ProgressResponse
```java
class ProgressResponse {
    int success;           // 0 = processing, 1 = ready
    int progress;          // 0-1000 (represents 0-100%)
    String text;           // Status message
    String download_url;   // URL when success=1
    String title;
}
```

### DownloadFormat
```java
class DownloadFormat {
    String quality;        // "4K", "1080p"
    String formatType;     // "mp4", "webm"
    String codec;          // "H264", "VP9"
    String downloadUrl;    // File URL
    String formatKey;      // Original format key
}
```

---

## ❌ Error Codes & Messages

### HTTP Status Codes

| Code | Message | User Guidance |
|------|---------|---------------|
| 400 | Bad Request | "Invalid video URL or format" |
| 404 | Not Found | "Video not found or deleted" |
| 502 | Bad Gateway | "Server is busy, wait a few minutes" |
| 503 | Service Unavailable | "Server is overloaded, try later" |
| 504 | Gateway Timeout | "Server took too long, try again" |
| 429 | Too Many Requests | "Too many requests, wait a moment" |

### API Error Messages

| Error | Cause | Solution |
|-------|-------|----------|
| "4K not supported" | API limitation | Select 1440p or lower |
| "timeout" | Server slow | Try again or select lower quality |
| "not available" | Video protected | Try different video |
| "network error" | Connection issue | Check internet, try again |

---

## 🔐 Thread Safety

All API calls are **thread-safe**:
- API calls run on background thread
- Callbacks posted to main thread
- State changes synchronized
- No blocking on main thread

```java
// Safe: API call on background thread
new Thread(() -> {
    client.getFormats(url, callback);
}).start();

// Callback executed on main thread
callback.onSuccess(result);  // Safe to update UI
```

---

## 📈 Rate Limiting

**SaveNow.to API**:
- Polling max: 30 requests per video
- Format loading: 1 per video
- Request download: 1 per video
- Total: ~32 requests per download

**Recommendation**: Implement backoff for retries

```java
// Exponential backoff on error
int retries = 0;
while (retries < 3) {
    try {
        // API call
        break;
    } catch (Exception e) {
        long delay = (long) Math.pow(2, retries) * 1000; // 1s, 2s, 4s
        Thread.sleep(delay);
        retries++;
    }
}
```

---

## 🧪 Testing

### Mock API Responses

```java
// Test successful format loading
DownloadResponse response = new DownloadResponse();
response.success = true;
response.formats = Arrays.asList(
    new FormatOption("360", "MP4", "360p"),
    new FormatOption("4k", "MP4", "4K")
);

// Test error response
ProgressResponse errorResp = new ProgressResponse();
errorResp.success = 0;
errorResp.progress = 1000;
errorResp.text = "4K not supported";
errorResp.download_url = null;
```

---

**Last Updated**: 2026-08-17
**API Version**: 2
**Status**: Stable
