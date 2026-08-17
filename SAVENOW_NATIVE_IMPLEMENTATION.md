# SaveNow.to Native Android Implementation

## Overview
This document describes the native Android implementation of SaveNow.to download flow using XML dialogs and Java code (no WebView scraping).

## Architecture

### 4-Step API Flow

```
┌──────────────────────────────────────────────────────────────┐
│ Step 1: Load Formats                                         │
│ GET /api/card2/?url={VIDEO_URL}                             │
│ Response: HTML with video title, thumbnail, format options   │
└────────────────┬─────────────────────────────────────────────┘
                 │
                 ↓
        ┌────────────────────┐
        │ Show Format Dialog  │
        │ - Thumbnail image   │
        │ - Video title       │
        │ - Format spinner    │
        │ - Download button   │
        └────────┬───────────┘
                 │
                 ↓
┌──────────────────────────────────────────────────────────────┐
│ Step 2: Request Download                                     │
│ GET /api/v2/download?format={FORMAT}&url={URL}&button=1     │
│ Response: JSON with task ID and progress URL                │
└────────────────┬─────────────────────────────────────────────┘
                 │
                 ↓
        ┌────────────────────┐
        │ Show Progress Bar   │
        │ "Preparing... 50%"  │
        └────────┬───────────┘
                 │
                 ↓
┌──────────────────────────────────────────────────────────────┐
│ Step 3: Poll Progress (max 30 polls, 1s interval)            │
│ GET {progress_url}                                           │
│ Loop until: success == 1                                     │
│ Response: JSON with download_url when ready                  │
└────────────────┬─────────────────────────────────────────────┘
                 │
                 ↓
┌──────────────────────────────────────────────────────────────┐
│ Step 4: Download File                                        │
│ GET {download_url}                                           │
│ Download with progress using Android DownloadManager         │
│ Show: "Downloading... 75%"                                   │
└──────────────────────────────────────────────────────────────┘
```

## Classes

### 1. SaveNowModels.java
Contains model classes for API responses:
- **FormatOption** - Single format option (mp3, 720p MP4, etc.)
- **FormatList** - List of all 15 available formats
- **DownloadResponse** - Response from Step 2 API
- **ProgressResponse** - Response from Step 3 polling

### 2. SaveNowApiClient.java
Handles all HTTP requests to SaveNow.to API:
- `getFormats()` - Step 1: Load format list
- `requestDownload()` - Step 2: Request download task
- `pollProgress()` - Step 3: Poll progress
- Helper methods for HTML/JSON parsing and extraction

### 3. SaveNowDownloadDialog.java
Main UI dialog that orchestrates the entire flow:
- Shows format selection dialog
- Loads thumbnail from API
- Handles format spinner
- Manages download button states
- Orchestrates all 4 API steps
- Polls progress with visual feedback
- Initiates final file download

## Layouts

### dialog_download.xml
The main dialog layout with:
```
┌─────────────────────────────────────┐
│ ┌──────────┐  ┌──────────────────┐ │
│ │          │  │ VIDEO TITLE      │ │
│ │Thumbnail │  │ https://url.com  │ │
│ │ 243x198  │  │                  │ │
│ │          │  │ Format ┌────────┐│ │
│ │          │  │        │720p MP4││ │
│ │          │  │        └────────┘│ │
│ │          │  │                  │ │
│ │          │  │ ┌──────────────┐ │ │
│ │          │  │ │   Download   │ │ │
│ │          │  │ └──────────────┘ │ │
│ └──────────┘  └──────────────────┘ │
└─────────────────────────────────────┘
```

## Available Formats (15 total)

**Video:**
- 360p, 480p, 720p, 1080p, 1440p, 4K, 8K

**Audio:**
- MP3, M4A, AAC, FLAC, OGG, OPUS, WAV, WEBM

## Integration Example

### From WebAppInterface.java

```java
// When user clicks download button from JavaScript
@JavascriptInterface
public void startVideoDownload(String videoDataJson) {
    try {
        JSONObject json = new JSONObject(videoDataJson);
        String videoUrl = json.getString("url");
        String videoTitle = json.getString("title");

        // Use new SaveNowDownloadDialog instead of old DownloadDialog
        SaveNowDownloadDialog dialog = new SaveNowDownloadDialog(MainActivity.this);
        dialog.show(videoUrl, videoTitle);
    } catch (Exception e) {
        Log.e("WebAppInterface", "Error", e);
    }
}
```

