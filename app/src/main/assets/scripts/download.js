/**
 * YT Pro - Download Handler
 * Standalone script for video download functionality
 */

console.log('[DOWNLOAD] Script loaded');

function handleDownload() {
  console.log('[DOWNLOAD] Button clicked');
  console.log('[DOWNLOAD] Logging YouTube player...');

  console.log('=== YOUTUBE PLAYER DATA ===');
  console.log('window.ytPlayer:', window.ytPlayer);
  console.log('window.player:', window.player);
  console.log('window.ytplayer:', window.ytplayer);
  console.log('document.querySelector("video"):', document.querySelector('video'));

  if (window.yt) {
    console.log('window.yt:', window.yt);
    if (window.yt.player) console.log('window.yt.player:', window.yt.player);
  }

  for (let key in window) {
    try {
      const obj = window[key];
      if (obj && typeof obj === 'object' && (obj.getVideoData || obj.playVideo || obj.pauseVideo)) {
        console.log(`window.${key}:`, obj);
      }
    } catch(e) {}
  }

  console.log('=== END PLAYER DATA ===');
}

// Export
window.handleDownload = handleDownload;
