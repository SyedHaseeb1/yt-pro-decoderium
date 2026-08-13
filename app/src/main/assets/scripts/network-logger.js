/**
 * Network Logger - Log all network requests
 */

console.log('[NET] Network logger started');

// Log fetch requests
const originalFetch = window.fetch;
window.fetch = function(...args) {
  const url = typeof args[0] === 'string' ? args[0] : (args[0]?.url || '');
  console.log('[NET] FETCH:', url);
  return originalFetch.apply(this, args);
};

// Log XMLHttpRequest
const originalOpen = XMLHttpRequest.prototype.open;
XMLHttpRequest.prototype.open = function(method, url, ...rest) {
  console.log('[NET] XHR ' + method + ':', url);
  return originalOpen.apply(this, [method, url, ...rest]);
};

console.log('[NET] Interceptors ready');
