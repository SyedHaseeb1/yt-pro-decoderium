# Developer Guide & Project Status

## 🌟 Current Branch Focus: UX & Quality
We are currently prioritizing the **User Experience (UX)** and **App Quality**. While the core streaming remains WebView-based, we are aiming for a "native-like" feel.

**Key Goals for this Branch:**
- **Smoothness**: Eliminating jank during JavaScript injection.
- **Consistency**: Ensuring injected UI elements match the Material Design of the native wrapper.
- **Resilience**: Better handling of WebView crashes or YouTube UI changes.
- **Resource Management**: Optimizing memory usage during long streaming sessions.

---

## 📚 Understanding the Downloader System

YTPro has **4 layers** for video downloads:

1. **UI Layer** (`SaveNowDownloadDialog`) - What users see
2. **API Layer** (`SaveNowApiClient`) - Talk to SaveNow.to
3. **Download Layer** (`DownloadManager`) - Download files
4. **Codec Layer** (`MediaMuxerUtils`) - Fix video format compatibility

Each layer handles one responsibility. This makes code easy to understand and test.

---

## 🔄 The Download Flow (Simple Version)

```
User opens download dialog
    ↓
Load available formats from API
    ↓
User picks format (e.g., 4K)
    ↓
Request download preparation from API
    ↓
Poll API every 1 second (max 30 times) until ready
    ↓
Download file when ready
    ↓
Detect video codec (AV1, VP9, H.264)
    ↓
If AV1: Use FFmpeg (slow but necessary)
Else: Use Android MediaMuxer (fast)
    ↓
Save file to Downloads/YTPRO
    ↓
Done!
```

---

## 📝 Key Files to Know

### When: User clicks download
→ Read: `SaveNowDownloadDialog.java` lines 101-115 (startDownloadFlow)

### When: API returns error like "4K not supported"
→ Read: `SaveNowDownloadDialog.java` lines 580-620 (getUserFriendlyError)

### When: Video won't play after download
→ Read: `MediaMuxerUtils.java` lines 43-106 (codec detection & routing)

### When: Download progress stuck
→ Read: `SaveNowDownloadDialog.java` lines 266-314 (polling logic)

---

## 🎯 Common Tasks

### Task 1: Add New Format to UI
**File**: `SaveNowModels.java` line 35

```java
public static FormatOption[] getDefaultFormats() {
    return new FormatOption[]{
        // ... existing formats ...
        new FormatOption("webm", "WEBM", "Audio"),  // Add new line here
    };
}
```

### Task 2: Change Polling Timeout
**File**: `SaveNowDownloadDialog.java` line 31

```java
private static final int MAX_PROGRESS_POLLS = 30;  // Change this number
private static final int POLL_INTERVAL = 1000;     // Milliseconds (1000 = 1 second)
```

### Task 3: Support New Codec
**File**: `MediaMuxerUtils.java` line 86

```java
// Add here:
boolean isNewCodec = videoMime != null && videoMime.contains("new-codec");
if (isNewCodec) {
    remuxWithFFmpeg(context, sourceUri, displayName, tempFile, callback);
    return;
}
```

### Task 4: Custom Error Message
**File**: `SaveNowDownloadDialog.java` line 565

```java
private String getUserFriendlyError(String error) {
    // Add new check here:
    if (error.toLowerCase().contains("my-error")) {
        return "Explain it to user in simple words";
    }
    // ... rest of code ...
}
```

---

## 🐛 Debugging Tips

### See download flow in logcat:
```bash
adb logcat SaveNowDownloadDialog:V SaveNowApiClient:V YTPRO_MEDIA:V | grep -v "VERBOSE"
```

### Key log points:
- "Loading formats from: " - Format loading started
- "Selected format: " - User choice logged
- "Requesting download: " - API call sent
- "Polling progress: " - Status check
- "Download completed successfully" - Muxing done

### Check device downloads:
```bash
adb shell ls /sdcard/Download/YTPRO/
```

---

## ❌ Common Problems & Solutions

### Problem: Downloads timeout
**Cause**: API is slow (happens with 4K)
**Fix**: Increase `MAX_PROGRESS_POLLS` to 60
**File**: `SaveNowDownloadDialog.java` line 31

### Problem: "File format not supported"
**Cause**: Codec compatibility issue
**Check**: `MediaMuxerUtils.java` lines 94-97 for format decision logic
**Fix**: Add codec to WebM route if Opus audio detected

### Problem: Download gets stuck at 100%
**Cause**: File not being written to Downloads folder
**Check**: `SaveNowDownloadDialog.java` handleRemuxSuccess() method
**Fix**: Verify Downloads/YTPRO directory exists

### Problem: User sees technical error like "HTTP 502"
**Cause**: Error message not user-friendly
**Fix**: Add mapping in getUserFriendlyError() method
**File**: `SaveNowDownloadDialog.java` lines 545-605

---

## 🧪 Testing the Download System

### Test 1: Format Loading
```java
// Open app, click download button
// Should see formats loading with spinner
// Check logcat: "Formats loaded successfully"
```

### Test 2: Error Handling
```java
// Manually set invalid URL
// Should show error dialog with message, not crash
// Check logcat: "Error: [message]"
```

### Test 3: 4K Warning
```java
// Select 4K format
// Should show warning: "4K+ may timeout"
// Check dialog message text updated
```

### Test 4: File Location
```bash
# After download completes
adb shell ls /sdcard/Download/YTPRO/
# Should list downloaded file with _ytpro suffix
```

---

## 📚 Code Style Guide

### Naming Convention
```java
boolean isDownloading;      // Boolean: is/has prefix
String downloadUrl;         // String: descriptive name
int maxPolls = 30;         // Constants: ALL_CAPS
URL url = new URL(...);    // Classes: CapitalCase
```

### Error Handling
```java
// Good:
if (error != null && error.toLowerCase().contains("4k")) {
    return "4K not supported. Try lower quality.";
}

// Bad:
if (error.contains("4k")) { // Can crash if error is null
    // ...
}
```

### Thread Safety
```java
// Good: UI update from callback
mainHandler.post(() -> {
    downloadMessage.setText("Downloaded!");  // Safe
});

// Bad: Direct UI update from background thread
downloadMessage.setText("Downloaded!");  // Can crash!
```

---

## 📖 Related Documentation

- **ARCHITECTURE.md** - Full system design with diagrams
- **API_REFERENCE.md** - Complete API documentation
- **README.md** - Project overview

---

## ❓ Need Help?

1. **Understand flow**: Read this guide first
2. **See details**: Check ARCHITECTURE.md
3. **API docs**: Look in API_REFERENCE.md
4. **Code questions**: Search in the file where problem occurs
5. **Still stuck**: Add logs and check logcat output

---

**Last Updated**: 2026-08-17
**For**: Android developers new to YTPro
**Time to read**: 10 minutes
