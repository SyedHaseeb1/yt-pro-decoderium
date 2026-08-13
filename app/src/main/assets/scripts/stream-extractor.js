/**
 * YT Pro - Direct YouTube Player Stream Extractor
 * Accesses the actual YouTube player object that has streaming data
 */

class YouTubeStreamExtractor {
  constructor() {
    this.streams = {
      video: [],
      audio: [],
      metadata: null,
      playerData: null
    };
    this.setupExtraction();
  }

  setupExtraction() {
    // Access YouTube player directly
    const checkPlayer = () => {
      // Try multiple ways to access the player
      const player = this.getYouTubePlayer();

      if (player) {
        console.log('[PLAYER] Found YouTube player');
        this.extractFromPlayer(player);
        return true;
      }

      // Also check for config data
      if (window.ytInitialData || window.ytInitialPlayerResponse) {
        console.log('[PLAYER] Found initial data');
        this.extractFromInitialData();
        return true;
      }

      // Check for yt object with config
      if (window.yt && window.yt.config) {
        console.log('[PLAYER] Found yt.config');
        this.extractFromConfig();
        return true;
      }

      return false;
    };

    // Check immediately
    if (!checkPlayer()) {
      // Retry periodically
      const interval = setInterval(() => {
        if (checkPlayer()) {
          clearInterval(interval);
        }
      }, 200);

      // Stop after 30 seconds
      setTimeout(() => clearInterval(interval), 30000);
    }
  }

  getYouTubePlayer() {
    // Method 1: Direct window reference
    if (window.ytPlayer) return window.ytPlayer;
    if (window.player) return window.player;

    // Method 2: Through ytplayer
    if (window.ytplayer && window.ytplayer.web) return window.ytplayer.web;

    // Method 3: Access through document - find video element
    const video = document.querySelector('video');
    if (video) {
      console.log('[PLAYER] Found video element');
      return { videoElement: video };
    }

    // Method 4: Look for player in window properties
    for (let key in window) {
      try {
        const obj = window[key];
        if (obj && typeof obj === 'object') {
          if (obj.getVideoData || obj.getPlayerState || obj.playVideo) {
            console.log(`[PLAYER] Found player at window.${key}`);
            return obj;
          }
        }
      } catch (e) {}
    }

    return null;
  }

  extractFromPlayer(player) {
    try {
      // YouTube player has getVideoData() method with streaming info
      if (player.getVideoData) {
        const videoData = player.getVideoData();
        console.log('[PLAYER] Video data:', videoData);

        if (videoData) {
          this.streams.metadata = {
            videoId: videoData.video_id || videoData.videoId,
            title: videoData.title,
            duration: videoData.length_seconds
          };
        }
      }

      // Try to access streaming data from player internals
      if (player.getStreamingData) {
        const streamingData = player.getStreamingData();
        this.analyzeStreamingData(streamingData);
      }

      // Check player config
      if (player.config) {
        console.log('[PLAYER] Player has config');
        this.analyzeStreamingData(player.config);
      }

      // Look in player.streamingData
      if (player.streamingData) {
        console.log('[PLAYER] Found streamingData in player');
        this.analyzeStreamingData(player.streamingData);
      }

      console.log('[PLAYER] Extracted from player object');
    } catch (e) {
      console.log('[PLAYER] Error accessing player:', e.message);
    }
  }

  extractFromInitialData() {
    try {
      const playerResponse = window.ytInitialPlayerResponse || window.ytInitialData;

      if (playerResponse && playerResponse.streamingData) {
        console.log('[PLAYER] Using ytInitialPlayerResponse');
        this.analyzeStreamingData(playerResponse.streamingData);
        if (playerResponse.videoDetails) {
          this.streams.metadata = {
            videoId: playerResponse.videoDetails.videoId,
            title: playerResponse.videoDetails.title,
            duration: playerResponse.videoDetails.lengthSeconds
          };
        }
      }
    } catch (e) {
      console.log('[PLAYER] Error accessing initial data:', e.message);
    }
  }

