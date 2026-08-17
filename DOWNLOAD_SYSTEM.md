# YT Pro Download System - Architecture & Implementation

## Overview

The YT Pro download system provides production-quality YouTube video downloading with format selection, progress tracking, and proper video playback management.

## Architecture

### Components

#### 1. Frontend (JavaScript)
- **video-downloader.js**: Comprehensive video data extraction and playback control
  - Extracts video URL, ID, title, duration
  - Manages video pause/resume during download
  - Provides video playback state tracking
  - Exports `handleDownloadClick()` function called by ytpro.js

#### 2. Android Native (Java)

##### DownloadHandler.java
- Entry point for download workflow
- Manages dialog lifecycle
- Coordinates format discovery and selection
- Sets up progress callbacks

##### DownloadManager.java
- State machine for download workflow
  - States: IDLE, DISCOVERING_FORMATS, FORMATS_READY, DOWNLOADING, PAUSED, COMPLETED, ERROR, CANCELLED
- Format discovery from SaveNow API with timeout handling
- Download coordination
- Progress tracking interface
- Error handling and recovery

##### FormatSelectionDialog.java
- UI for selecting video format
- Shows quality, codec, and file size
- Handles user selection with callback

##### VideoDataExtractor.java
- Utility for extracting video metadata
- Video ID extraction from URLs
- Filename sanitization
- Byte formatting for UI display

##### WebAppInterface.java (Extended)
- `openDownloadDialog()`: Initiates download workflow
- `pauseVideoDownload()`: Pause video during download
- `resumeVideoDownload()`: Resume paused video

## Workflow

### Download Initiation
1. User clicks "Download" button in ytpro.js UI
2. `handleDownloadClick()` in video-downloader.js is called
3. Video playback state is captured (paused if playing)
4. `Android.openDownloadDialog(videoUrl)` is called
5. DownloadHandler opens progress dialog

### Format Discovery
1. DownloadManager makes HTTP request to SaveNow API
2. API response is parsed for available quality options
3. Extracted formats displayed in FormatSelectionDialog
4. User selects desired quality/codec/size

### Download Execution
1. Selected format is passed to DownloadManager
2. Download URL is constructed
3. Android DownloadManager enqueues download to /Downloads/
4. Progress callbacks are triggered
5. Download completion notification shows filename

### Error Handling
- API timeouts: Default format options provided
- Network errors: User-friendly error messages
- Format parsing failures: Graceful fallback options
- Download interruption: State preserved for resume

## Class Hierarchy

```
DownloadHandler
├── DownloadManager
│   ├── DownloadFormat (inner class)
│   ├── DownloadState (enum)
│   └── DownloadProgressCallback (interface)
├── FormatSelectionDialog
└── VideoDataExtractor
```

## State Machine Diagram

```
IDLE
 ↓
DISCOVERING_FORMATS → [ERROR]
 ↓
FORMATS_READY
 ↓
DOWNLOADING ↔ PAUSED
 ↓
COMPLETED / CANCELLED / [ERROR]
```

## API Integration

### SaveNow.to Format Discovery
- **Endpoint**: `https://p.savenow.to/api/card2/?url={VIDEO_URL}`
- **Method**: GET
- **Response**: HTML with embedded format options
- **Parsing**: Regex extraction of quality levels (1080p, 720p, 480p, 360p)

### Android DownloadManager
- Uses native Android DownloadManager for reliable downloads
- Automatically places files in `/Downloads/` directory
- Shows notification with progress and completion

## Configuration

### Timeouts
- Format discovery: 30 seconds (configurable)
- API request: 10 seconds
- Can be adjusted in DownloadManager.startFormatDiscovery()

### Format Detection
- Automatically parses quality options from API response
- Fallback to standard options if parsing fails:
  - 1080p, 720p, 480p, 360p

### Video Pause/Resume
- Video is paused when download dialog opens
- User can resume by closing dialog
- Video playback state preserved if dialog is cancelled

## Error Scenarios & Recovery

| Scenario | State | Recovery |
|----------|-------|----------|
| Network timeout | ERROR | Shows error, allows retry |
| Format parsing fails | FORMATS_READY | Uses default options |
| Download cancellation | CANCELLED | Cleanup, return to IDLE |
| Invalid video URL | ERROR | Toast notification |
| Missing permissions | ERROR | Permission request prompt |

## Extension Points

### Adding Custom Format Parser
Extend `DownloadManager.parseFormatOptions()` to handle additional API responses

### Progress Updates
Implement `DownloadProgressCallback` to add progress UI (progress bar, percentage)

### Custom Download Handler
Replace `DownloadUtils.downloadFile()` with custom downloader (e.g., OkHttp, Retrofit)

## Files Modified/Created

### Created
- `DownloadManager.java` - Core download coordination
- `FormatSelectionDialog.java` - Format selection UI
- `VideoDataExtractor.java` - Metadata extraction utilities

### Modified
- `DownloadHandler.java` - Enhanced with state management
- `video-downloader.js` - Comprehensive video data extraction
- `WebAppInterface.java` - Added pause/resume methods
- `AndroidManifest.xml` - Fixed receiver package reference

## Testing

### Manual Testing
1. Open YouTube video
2. Click Download button
3. Verify formats discovered
4. Select format and download
5. Check /Downloads/ for file
6. Verify video playback resumes

### Edge Cases to Test
- Network interruption during format discovery
- Video without title
- Very short videos (< 1 second)
- Long videos (> 10 hours)
- Dialog cancellation
- Multiple concurrent downloads

## Performance Considerations

- Format discovery runs on background thread
- UI updates dispatched to main thread via Handler
- No blocking operations on main thread
- Memory-efficient parsing with regex
- Automatic cleanup of cancelled downloads

## Security Considerations

- User-Agent spoofing for API compatibility
- URL encoding for safe request parameters
- Filename sanitization to prevent directory traversal
- HTTPS for API communication (SaveNow.to)
- No sensitive data stored locally

## Future Enhancements

1. **Progress Bar UI**: Real-time download percentage
2. **Resume/Pause**: Pause ongoing downloads
3. **Multi-format Audio**: Separate audio codec selection
4. **Batch Downloads**: Download multiple videos
5. **Format Presets**: User preferences for quality/codec
6. **Video Trimming**: Download only selected segments
7. **Custom Output Path**: User-defined download location
