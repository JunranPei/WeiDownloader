// Run with: node tools/verify-instagram-login.cjs
// Exercises the exact WebView verification script with simulated account API responses.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/java/cn/weibo/filedownloader/InstagramSession.kt'), 'utf8');
const script = source.match(/private val VERIFY_SCRIPT = """([\s\S]*?)"""\.trimIndent\(\)/)[1];
const flush = () => new Promise(resolve => setImmediate(resolve));

function harness(respond) {
    const calls = [];
    const context = vm.createContext({
        window: {}, Date: { now: () => 100000 }, AbortController, setTimeout, clearTimeout,
        fetch: async (url, options) => {
            calls.push(url);
            assert.equal(options.credentials, 'include');
            assert.equal(options.redirect, 'error');
            assert.ok(options.signal instanceof AbortSignal);
            return respond(url);
        },
    });
    return {
        calls,
        poll(session = 'session-A') {
            return JSON.parse(vm.runInContext(script.replaceAll('SESSION_FINGERPRINT', session), context)).verified;
        },
    };
}
const response = body => ({ ok: true, json: async () => body });

(async () => {
    const authenticated = harness(() => response({ status: 'ok', form_data: { username: 'account' } }));
    assert.equal(authenticated.poll(), false);
    await flush();
    assert.equal(authenticated.poll(), true);
    assert.equal(authenticated.calls.length, 1, 'Polling should reuse the recent account verification');
    assert.equal(authenticated.calls[0], '/api/v1/accounts/edit/web_form_data/', 'Use the endpoint verified on the logged-in page');

    const anonymous = harness(() => response({ status: 'fail', message: 'login_required' }));
    anonymous.poll();
    await flush();
    assert.equal(anonymous.poll(), false);

    const networkFailure = harness(() => { throw new Error('offline'); });
    networkFailure.poll();
    await flush();
    assert.equal(networkFailure.poll(), false);

    const editFallback = harness(url => response(url.includes('current_user')
        ? { status: 'ok', user: { pk: 123, username: 'account' } } : { status: 'fail' }));
    editFallback.poll();
    await flush();
    assert.equal(editFallback.poll(), true);
    assert.equal(editFallback.calls.length, 2);

    let valid = true;
    const changedSession = harness(() => response(valid
        ? { status: 'ok', form_data: { username: 'old-account' } } : { status: 'fail' }));
    changedSession.poll();
    await flush();
    assert.equal(changedSession.poll(), true);
    valid = false;
    assert.equal(changedSession.poll('session-B'), false, 'A new cookie cannot inherit the old session verification');
    await flush();
    assert.equal(changedSession.poll('session-B'), false);

    let resolveOld;
    const pendingSession = harness(url => url.includes('web_form_data') && !resolveOld
        ? new Promise(resolve => { resolveOld = resolve; }) : response({ status: 'fail' }));
    pendingSession.poll('old');
    pendingSession.poll('new');
    await flush();
    resolveOld(response({ status: 'ok', form_data: { username: 'old-account' } }));
    await flush();
    assert.equal(pendingSession.poll('new'), false, 'An old pending response cannot authenticate a new session');
    console.log('PASS: 6 Instagram login verification scenarios');
})().catch(error => { console.error(error); process.exitCode = 1; });
