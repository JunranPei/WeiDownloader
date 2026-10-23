(function() {
  if (window.__weiDouyinDetailCaptureInstalled) return;
  window.__weiDouyinDetailCaptureInstalled = true;
  window.__weiDouyinDetails = window.__weiDouyinDetails || [];
  const add = value => {
    if (!value || typeof value !== 'object') return;
    const seen = new Set();
    const walk = node => {
      if (!node || typeof node !== 'object' || seen.has(node)) return;
      seen.add(node);
      const id = String(node.aweme_id || node.awemeId || node.item_id || '');
      if (/^\d{17,20}$/.test(id) && (node.video || node.images || node.image_post_info)) {
        const exists = window.__weiDouyinDetails.some(item => String(item.aweme_id || item.awemeId || '') === id);
        if (!exists) window.__weiDouyinDetails.push(node);
      }
      Object.keys(node).forEach(key => walk(node[key]));
    };
    walk(value);
  };
  const captureResponse = response => {
    const url = String(response?.url || '');
    if (!/\/aweme\/v1\/web\/aweme\/detail\//.test(url)) return;
    response.clone().json().then(add).catch(() => {});
  };
  const originalFetch = window.fetch;
  if (typeof originalFetch === 'function') {
    window.fetch = function() {
      return originalFetch.apply(this, arguments).then(response => {
        captureResponse(response);
        return response;
      });
    };
  }
  const originalOpen = XMLHttpRequest.prototype.open;
  const originalSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url) {
    this.__weiDouyinDetailRequest = /\/aweme\/v1\/web\/aweme\/detail\//.test(String(url || ''));
    return originalOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function() {
    if (this.__weiDouyinDetailRequest) {
      this.addEventListener('load', function() {
        try { add(JSON.parse(this.responseText)); } catch (_) {}
      });
    }
    return originalSend.apply(this, arguments);
  };
})();
