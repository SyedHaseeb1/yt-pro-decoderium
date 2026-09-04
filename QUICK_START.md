# YT Pro Video Downloader - Quick Start Guide

## 📦 Architecture
The downloader uses a **Native API** to interact with **SAVENOW.TO**. It fetches video metadata and generates download links directly from the API, providing a faster and more reliable experience.

## 🚀 Quick Start (Testing)

### 1. Build APK
```bash
./gradlew clean assembleDebug
```

### 2. Install on Device
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Usage
1. Open a YouTube video in the app (rendered via WebView).
2. Click the **Download** button.
3. The app fetches available formats directly via the SaveNow API (Native Integration).
4. Select a format from the dialog to start the download via the Android `DownloadManager`.

### 4. Current Branch Focus: UX & Quality
We are currently working on:
- Improving WebView-to-Native transitions.
- Optimizing script injection performance.
- Enhancing UI responsiveness and error states.

### 4. Monitor Logs
```bash
adb logcat | grep "YTPro_DL"
```

---

## 📂 Key Components
- **`SaveNowDownloadDialog.java`**: Manages the download dialog and API interaction.
- **`DownloadHandler.java`**: JavaScript bridge connecting YouTube UI to the Downloader.
- **`youtube-downloader.js`**: Logic to handle UI interaction and callbacks.

---

## 🐛 Debugging
If download fails, check the logs for:
- "API error": The SAVENOW.TO API might be down or changed. Check `SaveNowApiClient.java`.
- "Interface not available": Ensure `Downloader` interface is correctly bound in `MainActivity.load()`.
