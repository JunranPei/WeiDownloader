import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { replay, scripts, closeBrowser, getBrowser } from './engine.mjs';
import { mockWorks, mockPages, mockDetails, runMock } from './mock.mjs';
import { after } from 'node:test';

after(async () => { await closeBrowser(); });

test('replays a captured profile without touching the emulator', async () => {
  const html = await readFile(new URL('./fixtures/guest-profile.html', import.meta.url), 'utf8');
  const result = await replay(html, 'https://www.xiaohongshu.com/user/profile/5cf0ded3000000001802deeb');
  assert.equal(result.mode, 'replay');
  assert.ok(result.revision);
  assert.equal(result.stateFound, true);
  assert.ok(result.rawCards.length >= 8);
  assert.equal(result.works.length, 0, '公开作者页的脱敏占位卡片不能被当成可点击作品');
});

test('source script extraction stays available for the app implementation', async () => {
  const code = await scripts();
  assert.match(code.extract, /isNoteId/);
  assert.match(code.observer, /user_posted/);
  assert.match(code.auth, /loggedIn/);
});

test('mocked authenticated feed exercises the >8 and detail acceptance path locally', async () => {
  const result = await runMock();
  assert.equal(result.works.length, 14);
  assert.equal(result.rawCards.length, 14);
  assert.equal(mockPages[0].hasMore, true);
  assert.equal(mockPages[1].hasMore, false);
  for (const work of mockWorks) {
    assert.equal(mockDetails[work.id].id, work.id);
    assert.equal(mockDetails[work.id].imageList.length, 1);
  }
});


test('guest cookies cannot turn anonymous website state into authenticated evidence', async () => {
  const b = await getBrowser(), context = await b.newContext(), page = await context.newPage(), code = await scripts();
  try {
    for (const loggedIn of [false, true]) {
      await page.evaluate(loggedIn => { window.__INITIAL_STATE__ = {user: {
        loggedIn: {_value: loggedIn}, userInfo: {_value: {guest: !loggedIn, userId: 'present'}}
      }}; }, loggedIn);
      const evidence = JSON.parse(await page.evaluate(code.auth));
      assert.equal(evidence.loggedIn, loggedIn);
      assert.equal(evidence.guest, !loggedIn);
    }
  } finally { await context.close(); }
});

test('observer reads real fetch and XHR replies without issuing duplicate requests', async () => {
  const b = await getBrowser(), context = await b.newContext(), page = await context.newPage(), code = await scripts();
  let requests = 0;
  await page.route('https://www.xiaohongshu.com/**', r => r.fulfill({contentType:'text/html',body:'<html></html>'}));
  await page.route('https://edith.xiaohongshu.com/**', r => {
    requests++;
    const cursor = new URL(r.request().url()).searchParams.get('cursor');
    return r.fulfill({headers:{'access-control-allow-origin':'*'},contentType:'application/json',body:JSON.stringify({success:true,code:0,data:{
      cursor:cursor?'':'next',has_more:!cursor,notes:[{id:'646b4475000000000703b473',display_title:'Example',xsec_token:'example-token',cover:{url_default:'https://example.com/cover'}}]
    }})});
  });
  try {
    await page.addInitScript(code.observer);
    await page.goto('https://www.xiaohongshu.com/user/profile/example');
    await page.evaluate(async () => {
      await fetch('https://edith.xiaohongshu.com/api/sns/web/v1/user_posted?user_id=example&cursor=');
      await new Promise(resolve => { const xhr=new XMLHttpRequest(); xhr.open('GET','https://edith.xiaohongshu.com/api/sns/web/v1/user_posted?user_id=example&cursor=next');xhr.onload=resolve;xhr.send(); });
    });
    await page.waitForFunction(() => window.__weiXhsResponses.length === 2);
    const result = JSON.parse(await page.evaluate(code.status));
    assert.equal(requests, 2);
    assert.equal(result.responses.length, 2);
    assert.equal(result.responses[0].hasMore, true);
    assert.equal(result.responses[1].hasMore, false);
    assert.equal(result.responses[1].requestedCursor, 'next');
    assert.equal(result.responses[1].notes[0].token, 'example-token');
    assert.equal(JSON.parse(await page.evaluate(code.status)).responses.length, 0);
  } finally { await context.close(); }
});


test('a 32-character DOM link is not truncated into a usable 24-character ID', async () => {
  const b=await getBrowser(), c=await b.newContext(), p=await c.newPage(), code=await scripts();
  try {
    await p.setContent('<a href="https://www.xiaohongshu.com/explore/60f9bfde158b4ae9bd0e81e60184bfb4">Example<img src="https://example.com/cover"></a>');
    assert.equal(JSON.parse(await p.evaluate(code.extract)).length, 0);
  } finally { await c.close(); }
});
