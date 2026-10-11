import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import crypto from 'node:crypto';

export const labDir = path.dirname(fileURLToPath(import.meta.url));
export const repo = path.resolve(labDir, '../..');
export const localDir = path.join(labDir, '.local');
const sourceDir = path.join(repo, 'app/src/main/java/cn/weibo/filedownloader');
export const agents = {
  mobile: 'Mozilla/5.0 (Linux; Android 12; Medium_Phone) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36',
  desktop: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36',
};
const require = createRequire(import.meta.url);
let browser;
let playwrightRuntime;
let loginContext;
const loginProfileDir = path.join(localDir, 'login-profile');
const loginStorageState = path.join(localDir, 'login-state.json');

async function loadPlaywright() {
  if (playwrightRuntime) return playwrightRuntime;
  for (const directory of [process.env.XHS_LAB_NODE_MODULES, '', path.join(process.env.USERPROFILE || '', '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules')]) {
    try { playwrightRuntime = require(directory ? path.join(directory, 'playwright') : 'playwright'); break; } catch {}
  }
  if (!playwrightRuntime) throw new Error('缺少 Playwright。请在 tools/xhs-lab 运行 npm install，或设置 XHS_LAB_NODE_MODULES。');
  return playwrightRuntime;
}

function browserExecutable() {
  return [process.env.XHS_LAB_BROWSER, 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Google/Chrome/Application/chrome.exe'].find(p => p && existsSync(p));
}

export async function getBrowser() {
  if (browser?.isConnected()) return browser;
  const playwright = await loadPlaywright();
  browser = await playwright.chromium.launch({ executablePath: browserExecutable(), headless: true });
  return browser;
}
export async function closeBrowser() { await browser?.close(); browser = undefined; }

export async function openLogin() {
  const playwright = await loadPlaywright();
  await mkdir(localDir, { recursive: true });
  if (!loginContext || loginContext.browser()?.isConnected() === false) {
    loginContext = await playwright.chromium.launchPersistentContext(loginProfileDir, {
      executablePath: browserExecutable(),
      headless: false,
      userAgent: agents.desktop,
      viewport: { width: 1280, height: 900 },
      locale: 'zh-CN',
    });
  }
  const page = loginContext.pages()[0] || await loginContext.newPage();
  if (!/rednote\.com|xiaohongshu\.com/.test(page.url())) await page.goto('https://www.rednote.com/login', { waitUntil: 'domcontentloaded', timeout: 25000 }).catch(() => {});
  await loginContext.storageState({ path: loginStorageState });
  return { url: page.url(), cookieNames: (await loginContext.cookies()).map(cookie => cookie.name), storageState: loginStorageState };
}

export async function closeLogin() {
  if (loginContext) await loginContext.close().catch(() => {});
  loginContext = undefined;
}

async function storedState() {
  if (existsSync(loginStorageState)) return loginStorageState;
  if (loginContext) {
    await loginContext.storageState({ path: loginStorageState }).catch(() => {});
    if (existsSync(loginStorageState)) return loginStorageState;
  }
  return undefined;
}

function literal(source, label) {
  const at = source.indexOf(label);
  if (at < 0) throw new Error('源码中找不到脚本：' + label);
  const start = source.indexOf('"""', at) + 3;
  const end = source.indexOf('"""', start);
  if (start < 3 || end < start) throw new Error('脚本边界不完整：' + label);
  return source.slice(start, end);
}
export async function scripts() {
  const loader = await readFile(path.join(sourceDir, 'XiaohongshuCreatorWebLoader.kt'), 'utf8');
  const client = await readFile(path.join(sourceDir, 'XiaohongshuClient.kt'), 'utf8');
  return {
    extract: literal(loader, 'val EXTRACT_WORKS_SCRIPT'),
    link: literal(loader, 'val PROFILE_LINK_SCRIPT'),
    note: literal(client, 'val EXTRACT_NOTE_SCRIPT'),
    observer: literal(loader, 'val NETWORK_OBSERVER_SCRIPT'),
    status: literal(loader, 'val PROFILE_STATUS_SCRIPT'),
    scroll: literal(loader, 'val SCROLL_PROFILE_SCRIPT'),
    auth: literal(await readFile(path.join(sourceDir, 'XiaohongshuSession.kt'), 'utf8'), 'val PAGE_AUTH_SCRIPT'),
    revision: crypto.createHash('sha256').update(loader + client).digest('hex').slice(0, 12),
  };
}

export function allowedUrl(value) {
  const url = new URL(value);
  const hosts = ['www.xiaohongshu.com', 'xiaohongshu.com', 'edith.xiaohongshu.com', 'xhslink.cn', 'xhslink.com', 'www.xhslink.com'];
  if (url.protocol !== 'https:' || !hosts.includes(url.hostname) || url.username || url.password) throw new Error('只接受小红书 HTTPS 链接');
  return url.href;
}
function redactUrl(value) {
  try { const u = new URL(value); for (const key of [...u.searchParams.keys()]) if (/token|cookie|session|sign|profile/i.test(key)) u.searchParams.set(key, '[present]'); return u.href; } catch { return value; }
}
export function parseStateExpression(html) {
  for (const marker of ['window.__INITIAL_STATE__', 'window.__SETUP_SERVER_STATE__']) {
    const at = html.indexOf(marker);
    if (at < 0) continue;
    const start = html.indexOf('{', at + marker.length);
    let depth = 0, quote = '', escaped = false;
    for (let i = start; i >= 0 && i < html.length; i++) {
      const ch = html[i];
      if (quote) { if (escaped) escaped = false; else if (ch === '\\') escaped = true; else if (ch === quote) quote = ''; continue; }
      if (ch === '"' || ch === "'") quote = ch;
      else if (ch === '{') depth++;
      else if (ch === '}' && --depth === 0) return { marker, expression: html.slice(start, i + 1) };
    }
  }
  return null;
}

async function inspect(page, code) {
  const diagnostics = await page.evaluate(() => {
    const root = window.__INITIAL_STATE__ || window.__SETUP_SERVER_STATE__ || {};
    const raw = [], seen = new Set();
    function walk(value, source, depth = 0) {
      if (!value || typeof value !== 'object' || seen.has(value) || depth > 14) return;
      seen.add(value);
      if (Array.isArray(value)) return value.forEach(v => walk(v, source, depth + 1));
      const card = value.noteCard || value.note_card || value;
      const title = card.displayTitle || card.display_title || card.title;
      if (title && card.cover) {
        const id = String(card.noteId || card.note_id || card.id || value.noteId || value.note_id || value.id || '');
        raw.push({ id, title: String(title), source, tokenPresent: !!(card.xsecToken || card.xsec_token || value.xsecToken || value.xsec_token),
          uuidLike: /^[0-9a-f]{12}4[0-9a-f]{3}[89ab][0-9a-f]{15}$/i.test(id) });
        return;
      }
      Object.keys(value).forEach(k => walk(value[k], source, depth + 1));
    }
    walk(root.profile?.noteData, 'profile.noteData');
    walk(root.user?.notes, 'user.notes');
    walk(root.user?.userPageData?.notes, 'user.userPageData.notes');
    const loggedIn = typeof root.user?.loggedIn === 'boolean' ? root.user.loggedIn : null;
    const result = { stateKeys: Object.keys(root), rawCards: raw, loggedIn,
      scriptFamily: [...document.scripts].map(s => s.src).find(s => /formula-static\/(ranchi|xhs-pc-web)/.test(s))?.match(/formula-static\/([^/]+)/)?.[1] || '',
      signerPresent: typeof window._webmsxyw === 'function', cookieNames: document.cookie.split(';').map(v => v.trim().split('=')[0]).filter(Boolean),
      noteLinks: [...document.querySelectorAll('a[href*="/explore/"],a[href*="/discovery/item/"]')].map(a => ({ path: new URL(a.href).pathname, tokenPresent: new URL(a.href).searchParams.has('xsec_token') })).slice(0, 40) };
    return result;
  });
  let works = [], extractError = '';
  try { works = JSON.parse(await page.evaluate(code.extract) || '[]'); } catch (e) { extractError = e.message; }
  return { ...diagnostics, works, extractError, revision: code.revision, url: page.url() };
}

export async function replay(html, url = 'https://www.xiaohongshu.com/user/profile/fixture', extractOverride) {
  const b = await getBrowser(), context = await b.newContext();
  const page = await context.newPage(), state = parseStateExpression(html), code = await scripts();
  if (extractOverride) code.extract = extractOverride;
  try {
    await page.route('**/*', route => route.request().isNavigationRequest()
      ? route.fulfill({ contentType: 'text/html', body: html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '') }) : route.abort());
    await page.goto(url, { waitUntil: 'domcontentloaded' });
    if (state) await page.evaluate(`${state.marker}=(${state.expression})`);
    return { mode: 'replay', ...(await inspect(page, code)), network: [], stateFound: !!state, capturedAt: new Date().toISOString() };
  } finally { await context.close(); }
}

