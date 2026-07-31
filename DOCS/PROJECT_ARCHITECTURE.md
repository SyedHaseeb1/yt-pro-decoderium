# YTPro Project Architecture

## Overview
YTPro is a custom YouTube client for Android that uses a `WebView` to render the YouTube mobile website and injects custom JavaScript to enhance the user experience. Key features include ad-blocking, background playback, video downloading, and Gemini AI integration.

## Project Structure
The project is organized as a standard Android application module (`:app`).

### Key Directories
- `app/src/main/java/com/google/android/youtube/pro/`: Core Java source code.
    - `MainActivity.java`: Entry point and main UI container.
    - `GeminiWrapper.java`: Handles communication with Google Gemini.
    - `DownloadService.java`: Manages file downloads in the background.
    - `ForegroundService.java`: Handles media notifications and background playback.
    - `webview/`: Contains WebView-related logic.
    - `utils/`: Helper classes for downloads and media processing.
    - `receivers/`: Broadcast receivers for media commands and notifications.
- `app/src/main/assets/scripts/`: JavaScript files injected into the WebView.
    - `ytpro.js`: The main script handling most UI enhancements and logic.
    - `bgplay.js`: Script for background playback support.
    - `innertube.js`: Script for interacting with YouTube's InnerTube API.
- `native/`: Potentially contains native code for specific tasks (e.g., bypass logic).
- `signer/`: Tools related to signing or patching.

## Technical Stack
- **Language**: Java (Android) and JavaScript (Injected).
- **UI**: WebView-based with native Android components for navigation and background services.
- **Networking**: Standard `HttpURLConnection` and WebView's internal networking.
- **AI**: Integration with Google Gemini via web-based API calls.
- **Media**: Android `MediaSession` for background playback control.

## Execution Flow
1. `MainActivity` starts and initializes `YTProWebView`.
2. `YTProWebViewClient` handles page loading and injects `ytpro.js` into the YouTube website.
3. `ytpro.js` blocks ads, modifies the UI, and sets up gesture listeners.
4. User interactions (e.g., clicking "Download") trigger calls to `WebAppInterface` via `@JavascriptInterface`.
5. `WebAppInterface` coordinates with Android services like `DownloadService` or `ForegroundService`.
