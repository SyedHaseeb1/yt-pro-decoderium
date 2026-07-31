# API and Interfaces

## Android-to-JavaScript Bridge (`Android` object)

The `WebAppInterface.java` class exposes the following methods to the JavaScript environment under the global `Android` object:

### UI & System
- `showToast(text)`: Displays a native Android toast.
- `oplink(url)`: Opens a URL in the system browser.
- `fullScreen(isPortrait)`: Toggles full-screen mode and sets orientation.
- `gohome(url)`: Navigates to the home URL.

### Media & Control
- `pipvid(orientation)`: Activates Picture-in-Picture mode.
- `getVolume()`: Returns the current system volume level (0.0 to 1.0).
- `setVolume(value)`: Sets the system volume level.
- `getBrightness()`: Returns the current screen brightness (0 to 100).
- `setBrightness(value)`: Sets the screen brightness.
- `setBgPlay(enabled)`: Enables or disables background playback support in the app.

### Downloads
- `downvid(fileName, url, mimeType)`: Initiates a download via `DownloadService`.
- `requestBinaryPort(fileName)`: Sets up a binary stream for downloading large files.
- `muxVideoAudio(videoPath, audioPath, outputPath)`: Muxes video and audio files together.

### Gemini AI
- `getSNlM0e(cookies)`: Fetches the required `at` token for Gemini API calls.
- `GeminiClient(endpoint, headers, body)`: Performs a network request to the Gemini API and returns the response.

### Background Playback Updates
- `bgStart(title, channel, icon, duration)`: Notifies the app that playback has started.
- `bgUpdate(title, channel, icon, duration)`: Updates current playback metadata.
- `bgPause(position)`: Notifies that playback is paused.
- `bgPlay(position)`: Notifies that playback has resumed.
- `bgBuffer(position)`: Notifies that the video is buffering.
- `bgStop()`: Notifies that playback has stopped.

## JavaScript Callbacks
The Android layer can execute JavaScript in the WebView to send data back to the scripts:
- `window.callbackSNlM0e.resolve(token)`: Delivers the Gemini token.
- `window.callbackGeminiClient.resolve(response)`: Delivers the Gemini API response.
- `window.handleMediaCommand(command)`: Sends media button commands (play, pause, next) from the notification to the JS layer.

## External APIs Used
- **SponsorBlock API**: `https://sponsor.ajay.app/api/skipSegments`
- **Return YouTube Dislike API**: `https://returnyoutubedislikeapi.com/votes`
- **Gemini API**: `https://gemini.google.com/` (Internal web endpoints)
