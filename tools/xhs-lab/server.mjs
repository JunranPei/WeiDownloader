import http from 'node:http';
import { readFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { Lab, localDir, replay, openLogin, closeLogin } from './engine.mjs';
import { runMock } from './mock.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const lab = new Lab();
const port = Number(process.env.PORT || 18766);
const page = `<!doctype html><meta charset="utf-8"><title>XHS local lab</title>
<style>body{font:14px system-ui;max-width:1200px;margin:20px auto;color:#222}input,button{padding:8px;margin:3px}button{cursor:pointer}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}pre{white-space:pre-wrap;background:#f5f5f5;padding:12px;max-height:520px;overflow:auto}.card{border:1px solid #ddd;border-radius:8px;padding:8px;margin:5px 0}</style>
<h1>小红书作者页本地调试台</h1><p>先用快照回放迭代，只有最后验收才实时载入。</p>
<input id="url" size="95" value="https://xhslink.cn/o/2uN5D8VgpIH"><select id="mode"><option>mobile</option><option>desktop</option></select><button onclick="login()">打开本地登录页</button><button onclick="capture()">实时载入</button><button onclick="api()">测试分页 API</button><button onclick="mock()">回放模拟登录</button>
<div class="grid"><section><h2>作者作品</h2><div id="cards"></div></section><section><h2>诊断</h2><pre id="report">尚未运行</pre></section></div>
<script>
let report;
async function call(path,body){const r=await fetch(path,{method:body?'POST':'GET',headers:{'content-type':'application/json'},body:body&&JSON.stringify(body)});return r.json()}
function show(r){report=r;document.querySelector('#report').textContent=JSON.stringify(r,null,2);const cards=r.works||[];document.querySelector('#cards').innerHTML=cards.map((w,i)=>'<div class="card"><b>'+i+'. '+(w.title||'(无标题)')+'</b><br><small>'+w.id+' | '+w.sourceUrl+'</small><br><button onclick="detail('+i+')">测试详情</button></div>').join('')||'<p>没有作品</p>'}
async function capture(){show(await call('/capture',{url:document.querySelector('#url').value,mode:document.querySelector('#mode').value}))}
async function login(){show(await call('/login',{}))}
async function api(){show(await call('/api',{cursor:''}))}
async function mock(){show(await call('/mock',{}))}
async function detail(i){const r=await call('/detail',{work:report.works[i]});document.querySelector('#report').textContent=JSON.stringify(r,null,2)}
async function replayFile(f){show(await call('/replay',{file:f}))}
async function snapshots(){const r=await call('/snapshots');document.querySelector('#cards').innerHTML=(r.files||[]).map(f=>'<div class="card"><button onclick="replayFile('+JSON.stringify(f)+')">回放 '+f+'</button></div>').join('')}
snapshots();
</script>`;
function send(res, value, status = 200) { const body = JSON.stringify(value); res.writeHead(status, {'content-type':'application/json; charset=utf-8'}); res.end(body); }
const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/') { res.writeHead(200, {'content-type':'text/html; charset=utf-8'}); res.end(page); return; }
    let body = ''; for await (const chunk of req) body += chunk;
    const data = body ? JSON.parse(body) : {};
    if (req.url === '/capture') send(res, await lab.capture(data.url, data.mode));
    else if (req.url === '/login') send(res, await openLogin());
    else if (req.url === '/api') send(res, await lab.api(data.cursor || ''));
    else if (req.url === '/mock') send(res, await runMock());
    else if (req.url === '/detail') send(res, await lab.detail(data.work));
    else if (req.url === '/snapshots') send(res, {files: (await readdir(localDir).catch(()=>[])).filter(f=>f.endsWith('.html')).sort().reverse()});
    else if (req.url === '/replay') { const file = path.basename(data.file); await lab.close(); send(res, await replay(await readFile(path.join(localDir,file),'utf8'), 'https://www.xiaohongshu.com/user/profile/fixture')); }
    else send(res, {error:'not found'}, 404);
  } catch (e) { send(res, {error:e.stack || e.message}, 500); }
});
server.listen(port, '127.0.0.1', () => console.log(`XHS local lab: http://127.0.0.1:${port}`));
process.on('SIGINT', async () => { await lab.close(); await closeLogin(); server.close(); process.exit(0); });