export class Lab {
  constructor() { this.context = null; this.page = null; this.report = null; this.network = []; this.inflight = new Set(); this.provenance = 'live'; }
  async capture(url, mode = 'mobile', runApi = false) {
    allowedUrl(url);
    await this.context?.close();
    const b = await getBrowser();
    this.context = await b.newContext({ storageState: await storedState(), userAgent: agents[mode] || agents.mobile, viewport: mode === 'desktop' ? { width: 1440, height: 960 } : { width: 412, height: 914 }, locale: 'zh-CN' });
    this.page = await this.context.newPage(); this.network = []; this.inflight = new Set(); this.provenance = 'live';
    const page = this.page, network = this.network, inflight = this.inflight;
    page.on('response', response => {
      const request = response.request();
      if (!/\/api\/(sns|sec)\//.test(response.url())) return;
      const headers = request.headers();
      const entry = { url: redactUrl(response.url()), status: response.status(), method: request.method(), headerNames: Object.keys(headers).filter(k => !k.startsWith('sec-')), signerAppId: '' };
      try { entry.signerAppId = JSON.parse(Buffer.from((headers['x-s'] || '').replace(/^XYW_/, ''), 'base64').toString()).appId || ''; } catch {}
      network.push(entry);
      if (/user_posted|\/feed(?:\?|$)|user\/selfinfo/.test(response.url())) {
        const pending = response.text().then(body => {
          entry.bodyPrefix = body.slice(0, 240).replace(/("(?:xsec_token|xsecToken)"\s*:\s*")[^"]*/g, '$1[present]');
          try { const json = JSON.parse(body); entry.code = json.code; entry.count = json.data?.notes?.length; entry.hasMore = json.data?.has_more; } catch {}
        }).catch(e => { entry.bodyError = e.message; }).finally(() => inflight.delete(pending));
        inflight.add(pending);
      }
    });
    page.on('requestfailed', request => {
      if (/\/api\/sns\//.test(request.url())) network.push({ url: redactUrl(request.url()), status: 'network error', error: request.failure()?.errorText });
    });
    const code = await scripts();
    await page.addInitScript(code.observer);
    let navigationError = '';
    try { await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 25000 }); } catch (e) { navigationError = e.message; }
    await page.waitForFunction(() => !!window.__INITIAL_STATE__ || !!window.__SETUP_SERVER_STATE__, { timeout: 8000 }).catch(() => {});
    await page.waitForTimeout(2500);
    let seedNote = null;
    if (!page.url().includes('/user/profile/')) {
      try { seedNote = JSON.parse(await page.evaluate(code.note) || 'null'); } catch {}
      let profile = await page.evaluate(code.link).catch(() => '');
      if (!profile && seedNote?.user?.userId) {
        const current = new URL(page.url());
        const token = current.searchParams.get('xsec_token') || '';
        profile = `https://www.xiaohongshu.com/user/profile/${seedNote.user.userId}?xsec_token=${encodeURIComponent(token)}&xsec_source=pc_user&channel_type=web_note_detail_r10&parent_page_channel_type=web_profile_board`;
      }
      if (profile) {
        allowedUrl(profile);
        await page.goto(profile, { waitUntil: 'domcontentloaded', timeout: 25000 }).catch(e => { navigationError = e.message; });
        await page.waitForTimeout(4500);
      }
    }
    const report = await inspect(page, code);
    if (runApi && page.url().includes('/user/profile/')) report.api = await this.api('');
    await Promise.allSettled([...inflight]);
    Object.assign(report, { mode, provenance: 'live', capturedAt: new Date().toISOString(), navigationError, network, seedNote: seedNote ? { id: seedNote.noteId, title: seedNote.title || seedNote.desc, images: seedNote.imageList?.length || 0, video: !!seedNote.video } : null });
    const html = await page.content();
    await mkdir(localDir, { recursive: true });
    const id = new Date().toISOString().replace(/[:.]/g, '-') + '-' + mode;
    // Replay stores the hydrated state explicitly; raw HTML scripts may still contain pre-hydration placeholders.
    await writeFile(path.join(localDir, id + '.html'), html);
    await writeFile(path.join(localDir, id + '.json'), JSON.stringify(report, null, 2));
    report.captureId = id;
    this.report = report;
    return report;
  }
  async api() {
    if (!this.page || !this.page.url().includes('/user/profile/')) throw new Error('请先实时载入作者页');
    const code = await scripts();
    const before = (await inspect(this.page, code)).works.length;
    await this.page.evaluate(code.status); // Drain initial navigation responses.
    await this.page.evaluate(code.scroll);
    const started = Date.now();
    let report, status;
    do {
      await this.page.waitForTimeout(600);
      report = await inspect(this.page, code);
      status = JSON.parse(await this.page.evaluate(code.status));
      if (status.responses.length) this.observed = [...(this.observed || []), ...status.responses];
    } while (report.works.length <= before && status.hasMore !== false && Date.now() - started < 15000);
    this.report = report;
    return { before, after: report.works.length, hasMore: status.hasMore, works: report.works,
      responses: (this.observed || []).map(r => ({status:r.status,code:r.code,count:r.notes.length,cursor:r.cursor,requestedCursor:r.requestedCursor})), network: this.network };
  }
  async detail(work) {
    const url = work.sourceUrl || `https://www.xiaohongshu.com/explore/${work.id}${work.token ? '?xsec_token=' + encodeURIComponent(work.token) + '&xsec_source=pc_user' : ''}`;
    allowedUrl(url);
    const context = this.context || await (await getBrowser()).newContext({ userAgent: agents.mobile });
    const page = await context.newPage(), code = await scripts();
    try {
      const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 25000 });
      await page.waitForTimeout(2500);
      const raw = await page.evaluate(code.note);
      let note; try { note = JSON.parse(raw || 'null'); } catch {}
      return { status: response?.status(), url: redactUrl(page.url()), parsed: !!note, title: note?.title || note?.desc || '', id: note?.noteId || '', images: note?.imageList?.length || 0, video: !!note?.video, text: (await page.locator('body').innerText()).slice(0, 350) };
    } finally { await page.close(); if (!this.context) await context.close(); }
  }
  async close() { await this.context?.close(); this.context = null; this.page = null; }
}
