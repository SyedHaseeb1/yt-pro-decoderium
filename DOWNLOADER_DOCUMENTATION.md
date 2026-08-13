# YT Pro Video Downloader - Complete Documentation

**Version:** 1.1 (Updated)
**Date:** August 24, 2026
**Status:** Operational - Native SaveNow.to API Integration
**Target Service:** SAVENOW.TO

---

## Overview

### Purpose
Enable YouTube video downloading directly from the YT Pro app by leveraging the SAVENOW.TO API.

### Key Objectives
- ✅ Download YouTube videos in multiple formats/qualities (up to 8K)
- ✅ Bypass YouTube's download restrictions
- ✅ Fast format discovery via direct API calls
- ✅ Reliable progress tracking and download management
- ✅ Native UI for format selection and download control

### Architecture: Native API Integration

This approach uses direct HTTP requests to the SAVENOW.TO API to:
1. Fetch video metadata (title, thumbnail) and available formats.
2. Request a download task for a specific format.
3. Poll the server for download readiness.
4. Pass the final download URL to the Android `DownloadManager`.

---

## Component Documentation

### 1. `SaveNowDownloadDialog.java`

**Responsibility:** Manages the download dialog UI, orchestrates the API calls, and handles the download lifecycle.

**Key Logic:**
- **`show()`**: Inflates and displays the download dialog.
- **`loadFormats()`**: Fetches available formats from the API.
- **`startDownloadFlow()`**: Initiates the download request.
- **`startProgressPolling()`**: Polls the API until the download link is ready.
*   **`downloadFile()`**: Hands off the final URL to the `DownloadManager`.

### 2. `SaveNowApiClient.java`

**Responsibility:** Handles low-level HTTP communication with the SAVENOW.TO API.

**Key Methods:**
- **`getFormats()`**: Fetches format data.
- **`requestDownload()`**: Creates a download task on the server.
- **`pollProgress()`**: Checks status of a pending task.

### 3. `DownloadManager.java`

**Responsibility:** Wraps the system `DownloadManager` and provides progress callbacks to the UI.

---

## Configuration

**In `SaveNowApiClient.java`:**

```java
private static final String BASE_URL = "https://p.savenow.to/api";
```

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| API error | Verify SAVENOW.TO availability in browser |
| Download timeout | Increase `MAX_PROGRESS_POLLS` in `SaveNowDownloadDialog.java` |
| Progress stuck | Check network connectivity or server-side issues |

---

**Last Updated:** August 24, 2026
