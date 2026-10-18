(function() {
  const id = __POST_ID_JSON__;
  const canonical = document.querySelector("link[rel='canonical']")?.href || location.href;
  const matches = value => {
    if (!value) return false;
    try {
      const url = new URL(value, location.href);
      return url.pathname.split('/').includes(id) ||
        ['modal_id', 'aweme_id', 'item_id', 'video_id', 'note_id'].some(key => url.searchParams.get(key) === id);
    } catch (_) { return false; }
  };
  const pageMatches = matches(canonical) || matches(location.href);
  if (!pageMatches) return JSON.stringify({id: ''});

  const seen = new Set();
  const details = [];
  const walk = value => {
    if (!value || typeof value !== 'object' || seen.has(value)) return;
    seen.add(value);
    if (String(value.aweme_id || value.awemeId || '') === id && (value.video || value.images || value.image_post_info)) {
      details.push(value);
    }
    Object.keys(value).forEach(key => walk(value[key]));
  };
  walk(window._ROUTER_DATA);
  walk(window.__INITIAL_STATE__);

  let article = null;
  for (const node of document.querySelectorAll("script[type='application/ld+json']")) {
    try {
      const value = JSON.parse(node.textContent || '{}');
      if (matches(value?.url || '') && String(value?.['@type'] || '').toLowerCase() === 'article') {
        article = value;
        break;
      }
    } catch (_) {}
  }
  if (article) {
    const values = Array.isArray(article.image) ? article.image : [article.image];
    const images = values.map(value => typeof value === 'string' ? value : value?.url || value?.contentUrl || '').filter(Boolean);
    const author = typeof article.author === 'object' ? article.author?.name || '' : article.author || '';
    return JSON.stringify({id, details, images, title: article.headline || article.description || '', author});
  }

  const exactContainer = document.querySelector('[class~="video_' + id + '"]');
  const marker = document.querySelector('[data-e2e-aweme-id="' + id + '"]');
  const scope = exactContainer || marker?.closest('[data-e2e="video-detail"]');
  const mobileShare = /(^|\.)iesdouyin\.com$/i.test(location.hostname) &&
    location.pathname.split('/').includes(id);
  const pageVideos = document.querySelectorAll('video');
  const video = scope?.querySelector('video') ||
    (mobileShare && pageVideos.length === 1 ? pageVideos[0] : null);
  if (video) {
    video.muted = true;
    const promise = video.play();
    if (promise?.catch) promise.catch(() => {});
  }
  const directVideo = video?.currentSrc || video?.src || video?.querySelector('source')?.src || '';
  const title = document.querySelector("meta[name='description']")?.content || document.title || '';
  const author = marker?.querySelector("[class*='author']")?.textContent || '';
  return JSON.stringify({id, details, video: directVideo, poster: video?.poster || '', title, author});
})()