## HTTP Flow Details

### Step 1: Load Formats
```
Request:
  GET https://p.savenow.to/api/card2/?url=https%3A%2F%2Fm.youtube.com%2Fwatch%3Fv%3D...

Response: HTML page with:
  - Video title in: <div id="cardTitle">...</div>
  - Thumbnail in: <div id="cardThumbnail" style="background-image: url(...)">
  - Format options in JavaScript array
```

### Step 2: Request Download
```
Request:
  GET https://p.savenow.to/api/v2/download?button=1&format=720&url=...&iframe_source=youtube.com

Response:
  {
    "success": true,
    "id": "v2_stream_...",
    "progress_url": "https://p.savenow.to/api/progress?id=v2_stream_...",
    "text": "Preparing streaming download",
    "title": "Video Title",
    "format": "720",
    "full_format": "mp4 [720p]",
    "thumbnail_url": "https://...",
    "info": {
      "title": "Video Title",
      "image": "https://..."
    }
  }
```

### Step 3: Poll Progress
```
Request:
  GET https://p.savenow.to/api/progress?id=v2_stream_...

Response (in progress):
  {
    "success": 0,
    "progress": 500,
    "text": "Processing..."
  }

Response (ready):
  {
    "success": 1,
    "progress": 1000,
    "download_url": "https://aiden90.savenow.to/api/v2/download/TOKEN",
    "text": "Finished",
    "format": "720",
    "full_format": "mp4 [720p]"
  }
```

### Step 4: Download File
```
Request:
  GET https://aiden90.savenow.to/api/v2/download/TOKEN

Response Headers:
  HTTP/2 200
  content-type: video/mp4
  content-disposition: attachment; filename="Video Title - 720p.mp4"

Response Body: Binary video file
```

## Error Handling & Reliability

| Step | Issue | Handling / Resolution |
|------|-------|-----------------------|
| 1 | Network error | Toast: "Failed to load formats" |
| 2 | SaveNow unavailable | Toast: "Download request failed" |
| 3 | Polling timeout (>60s) | Increased limit to 60 polls to handle long videos. |
| 3 | Worker Nodes | Dynamically follows worker subdomains provided in `progress_url`. |
| 4 | **No Seek Issue** | **FIXED:** Native re-muxing via `MediaMuxer` ensures `moov` atom is at the front. |
| 4 | MediaStore Sync | Post-download `MediaScanner` scan ensures duration is visible in gallery. |

## State Management

### Button States
- **Initial**: "Download" (enabled, waiting for format)
- **Preparing**: "Preparing... X%" (logs attempt counts `1/60` to Logcat)
- **Downloading**: "Downloading... X%" (actual file transfer to device)
- **Finalizing**: "Completed!" (occurs *after* native re-muxing is successful)

## Threading

- **Main Thread**: UI updates, button state changes
- **Background Thread**: All network requests (HTTP calls)
- **Main Handler**: Post UI updates from background threads

## Notes

- Polling interval: 1 second
- Maximum polls: 30 (30 second timeout)
- User-Agent: "Mozilla/5.0 (Linux; Android 12)" (required by SaveNow)
- All network requests are asynchronous (non-blocking)
- Thumbnail loaded asynchronously on background thread
- Dialog dismisses automatically on successful download

## Comparison: WebView vs Native

| Feature | WebView | Native |
|---------|---------|--------|
| Performance | Slower | Faster |
| UI Control | Limited | Full |
| Code Size | Simpler | More code |
| Maintenance | Depends on SaveNow HTML | Depends on API JSON |
| UI Polish | Uses SaveNow's UI | Fully customizable |
| Testing | Harder | Easier |
| Native Look | No | Yes |

## Future Enhancements

1. Add retry logic with exponential backoff
2. Cache thumbnail images
3. Support batch downloads
4. Add download history
5. Implement custom progress notifications
6. Add pause/resume functionality
