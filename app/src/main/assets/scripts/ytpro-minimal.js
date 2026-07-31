console.log("YTPro minimal script loaded");

// Improved ad blocker
(() => {
const _origFetch = window.fetch;
window.fetch = async function(input, init) {
try {
const url = (typeof input === 'string') ? input : input.url;

// Block all ad-related URLs
const adUrls = [
  "googleads.g.doubleclick.net",
  "doubleclick.net",
  "youtube.com/youtubei/v1/player/ad_break",
  "youtube.com/pagead",
  "youtube.com/api/stats/ads",
  "pagead",
  "adservice",
  "ads/",
  "/get_ads"
];

if(adUrls.some(ad => url.includes(ad))) {
  console.log("Blocked ad URL:", url);
  return new Response(JSON.stringify({}), {
    status: 200, 
    headers: {"content-type": "application/json"}
  });
}

// Intercept YouTube API responses
if(url.includes("youtube.com/youtubei/")) {
  const response = await _origFetch.apply(this, arguments);
  try {
    const clone = response.clone();
    let data = await clone.json();
    
    // Remove all ad-related fields
    if(data) {
      delete data.adSlots;
      delete data.playerAds;
      delete data.adPlacements;
      delete data.adBreakHeartbeatParams;
      
      // Deep clean
      if(data.contents) delete data.contents.adSlotRenderer;
      if(data.playerOverlays) delete data.playerOverlays;
      if(data.trackingParams) data.trackingParams = [];
    }
    
    const newBody = JSON.stringify(data);
    const newHeaders = new Headers(response.headers);
    newHeaders.set("content-length", String(newBody.length));
    newHeaders.set("content-type", "application/json");
    
    return new Response(newBody, {
      status: response.status,
      statusText: response.statusText,
      headers: newHeaders
    });
  } catch (e) {
    return response;
  }
}

return _origFetch.apply(this, arguments);
} catch (e) { }
return _origFetch.apply(this, arguments);
};
})();

// Event listeners
document.body.addEventListener('touchstart', e => {});
document.body.addEventListener('touchmove', e => {});
document.body.addEventListener('touchend', e => {});
