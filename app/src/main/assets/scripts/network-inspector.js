/**
 * YT Pro - Stream Extractor
 * Extracts video/audio streams from YouTube's embedded player data
 */

class StreamExtractor {
  constructor() {
    this.streams = {
      video: [],
      audio: [],
      metadata: null,
      source: 'ytInitialPlayerResponse'
    };
    this.setupExtraction();
  }

  setupExtraction() {
    // Wait for ytInitialPlayerResponse to be available
    const checkForData = () => {
      if (window.ytInitialPlayerResponse) {
        console.log('[STREAM] Found ytInitialPlayerResponse');
        this.extractFromPlayerResponse(window.ytInitialPlayerResponse);
      } else {
        // Retry after a short delay
        setTimeout(checkForData, 500);
      }
    };

    checkForData();

    // Also watch for dynamic updates
    const observer = new MutationObserver(() => {
      if (window.ytInitialPlayerResponse && this.streams.video.length === 0) {
        console.log('[STREAM] Updated ytInitialPlayerResponse detected');
        this.extractFromPlayerResponse(window.ytInitialPlayerResponse);
      }
    });

    observer.observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  }

  extractFromPlayerResponse(playerResponse) {
    if (!playerResponse) return;

    // Extract metadata
    if (playerResponse.videoDetails) {
      const details = {
        videoId: playerResponse.videoDetails.videoId,
        title: playerResponse.videoDetails.title,
        duration: playerResponse.videoDetails.lengthSeconds,
        author: playerResponse.videoDetails.author,
        isLiveContent: playerResponse.videoDetails.isLiveContent
      };
      this.streams.metadata = details;
      console.log(`[STREAM] Video: "${details.title}" (${details.duration}s)`);
    }

    // Extract streaming data
    const streamingData = playerResponse.streamingData;
    if (!streamingData) {
      console.log('[STREAM] No streamingData found');
      return;
    }

    let found = 0;

    // Extract adaptive formats (video and audio separate)
    if (streamingData.adaptiveFormats && Array.isArray(streamingData.adaptiveFormats)) {
      streamingData.adaptiveFormats.forEach(format => {
        if (!format.url && !format.cipher) return;

        const streamInfo = {
          itag: format.itag,
          mimeType: format.mimeType || '',
          quality: format.qualityLabel || format.height || `itag=${format.itag}`,
          bitrate: format.bitrate || format.averageBitrate || 0,
          fps: format.fps || 30,
          width: format.width,
          height: format.height,
          url: format.url || '',
          cipher: format.cipher || '',
          hasUrl: !!format.url,
          hasCipher: !!format.cipher,
          audioCodec: format.audioCodec,
          videoCodec: format.codecs
        };

        if (format.mimeType?.includes('video')) {
          if (!this.streams.video.some(v => v.itag === streamInfo.itag)) {
            this.streams.video.push(streamInfo);
            console.log(`[STREAM] ✓ Video: ${streamInfo.quality}@${streamInfo.fps}fps (itag=${streamInfo.itag})`);
            found++;
          }
        } else if (format.mimeType?.includes('audio')) {
          if (!this.streams.audio.some(a => a.itag === streamInfo.itag)) {
            this.streams.audio.push(streamInfo);
            console.log(`[STREAM] ✓ Audio: ${streamInfo.bitrate}bps (itag=${streamInfo.itag})`);
            found++;
          }
        }
      });
    }

    // Extract regular formats (muxed video+audio)
    if (streamingData.formats && Array.isArray(streamingData.formats)) {
      streamingData.formats.forEach(format => {
        if (!format.url) return;

        const streamInfo = {
          itag: format.itag,
          mimeType: format.mimeType || '',
          quality: format.qualityLabel || 'muxed',
          bitrate: format.bitrate || 0,
          fps: format.fps || 30,
          url: format.url,
          isMuxed: true,
          hasUrl: true
        };

        if (!this.streams.video.some(v => v.itag === streamInfo.itag)) {
          this.streams.video.push(streamInfo);
          console.log(`[STREAM] ✓ Muxed: ${streamInfo.quality} (itag=${streamInfo.itag})`);
          found++;
        }
      });
    }

    if (found > 0) {
      console.log(`[STREAM] SUCCESS: Found ${found} formats total`);
      window.Android?.log?.(`Found ${this.streams.video.length} video + ${this.streams.audio.length} audio`);
    } else {
      console.log('[STREAM] No formats found in streamingData');
    }
  }

  getStreams() {
    return {
      videos: this.streams.video,
      audios: this.streams.audio,
      metadata: this.streams.metadata,
      source: this.streams.source
    };
  }

  printStreams() {
    console.log('[STREAM] ===== CAPTURED STREAMS =====');
    console.log('[STREAM] VIDEO FORMATS:');
    if (this.streams.video.length === 0) {
      console.log('[STREAM]   (none)');
    } else {
      this.streams.video.forEach((v, i) => {
        const url = v.url ? `✓ Direct URL` : (v.cipher ? `🔒 Encrypted` : '❌ No URL');
        console.log(`  [${i}] ${v.quality} @${v.fps}fps | ${url}`);
      });
    }

    console.log('[STREAM] AUDIO FORMATS:');
    if (this.streams.audio.length === 0) {
      console.log('[STREAM]   (none)');
    } else {
      this.streams.audio.forEach((a, i) => {
        const url = a.url ? `✓ Direct URL` : (a.cipher ? `🔒 Encrypted` : '❌ No URL');
        console.log(`  [${i}] ${a.bitrate}bps | ${url}`);
      });
    }

    if (this.streams.metadata) {
      console.log(`[STREAM] METADATA: "${this.streams.metadata.title}"`);
    }
  }

  clearStreams() {
    this.streams = { video: [], audio: [], metadata: null, source: 'ytInitialPlayerResponse' };
    console.log('[STREAM] Cleared');
  }
}

// Export as global instance
window.streamExtractor = new StreamExtractor();

// Helper functions for easy access
window.getVideoStreams = () => window.streamExtractor.getStreams();
window.printStreams = () => window.streamExtractor.printStreams();
window.clearStreams = () => window.streamExtractor.clearStreams();

console.log('[STREAM] Stream Extractor loaded');
console.log('[STREAM] Extracting from: window.ytInitialPlayerResponse');
