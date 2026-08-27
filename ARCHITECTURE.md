# YTPro Architecture & Implementation Guide

## System Overview

YTPro is an Android application with modular architecture for video downloads, playback enhancement, and media processing.

## 📁 File Hierarchy

```
app/src/main/
├── java/com/google/android/youtube/pro/
│   ├── MainActivity.java                           # App entry point, WebView host
│   ├── DownloadService.java                        # Background download service
│   │
│   ├── downloader/                                 # Download system
│   │   ├── SaveNowDownloadDialog.java             # ✅ UI Layer (Download Dialog)
│   │   ├── SaveNowApiClient.java                  # ✅ API Layer (SaveNow.to Integration)
│   │   ├── SaveNowModels.java                     # ✅ Data Models (Request/Response)
│   │   ├── DownloadManager.java                   # ✅ Download Manager & Service Binding
│   │   └── DownloadFormat.java                    # Format metadata container
│   │
│   ├── utils/                                      # Utility Functions
│   │   ├── MediaMuxerUtils.java                   # ✅ Video Muxing (MP4/WebM/AV1)
│   │   └── FileUtils.java                         # File operations
│   │
│   ├── webview/
│   │   ├── YTProWebViewClient.java                # WebView client, JS injection
│   │   ├── JavascriptInterface.java               # Java-JavaScript bridge
│   │   └── CustomWebViewSettings.java             # WebView configuration
│   │
│   └── services/
│       └── DownloadServiceImpl.java                # Service implementation
│
├── res/layout/
│   ├── dialog_download.xml                        # Download dialog UI ✅
│   ├── dialog_error.xml                           # Error dialog UI ✅
│   └── activity_main.xml                          # Main activity layout
│
├── AndroidManifest.xml                            # Permissions & components
├── build.gradle                                   # Dependencies & SDK config
└── proguard-rules.pro                            # Code obfuscation rules
```

## 🔄 Download Flow Architecture

```
┌─────────────────────────────────────────────────────────┐
│ SaveNowDownloadDialog (UI Layer)                        │
│ - Format loading with loader                           │
│ - Error dialog for all failures                        │
│ - 4K+ warning on selection                             │
└────────────────┬────────────────────────────────────────┘
                 │
         ┌───────▼────────┐
         │ User Selects   │
         │ Format & Click │
         │ Download       │
         └───────┬────────┘
                 │
┌────────────────▼────────────────────────────────────────┐
│ SaveNowApiClient (API Layer)                            │
│                                                         │
│ 1. requestDownload()   → POST /api/v2/download         │
│ 2. pollProgress()      → GET {progress_url} (max 30x)  │
│ 3. getHttpErrorMessage() → User-friendly errors        │
└────────────────┬────────────────────────────────────────┘
                 │
         ┌───────▼────────────────┐
         │ Download URL Received  │
         │ (progress == 1000)     │
         └───────┬────────────────┘
                 │
┌────────────────▼────────────────────────────────────────┐
│ DownloadManager (Download Layer)                        │
│                                                         │
│ - Service binding                                       │
│ - HTTP file download with progress                      │
│ - Save to cache directory                               │
└────────────────┬────────────────────────────────────────┘
                 │
         ┌───────▼──────────┐
         │ Detect Codec:    │
         │ - AV1?           │
         │ - VP9?           │
         │ - H.264?         │
         └───────┬──────────┘
                 │
      ┌──────────┴──────────┐
      │                     │
  ┌───▼────┐        ┌──────▼──────┐
  │ AV1    │        │ VP9/H.264    │
  │        │        │              │
  │ FFmpeg │        │ MediaMuxer   │
  │ Remux  │        │ Mux          │
  │        │        │              │
  └───┬────┘        └──────┬──────┘
      │                     │
      └──────────┬──────────┘
                 │
        ┌────────▼─────────┐
        │ Move to          │
        │ Downloads/YTPRO  │
        │                  │
        │ Success!         │
        └──────────────────┘
```

## 🎯 Core Components

### 1. SaveNowDownloadDialog (UI Layer)
**File**: `downloader/SaveNowDownloadDialog.java`

**Responsibilities**:
- Display download dialog with video thumbnail
- Load available formats with progress loader
- Format selection with 4K+ warning
- Progress tracking during download
- Error display with user-friendly messages
- Cancel/pause/resume functionality

**Key Methods**:

```java
// UI Lifecycle
show(videoUrl, displayName)                 // Show dialog
loadFormats()                               // Load formats with loader
startDownloadFlow()                         // Start download process

// Download Control
startProgressPolling()                      // Poll API (max 30 times)
downloadFile()                              // Begin file download
pauseDownload()                             // Pause download
resumeDownload()                            // Resume download

// Error Handling
showErrorDialog(errorMessage)               // Show error dialog
getUserFriendlyError(technicalError)        // Convert to simple message
isSimpleUserMessage(message)                // Check if already simple

// Helpers
determineFormatType()                       // Get container type
determineQuality()                          // Get quality string
isHighResolution(formatKey)                 // Check if 4K+
```