  extractFromConfig() {
    try {
      // Access through yt.config
      if (window.yt && window.yt.config && window.yt.config.INNERTUBE_CONTEXT) {
        console.log('[PLAYER] Found innertube context');
        // This requires making API calls, which we can do later
      }
    } catch (e) {
      console.log('[PLAYER] Error accessing config:', e.message);
    }
  }

  analyzeStreamingData(streamingData) {
    if (!streamingData) return;

    let found = 0;

    // Process adaptive formats
    if (streamingData.adaptiveFormats && Array.isArray(streamingData.adaptiveFormats)) {
      streamingData.adaptiveFormats.forEach(format => {
        if (!format.url && !format.cipher) return;

        const isVideo = format.mimeType?.includes('video');
        const isAudio = format.mimeType?.includes('audio');

        if (!isVideo && !isAudio) return;

        const streamInfo = {
          itag: format.itag,
          mimeType: format.mimeType || '',
          quality: format.qualityLabel || format.height || `itag=${format.itag}`,
          bitrate: format.bitrate || format.averageBitrate || 0,
          fps: format.fps || 30,
          width: format.width,
          height: format.height,
          url: format.url || '',
          hasUrl: !!format.url
        };

        if (isVideo) {
          if (!this.streams.video.some(v => v.itag === streamInfo.itag)) {
            this.streams.video.push(streamInfo);
            console.log(`[PLAYER] ✓ Video: ${streamInfo.quality}@${streamInfo.fps}fps`);
            found++;
          }
        } else if (isAudio) {
          if (!this.streams.audio.some(a => a.itag === streamInfo.itag)) {
            this.streams.audio.push(streamInfo);
            console.log(`[PLAYER] ✓ Audio: ${streamInfo.bitrate}bps`);
            found++;
          }
        }
      });
    }

    // Process regular formats (muxed)
    if (streamingData.formats && Array.isArray(streamingData.formats)) {
      streamingData.formats.forEach(format => {
        if (!format.url) return;

        const streamInfo = {
          itag: format.itag,
          mimeType: format.mimeType || '',
          quality: format.qualityLabel || 'muxed',
          url: format.url,
          hasUrl: true
        };

        if (!this.streams.video.some(v => v.itag === streamInfo.itag)) {
          this.streams.video.push(streamInfo);
          console.log(`[PLAYER] ✓ Muxed: ${streamInfo.quality}`);
          found++;
        }
      });
    }

    if (found > 0) {
      console.log(`[PLAYER] SUCCESS: Found ${found} stream formats`);
      window.Android?.log?.(`Streams: ${this.streams.video.length} video + ${this.streams.audio.length} audio`);
    }
  }

  getStreams() {
    return {
      videos: this.streams.video,
      audios: this.streams.audio,
      metadata: this.streams.metadata
    };
  }

  printStreams() {
    console.log('[PLAYER] ===== CAPTURED STREAMS =====');
    if (this.streams.video.length > 0) {
      console.log('[PLAYER] VIDEO:');
      this.streams.video.forEach((v, i) => {
        console.log(`  [${i}] ${v.quality}@${v.fps}fps (${v.hasUrl ? '✓' : '❌'})`);
      });
    }
    if (this.streams.audio.length > 0) {
      console.log('[PLAYER] AUDIO:');
      this.streams.audio.forEach((a, i) => {
        console.log(`  [${i}] ${a.bitrate}bps (${a.hasUrl ? '✓' : '❌'})`);
      });
    }
  }
}

window.youtubeExtractor = new YouTubeStreamExtractor();
window.getVideoStreams = () => window.youtubeExtractor.getStreams();
window.printStreams = () => window.youtubeExtractor.printStreams();

console.log('[PLAYER] YouTube Stream Extractor loaded');
