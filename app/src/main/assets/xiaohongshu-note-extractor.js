(function () {
    const routeId = (location.pathname.match(/\/(?:explore|discovery\/item)\/([0-9a-fA-F]{32}|[0-9a-fA-F]{24})(?:\/|$)/) || [])[1] || '';
    if (!routeId) return '';
    const notes = [];
    const seen = new Set();
    const fields = ['imageList', 'video', 'videoInfo', 'video_info', 'playInfo', 'play_info', 'media', 'videos', 'videoList', 'video_list', 'mediaList'];
    const walk = value => {
        if (!value || typeof value !== 'object' || seen.has(value)) return;
        seen.add(value);
        if (String(value.noteId || '') === routeId && fields.some(key => value[key] != null)) notes.push(value);
        Object.keys(value).forEach(key => walk(value[key]));
    };
    walk(window.__INITIAL_STATE__);
    walk(window.__SETUP_SERVER_STATE__);
    return notes.length ? JSON.stringify({notes}) : '';
})()