**State Management**:
```java
private boolean isDownloading = false;      // Download in progress
private boolean isPaused = false;           // Download paused
private String selectedFormat = null;       // User selection
private String downloadFileUrl = null;      // For resume
private int currentMaxPolls = 30;           // API config
```

---

### 2. SaveNowApiClient (API Layer)
**File**: `downloader/SaveNowApiClient.java`

**Responsibilities**:
- HTTP communication with SaveNow.to API
- Request/response handling
- Error message conversion to user-friendly text

**API Endpoints**:

```
GET /api/card2/?url={videoUrl}
    → Returns available formats & video info
    Response: DownloadResponse

POST /api/v2/download?button=1&format={fmt}&url={url}&iframe_source=youtube.com
    → Request download preparation
    Response: DownloadResponse {id, progress_url, ...}

GET {progress_url}
    → Poll download status
    Response: ProgressResponse {progress, success, download_url, text}
```

**Key Methods**:

```java
getFormats(videoUrl, callback)              // Load formats
requestDownload(videoUrl, format, callback) // Start preparation
pollProgress(progressUrl, callback)         // Check status

// Helpers
fetchHtml(url)                              // Generic HTML fetch
fetchJson(url)                              // Generic JSON fetch
getHttpErrorMessage(responseCode)           // 502/503/404 → user message
```

**HTTP Error Codes**:
```
502/503 → "Server is busy. Please wait a few minutes."
504     → "Server timeout. Please try again."
429     → "Too many requests. Please wait."
404     → "Video not found. Check the URL."
400     → "Invalid video URL or format."
```

---

### 3. DownloadManager (Download Layer)
**File**: `downloader/DownloadManager.java`

**Responsibilities**:
- Android service binding for downloads
- HTTP file download with progress tracking
- File saving to device storage

**Key Methods**:

```java
startDownload(format, fileName)             // Begin download
setProgressCallback(callback)                // Register listener
cancelActiveDownload()                      // Stop download
cleanup()                                   // Release resources
```

**Progress Callback**:
```java
interface DownloadProgressCallback {
    onFormatDiscovered(formats)             // Formats detected
    onDownloadProgress(percentage, bytes)   // Progress update
    onDownloadCompleted(filePath)           // Download finished
    onError(message)                        // Download failed
    onStateChanged(state)                   // State change
}
```

---

### 4. MediaMuxerUtils (Codec Layer)
**File**: `utils/MediaMuxerUtils.java`

**Responsibilities**:
- Detect video codec (AV1, VP9, H.264)
- Remux videos to make them seekable
- Route AV1 to FFmpeg, others to MediaMuxer
- Handle audio codec compatibility (Opus)

**Key Methods**:

```java
fixSeekability(context, uri, callback)     // Main entry point
    ├─ Detect codecs
    ├─ Route to appropriate handler
    └─ Move to Downloads/YTPRO

// Handler Routes
if (isAV1) → remuxWithFFmpeg()             // AV1 needs FFmpeg
else       → Use native MediaMuxer         // VP9/H.264

remuxWithFFmpeg()                           // FFmpeg remux
    ├─ Copy source to cache
    ├─ Execute: ffmpeg -i input -c copy -y output.webm
    └─ Move to Downloads

mediaμxerPath()                             // Native remux
    ├─ Select format (WebM for VP9/Opus, MP4 for H.264)
    ├─ Add audio & video tracks
    ├─ Write sample data
    └─ Move to Downloads
```

**Codec Detection**:
```
Video Codec Detection:
├─ AV1 (av01)           → FFmpeg remux to WebM
├─ VP9 + Opus           → MediaMuxer to WebM (Opus only in WebM)
├─ H.264 + AAC          → MediaMuxer to MP4
└─ H.264 + Opus         → MediaMuxer to WebM

Output Format Decision:
├─ AV1                  → WebM (forced, FFmpeg)
├─ VP9 + Opus           → WebM (Opus incompatible with MP4)
├─ VP9 + AAC            → WebM (VP9 typically WebM)
└─ H.264 + AAC          → MP4 (standard)
```

**Dependencies**:
```gradle
implementation 'com.antonkarpenko:ffmpeg-kit-min:6.0-2'
```

---

## 📊 Data Models

### SaveNowModels.java

