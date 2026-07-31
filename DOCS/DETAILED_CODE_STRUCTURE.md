# Detailed Code Structure

This document provides a detailed breakdown of the functions and methods in the core classes of the YTPro project.

## Android (Java)

### `com.google.android.youtube.pro.MainActivity`
- `onCreate(Bundle)`: Sets up the UI, WebView, and initial configuration.
- `load(boolean)`: Initializes the WebView with settings and sets the `WebViewClient`.
- `setupReceiver()`: Registers the `MediaCommandReceiver`.
- `setupBackNavigation()`: Configures the system back button behavior.
- `handleBackPress()`: Logic for navigating back within the WebView or exiting PiP.
- `onPictureInPictureModeChanged(...)`: Handles UI adjustments when entering/exiting PiP.
- `onUserLeaveHint()`: Triggered when the home button is pressed; used to enter PiP.
- `onDestroy()`: Cleans up the WebView and unbinds services.

### `com.google.android.youtube.pro.webview.WebAppInterface`
- `@JavascriptInterface showToast(String)`: Native toast bridge.
- `@JavascriptInterface downvid(String, String, String)`: Downloads a file.
- `@JavascriptInterface fullScreen(boolean)`: Controls orientation.
- `@JavascriptInterface getSNlM0e(String)`: Fetches Gemini token.
- `@JavascriptInterface GeminiClient(...)`: Proxies requests to Gemini.
- `@JavascriptInterface bgStart/bgUpdate/bgPause/bgPlay/bgStop`: Syncs JS playback with native media notification.
- `@JavascriptInterface pipvid(String)`: Activates PiP mode.
- `@JavascriptInterface setVolume(float) / setBrightness(float)`: System hardware controls.

### `com.google.android.youtube.pro.DownloadService`
- `openStream(String)`: Creates a new file stream for a download.
- `writeChunk(String, byte[])`: Appends data to an active download.
- `closeStream(String)`: Finalizes the download and closes the stream.
- `updateNotification()`: Refreshes the foreground notification with current speed/progress.
- `promoteToForeground()`: Ensures the service isn't killed by the system.

### `com.google.android.youtube.pro.ForegroundService`
- `initMediaSession()`: Sets up the `MediaSession` and its callbacks for background control.
- `updateNotification(...)`: Builds and shows the media control notification.
- `updateMediaSessionMetadata(...)`: Sets the song title/artist in the system media controller.
- `updatePlaybackState(...)`: Syncs the play/pause state with the system.

## JavaScript (`ytpro.js`)

### Ad Blocking Logic
- `window.fetch` / `XMLHttpRequest.prototype.open` override: Intercepts network requests to filter ads.
- `MutationObserver`: Continually scans the DOM for ad-related elements and removes them.
- `removeAds(obj)`: Deeply cleanses YouTube's API JSON responses to strip ad data.

### Feature Implementations
- `fDislikes(url)`: Calls the Return YT Dislike API.
- `checkSponsors(url)`: Calls the SponsorBlock API and sets up the `ontimeupdate` listener on the video element.
- `skipSponsor()`: Logic to jump past identified sponsor segments.
- `geminiInfo()`: Orchestrates the Gemini request flow (getting token, sending prompt, parsing response).
- `handleGeminiResponse(res)`: Renders Gemini's markdown response into the YouTube UI.
- `ytproSettings()`: Renders the custom settings overlay and handles user interactions.
- `sttCnf(button, key)`: Updates settings in `localStorage` and applies changes (e.g., toggling background play).
- `pkc()`: A recurring interval function that injects buttons and sliders into the YouTube UI.

### Gesture & UI
- `checkDirection(e)`: Determines swipe direction for minimizing the player.
- `getDistance(touches)`: Calculates pinch distance for zooming.
- `touchmove` listeners: Handle real-time volume/brightness adjustments.
- `addMaxButton()`: Handles the UI transformation for "Fill Screen" / Zoom functionality.
