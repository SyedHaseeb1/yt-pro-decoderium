# Android App Components

## Core Components

### `MainActivity.java`
- **Role**: The main container for the app.
- **Key Responsibilities**:
    - Initializes the `YTProWebView`.
    - Manages lifecycle events (`onCreate`, `onPause`, `onDestroy`).
    - Handles back navigation (`setupBackNavigation`, `handleBackPress`).
    - Manages Picture-in-Picture (PiP) transitions.
    - Manages permissions (Storage).

### `DownloadService.java`
- **Role**: Background service for handling video/audio downloads.
- **Key Responsibilities**:
    - Manages multiple concurrent download streams.
    - Handles file I/O using both legacy `File` API and Scoped Storage (MediaStore).
    - Updates a persistent notification to show download progress.
    - Promotes itself to a foreground service when downloads are active.

### `ForegroundService.java`
- **Role**: Handles media playback in the background and notification area.
- **Key Responsibilities**:
    - Initializes and manages `MediaSession`.
    - Updates the media notification with title, channel, and thumbnail.
    - Handles media commands (Play, Pause, Skip, Rewind) via `MediaSession.Callback`.
    - Manages background playback state.

### `GeminiWrapper.java`
- **Role**: A utility class to interface with the Gemini API.
- **Key Responsibilities**:
    - Fetches the `SNlM0e` token (required for Gemini requests).
    - Sends prompts to Gemini and returns the streamed response.
    - Handles cookie injection for authentication.

## WebView Components

### `YTProWebView.java`
- **Role**: Custom `WebView` subclass.
- **Key Responsibilities**:
    - Configures WebView settings (JavaScript enabled, DOM storage, etc.).
    - Overrides `onWindowVisibilityChanged` to prevent YouTube from pausing the video when the app is minimized (supporting background play).

### `YTProWebViewClient.java`
- **Role**: Custom `WebViewClient`.
- **Key Responsibilities**:
    - Injects `ytpro.js` and other scripts during `onPageFinished`.
    - Intercepts URL loading if necessary.

### `YTProWebChromeClient.java`
- **Role**: Custom `WebChromeClient`.
- **Key Responsibilities**:
    - Handles file choice requests (if any).
    - Manages JavaScript console messages.

### `WebAppInterface.java`
- **Role**: The bridge between JavaScript and Native Java code.
- **Key Responsibilities**:
    - Exposed to JS as `Android`.
    - Provides methods for:
        - Toast messages (`showToast`).
        - Downloading (`downvid`).
        - Controlling full screen (`fullScreen`).
        - Gemini interactions (`GeminiClient`, `getSNlM0e`).
        - Volume and Brightness control.
        - PiP mode activation (`pipvid`).
        - Background playback management (`bgStart`, `bgUpdate`, etc.).

## Utils & Receivers

### `DownloadUtils.java` & `MediaMuxerUtils.java`
- **Role**: Helper classes for processing downloaded files.
- **MediaMuxerUtils**: Used for merging separate video and audio streams (common in high-quality YouTube downloads).

### `MediaCommandReceiver.java`
- **Role**: `BroadcastReceiver` that listens for media button events.
- **Responsibility**: Routes media commands to the `MainActivity` or `ForegroundService`.