**DownloadResponse** (format loading & download request):
```java
class DownloadResponse {
    boolean success;              // API success flag
    String id;                    // Download task ID
    String progress_url;          // Status polling URL
    String text;                  // User message
    String title;                 // Video title
    String format;                // Selected format
    String thumbnail_url;         // Video thumbnail
    int max_polls;               // Custom poll limit
    List<FormatOption> formats;  // Available formats
}
```

**ProgressResponse** (polling):
```java
class ProgressResponse {
    int success;                  // 0=processing, 1=ready
    int progress;                 // 0-1000 (0-100%)
    String text;                  // Status message from API
    String download_url;          // File URL (when success=1)
    String title;                 // Video title
}
```

**FormatOption**:
```java
class FormatOption {
    String key;                   // Format ID (e.g., "4k", "1080")
    String label;                 // Display name (e.g., "MP4")
    String quality;               // Quality hint (e.g., "4K")
}
```

---

## 🔧 Configuration

### Gradle Dependencies
```gradle
dependencies {
    implementation 'androidx.webkit:webkit:1.12.0'
    implementation 'androidx.swiperefreshlayout:swiperefreshlayout:1.1.0'
    implementation 'androidx.cardview:cardview:1.0.0'
    implementation 'com.antonkarpenko:ffmpeg-kit-min:6.0-2'
}

android {
    compileSdk 36
    defaultConfig {
        minSdkVersion 21
        targetSdkVersion 36
        
        // APK size optimization
        ndk {
            abiFilters 'armeabi-v7a', 'arm64-v8a'
        }
    }

    // Build separate APKs for each architecture
    splits {
        abi {
            enable true
            reset()
            include 'armeabi-v7a', 'arm64-v8a'
            universalApk true
        }
    }
}
```

### AndroidManifest.xml Permissions
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

---

## 🔄 Threading Model

```
Main Thread (UI):
├─ Dialog UI updates
├─ Button clicks
└─ Handler.post() for state changes

Background Threads:
├─ SaveNowApiClient
│  ├─ HTTP requests (non-blocking)
│  └─ Response parsing
├─ DownloadManager
│  ├─ File download
│  └─ Progress tracking
└─ MediaMuxerUtils
   ├─ Codec detection
   ├─ FFmpeg execution
   └─ MediaMuxer operations

Thread Safety:
├─ mainHandler.post() for all UI updates
├─ synchronized access to state variables
└─ Callback execution on main thread
```

---

## 🎯 Error Handling Strategy

```
Technical Error → Convert to User Message → Show Error Dialog
                         ↓
                 Is it API message? (simple)
                    ✓ Yes → Use directly
                    ✗ No  → Parse & convert
                         ↓
                 Show in dialog_error.xml
```

**Error Sources**:
1. **API Errors** - SaveNow.to responses (http, network)
2. **Network Errors** - Connection/timeout issues
3. **Codec Errors** - Unsupported format combinations
4. **File Errors** - Storage/permission issues

**User-Friendly Mapping**:
```
API: "4K not supported"
↓ (already simple)
User: "4K not supported. Please select a lower quality."

API: HTTP 502
↓ (technical, convert)
User: "The download server is busy. Please wait a few minutes."

API: "timeout"
↓ (partial, enhance)
User: "The download is taking too long. Video might be protected. Try lower quality."
```

---

## 📈 Performance Characteristics

| Operation | Duration | Notes |
|-----------|----------|-------|
| Load formats | 1-2s | API call with parsing |
| Request download | 1-2s | API preparation |
| Poll progress | ~30s max | 30 polls × 1s interval |
| Download file | Variable | Depends on file size/network |
| FFmpeg remux (AV1) | 1-2 min | Slow, codec incompatibility |
| MediaMuxer mux | 30-60s | Fast, native Android |

---

## 🔍 Debugging

**Logging Tags**:
```
SaveNowApiClient       → API requests/responses
SaveNowDownloadDialog  → Dialog lifecycle & user actions
DownloadManager        → Download progress
YTPRO_MEDIA           → Media muxing operations
YTPRO_DL_SVC          → Download service
```

**Enable verbose logging**:
```bash
adb logcat SaveNowDownloadDialog:V | grep -i "error\|warning\|exception"
```

---

## 📋 State Diagram

```
Dialog Closed
    ↓
[Show Dialog] ← Load formats with loader
    ↓
[Formats Ready]
    ↓
[User Selects Format]
    ↓
[User Clicks Download]
    ↓
[Request Preparation] → Error → [Show Error Dialog] → [Close]
    ↓ Success
[Poll Progress] ← Loop (max 30 times)
    ↓ Success (progress=1000)
[Download File] → Error → [Show Error Dialog] → [Close]
    ↓ Success
[Move to Downloads/YTPRO]
    ↓
[Show Success Message]
    ↓
[Dialog Auto-close]
```

---

**Last Updated**: 2026-08-17
**Status**: Current & Production Ready
