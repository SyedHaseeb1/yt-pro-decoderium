// YouTube Video Downloader - Comprehensive Implementation
(function() {
  'use strict';

  // ============ Video Data Extraction ============
  function getVideoUrl() {
    let url = window.location.href;
    if (url.includes('youtube.com/watch') || url.includes('youtube.com/shorts') || url.includes('youtu.be')) {
      return url;
    }
    return null;
  }

  function extractVideoId(url) {
    // youtube.com/watch?v=ID or youtu.be/ID or youtube.com/shorts/ID
    const patterns = [
      /(?:youtube\.com\/watch\?v=|youtu\.be\/)([a-zA-Z0-9_-]{11})/,
      /youtube\.com\/shorts\/([a-zA-Z0-9_-]{11})/
    ];

    for (let pattern of patterns) {
      const match = url.match(pattern);
      if (match) return match[1];
    }
    return null;
  }

  function getVideoTitle() {
    // Try to get title from page
    const titleElement = document.querySelector('h1 yt-formatted-string');
    if (titleElement && titleElement.textContent) {
      return titleElement.textContent.trim();
    }

    // Fallback to document title
    let title = document.title;
    if (title.includes(' - YouTube')) {
      title = title.replace(' - YouTube', '');
    }
    return title || 'video';
  }

  function getVideoDuration() {
    try {
      const video = document.getElementsByClassName('video-stream')[0];
      if (video && video.duration) {
        return Math.floor(video.duration * 1000); // Convert to milliseconds
      }
    } catch (e) {
      console.log('[VideoDownloader] Could not get video duration');
    }
    return 0;
  }

  function getThumbnailUrl() {
    // Try to find thumbnail in meta tags
    const metaTags = document.querySelectorAll('meta[property="og:image"]');
    if (metaTags.length > 0) {
      return metaTags[0].getAttribute('content');
    }
    return null;
  }

  // ============ Video Playback Control ============

  // ============ Download Management ============
  function handleDownloadClick() {
    const videoUrl = getVideoUrl();
    if (!videoUrl) {
      console.error('[VideoDownloader] No video URL found');
      return;
    }

    const videoId = extractVideoId(videoUrl);
    const title = getVideoTitle();
    const duration = getVideoDuration();
    const thumbnail = getThumbnailUrl();

    console.log('[VideoDownloader] Starting download:');
    console.log('  URL:', videoUrl);
    console.log('  ID:', videoId);
    console.log('  Title:', title);
    console.log('  Duration:', duration, 'ms');
    console.log('  Thumbnail:', thumbnail);

    // Call Android to open download dialog with video metadata
    try {
      // Pass video metadata as JSON string
      const videoData = JSON.stringify({
        url: videoUrl,
        id: videoId,
        title: title,
        duration: duration,
        thumbnail: thumbnail
      });
      Android.openDownloadDialog(videoData);
    } catch (e) {
      console.error('[VideoDownloader] Failed to call Android interface:', e);
    }
  }

  // ============ Exports ============
  window.handleDownloadClick = handleDownloadClick;
  window.VideoDownloader = {
    getVideoUrl: getVideoUrl,
    extractVideoId: extractVideoId,
    getVideoTitle: getVideoTitle,
    getVideoDuration: getVideoDuration,
    getThumbnailUrl: getThumbnailUrl
  };

  // ============ Video Event Listeners ============
  let lastVideoElement = null;

  const observer = new MutationObserver(() => {
    const video = document.getElementsByClassName('video-stream')[0];

    if (video && video !== lastVideoElement) {
      lastVideoElement = video;
      console.log('[VideoDownloader] New video detected, requesting listener injection');
      Android.reinjectVideoListeners();
    } else if (!video) {
      lastVideoElement = null;
    }
  });

  observer.observe(document.body, {
    childList: true,
    subtree: true
  });

  console.log('[VideoDownloader] Loaded successfully');
})();
