// Run with: node tools/verify-media-extractors.cjs
// Executes the same assets shipped to WebView, so Kotlin compilation cannot hide JS syntax failures.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.join(__dirname, '../app/src/main/assets');
const douyinId = '7697980119662749928';
const noteId = '6ad0d56a000000001803ba07';
let checks = 0;
function check(name, test) { test(); checks++; console.log('PASS', name); }

function douyin({ id = douyinId, pathname = '/share/video/' + douyinId + '/', router,
    initial, videos = [], resources = [] } = {}) {
    const location = new URL('https://www.iesdouyin.com' + pathname);
    const script = fs.readFileSync(path.join(assets, 'douyin-post-extractor.js'), 'utf8')
        .replace('__POST_ID_JSON__', JSON.stringify(id));
    return JSON.parse(vm.runInNewContext(script, {
        URL, location, window: { _ROUTER_DATA: router, __INITIAL_STATE__: initial },
        document: {
            title: 'target',
            querySelector(selector) { return selector.startsWith('link') ? { href: location.href } : null; },
            querySelectorAll(selector) { return selector === 'video' ? videos : []; },
        },
        performance: { getEntriesByType() { return resources.map(name => ({ name })); } },
    }));
}

function xhs({ pathname = '/discovery/item/' + noteId, initial = {}, server = {} } = {}) {
    return vm.runInNewContext(fs.readFileSync(path.join(assets, 'xiaohongshu-note-extractor.js'), 'utf8'), {
        location: { pathname }, window: { __INITIAL_STATE__: initial, __SETUP_SERVER_STATE__: server },
    });
}

check('Douyin keeps target details across both stores', () => {
    const sparse = { aweme_id: douyinId, video: {} };
    const full = { aweme_id: douyinId, video: { play_addr: { url_list: ['https://v3-web.douyinvod.com/target.mp4'] } } };
    const result = douyin({ router: { sparse }, initial: { full, other: { aweme_id: '7697980119662749929', video: {} } } });
    assert.equal(result.details.length, 2);
    assert.deepEqual(result.details[1], full);
});
check('Douyin rejects ID prefixes', () => {
    assert.equal(douyin({ pathname: '/share/video/' + douyinId + '1/' }).id, '');
});
check('Douyin accepts exact query identity', () => {
    assert.equal(douyin({ pathname: '/discover?modal_id=' + douyinId }).id, douyinId);
});
check('Douyin does not attribute unrelated network video to a blob player', () => {
    const video = { currentSrc: 'blob:opaque', play() {} };
    const result = douyin({ videos: [video], resources: ['https://v3-web.douyinvod.com/recommendation.mp4'] });
    assert.equal(result.video, 'blob:opaque');
    assert.equal(result.resources, undefined);
});
check('Douyin rejects ambiguous unscoped players', () => {
    const video = { currentSrc: 'https://v3-web.douyinvod.com/target.mp4', play() {} };
    assert.equal(douyin({ videos: [video, video] }).video, '');
});
check('Douyin safely quotes the injected ID', () => {
    assert.equal(douyin({ id: '\"; throw new Error(\"injection\"); //' }).id, '');
});
check('XHS ignores an unrelated direct note and scans both stores', () => {
    const other = { noteId: '6ad0d56a000000001803ba08', imageList: [] };
    const target = { noteId, imageList: [{ livePhoto: true, stream: {} }] };
    const result = JSON.parse(xhs({ initial: { noteData: { data: { noteData: other } } }, server: { target } }));
    assert.deepEqual(result.notes, [target]);
});
check('XHS retains all target representations for coverage comparison', () => {
    const result = JSON.parse(xhs({ initial: { noteId, imageList: [] }, server: { noteId, videoList: [{ masterUrl: 'video.mp4' }] } }));
    assert.equal(result.notes.length, 2);
});
check('XHS supports full 32-character IDs without truncation', () => {
    const id = noteId + '01234567';
    assert.equal(JSON.parse(xhs({ pathname: '/explore/' + id, initial: { noteId: id, video: {} } })).notes[0].noteId, id);
});
check('XHS refuses to extract a note from a profile route', () => {
    assert.equal(xhs({ pathname: '/user/profile/' + noteId, initial: { noteId, video: {} } }), '');
});
check('XHS tolerates cycles in live page state', () => {
    const initial = { noteId, imageList: [] }; initial.self = initial;
    // A cyclic note itself cannot be serialized, but a cyclic surrounding store can.
    const store = { target: { noteId, imageList: [] } }; store.self = store;
    assert.equal(JSON.parse(xhs({ initial: store })).notes.length, 1);
});
console.log(`${checks} extractor checks passed`);
