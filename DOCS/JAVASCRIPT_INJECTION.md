# JavaScript Injection (`ytpro.js`)

## Overview
`ytpro.js` is the core of YTPro's functionality on the client side. It runs within the context of the YouTube mobile website and modifies its behavior.

## Major Modules

### 1. Ad Blocker
- **Fetch Interception**: Overrides `window.fetch` to block requests to known ad domains (e.g., `doubleclick.net`, `googleads`).
- **XHR Interception**: Overrides `XMLHttpRequest.prototype.open` to block ad-related XHR requests.
- **YouTube API Interceptor**: Intercepts responses from `youtube.com/youtubei/` to remove `adSlots`, `playerAds`, and `adPlacements` from the JSON data before the website processes it.
- **DOM Observer**: Uses a `MutationObserver` to detect and remove ad containers (e.g., `.ad-container`, `#masthead-ad`) as they are added to the page.

### 2. UI Enhancements
- **Settings Button**: Adds a settings gear icon to the YouTube header.
- **Refresh Button**: Adds a reload button to the header.
- **Download/Heart/PIP Buttons**: Injects custom buttons into the video action bar for downloading, liking (Heart), and PiP mode.
- **Shorts Blocker**: Optionally removes "Shorts" sections from the home feed based on user settings.

### 3. Features
- **SponsorBlock**: Fetches sponsor segments from `sponsor.ajay.app` and automatically skips them during playback.
- **Return YouTube Dislike**: Fetches dislike counts from `returnyoutubedislikeapi.com` and displays them, restoring the removed feature.
- **Gemini AI**: Provides a "Gemini" button that sends video information (title, URL) to the Gemini API for summarization or analysis, then displays the result in a custom UI overlay.
- **Gesture Controls**:
    - Swipe vertically on the right side to adjust volume.
    - Swipe vertically on the left side to adjust brightness.
    - Pinch-to-zoom in full-screen mode.
- **Heart (Favorites)**: Saves liked videos to `localStorage` and provides a dedicated UI to view them.

### 4. Background Playback & PiP
- **PiP Workaround**: Overrides `HTMLMediaElement.prototype.pause` to prevent the video from pausing when the app enters PiP mode or background, unless explicitly allowed.
- **Media Session Bridge**: Sends playback status (play, pause, duration) to the Android `ForegroundService` to keep the media notification in sync.

### 5. Settings Management
- Manages various user preferences (e.g., Auto-skip sponsors, Developer mode, Codec preferences) via `localStorage` and a custom settings UI overlay.
- **Codec Overrider**: Overrides `canPlayType` and `isTypeSupported` to force or block specific codecs (AV1, VP9, etc.) based on user settings.
