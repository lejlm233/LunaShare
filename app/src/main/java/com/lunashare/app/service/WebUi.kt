package com.lunashare.app.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Modern single-file web UI for the HTTP file server — Apple Design style.
 *
 * Zero external dependencies — all CSS/JS is embedded in one HTML page,
 * so it works fully offline in a LAN environment.
 *
 * Server-side features it relies on (all implemented in FileServer):
 *   - GET  + `Accept: application/json`  → directory listing as JSON
 *   - PUT                                → upload file
 *   - MKCOL                              → create folder
 *   - DELETE                             → delete file/folder
 *   - MOVE + `Destination:` header       → rename / move
 *
 * Security notes:
 *   - All file names are rendered via DOM textContent (no innerHTML for names).
 *   - Click actions use event delegation with data-* attributes, so file names
 *     containing quotes/backslashes can never break out of inline handlers.
 *
 * Design notes (Apple Design principles):
 *   - Translucent materials: floating toolbar/cards use backdrop-filter blur.
 *   - Typography: system font stack, negative tracking on large titles.
 *   - Response: feedback on pointer-down (scale), no dead transitions.
 *   - Reduced motion is honored via prefers-reduced-motion.
 */
object WebUi {

    private val json = Json

    @Serializable
    data class EntryDto(
        val name: String,
        val size: Long,
        val mtime: Long,
        val dir: Boolean
    )

    @Serializable
    data class ListingDto(
        val path: String,
        val parent: String?,
        val entries: List<EntryDto>
    )

    fun buildListingJson(path: String, parent: String?, entries: List<EntryDto>): String =
        json.encodeToString(ListingDto.serializer(), ListingDto(path, parent, entries))

    /**
     * The full page template. `%DATA%` is replaced with the JSON listing payload.
     *
     * NOTE: no `${}` template literals in the JS below — this string is a Kotlin
     * raw string, so `$` is not escaped inside it.
     */
    fun pageTemplate(): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="color-scheme" content="light dark">
<link rel="icon" href="%FAVICON%">
<title>LunaShare</title>
<style>
:root {
  --bg: #f5f5f7;
  --surface: rgba(255, 255, 255, 0.72);
  --surface-strong: rgba(255, 255, 255, 0.92);
  --surface-hover: rgba(0, 0, 0, 0.05);
  --text: #1d1d1f;
  --text-secondary: #86868b;
  --border: rgba(0, 0, 0, 0.08);
  --accent: #0071e3;
  --accent-hover: #0077ed;
  --accent-soft: rgba(0, 113, 227, 0.1);
  --danger: #ff3b30;
  --danger-soft: rgba(255, 59, 48, 0.12);
  --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.04);
  --shadow-md: 0 4px 16px rgba(0, 0, 0, 0.08), 0 1px 3px rgba(0, 0, 0, 0.04);
  --shadow-lg: 0 12px 40px rgba(0, 0, 0, 0.12);
  --radius-sm: 10px;
  --radius-md: 14px;
  --radius-lg: 20px;
  --blur: blur(20px) saturate(180%);
}
[data-theme="dark"] {
  --bg: #000000;
  --surface: rgba(28, 28, 30, 0.72);
  --surface-strong: rgba(28, 28, 30, 0.94);
  --surface-hover: rgba(255, 255, 255, 0.08);
  --text: #f5f5f7;
  --text-secondary: #98989d;
  --border: rgba(255, 255, 255, 0.12);
  --accent: #0a84ff;
  --accent-hover: #409cff;
  --accent-soft: rgba(10, 132, 255, 0.18);
  --danger: #ff453a;
  --danger-soft: rgba(255, 69, 58, 0.18);
  --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.3);
  --shadow-md: 0 4px 16px rgba(0, 0, 0, 0.35);
  --shadow-lg: 0 12px 40px rgba(0, 0, 0, 0.5);
}
* { box-sizing: border-box; margin: 0; padding: 0; }
html { -webkit-text-size-adjust: 100%; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display",
               "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  background: var(--bg);
  color: var(--text);
  line-height: 1.5;
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
  transition: background-color .35s ease, color .35s ease;
}
::selection { background: var(--accent-soft); }
.app { max-width: 840px; margin: 0 auto; padding: 24px 20px 56px; }

/* ── Header ── */
.header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 8px 0 20px; }
.brand { display: flex; align-items: center; gap: 12px; }
.brand .logo {
  width: 42px; height: 42px; border-radius: 12px; display: flex; align-items: center;
  justify-content: center; font-size: 22px;
  background: linear-gradient(150deg, #0a84ff, #5e5ce6);
  box-shadow: 0 6px 18px rgba(10, 132, 255, 0.35);
}
.brand .name {
  font-size: 24px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.1;
}
.brand .subtitle {
  font-size: 12px; color: var(--text-secondary); font-weight: 500; letter-spacing: 0.01em;
}
.icon-btn {
  border: none; cursor: pointer;
  width: 38px; height: 38px; border-radius: var(--radius-sm);
  display: inline-flex; align-items: center; justify-content: center; font-size: 17px;
  background: var(--surface);
  color: var(--text);
  -webkit-backdrop-filter: var(--blur); backdrop-filter: var(--blur);
  box-shadow: var(--shadow-sm);
  transition: transform 100ms ease-out, background-color .2s ease, box-shadow .2s ease;
}
.icon-btn:hover { background: var(--surface-strong); box-shadow: var(--shadow-md); }
.icon-btn:active { transform: scale(0.94); }
.header-actions { display: flex; gap: 8px; }

/* ── Toolbar (floating translucent) ── */
.toolbar {
  position: sticky; top: 12px; z-index: 10;
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  background: var(--surface);
  -webkit-backdrop-filter: var(--blur); backdrop-filter: var(--blur);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 10px;
  margin-bottom: 16px;
  box-shadow: var(--shadow-md);
}
.search {
  flex: 1; min-width: 140px; display: flex; align-items: center; gap: 8px;
  background: var(--surface-strong);
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  padding: 8px 12px;
  transition: border-color .2s ease, box-shadow .2s ease;
}
.search:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}
.search input {
  flex: 1; border: none; outline: none; background: transparent; color: var(--text);
  font-size: 14px; font-family: inherit;
}
.search input::placeholder { color: var(--text-secondary); }
.btn {
  border: none; cursor: pointer; border-radius: var(--radius-sm); font-size: 14px; font-weight: 600;
  padding: 9px 16px; display: inline-flex; align-items: center; gap: 6px;
  background: var(--surface-strong); color: var(--text);
  -webkit-backdrop-filter: var(--blur); backdrop-filter: var(--blur);
  box-shadow: var(--shadow-sm);
  transition: transform 100ms ease-out, background-color .2s ease, box-shadow .2s ease;
  font-family: inherit;
}
.btn:hover { box-shadow: var(--shadow-md); }
.btn:active { transform: scale(0.96); }
.btn.primary { background: var(--accent); color: #fff; }
.btn.primary:hover { background: var(--accent-hover); }
.btn.danger { background: var(--danger-soft); color: var(--danger); }

/* ── Breadcrumb ── */
.crumbs { display: flex; align-items: center; flex-wrap: wrap; gap: 2px; font-size: 14px; color: var(--text-secondary); }
.crumbs a {
  color: var(--text-secondary); text-decoration: none; padding: 5px 9px; border-radius: 8px;
  transition: background-color .15s ease, color .15s ease;
}
.crumbs a:hover { background: var(--surface-hover); color: var(--text); }
.crumbs .sep { opacity: .4; }
.crumbs .current { color: var(--text); font-weight: 600; padding: 5px 9px; }

/* ── Breadcrumb + selection count row ── */
.bread-area {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  margin-bottom: 12px;
}
.bread-area .count {
  font-size: 13.5px; font-weight: 600; color: var(--text-secondary);
  flex: 0 0 auto; min-width: 64px;
}

/* ── File list card ── */
.card {
  background: var(--surface);
  -webkit-backdrop-filter: var(--blur); backdrop-filter: var(--blur);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
  overflow: hidden;
}
.list-head, .row {
  display: grid; grid-template-columns: 24px minmax(0, 1fr) 96px 150px 96px;
  align-items: center; padding: 0 18px; gap: 8px;
}
.list-head {
  padding-top: 12px; padding-bottom: 10px; font-size: 12px; font-weight: 600;
  color: var(--text-secondary); letter-spacing: 0.02em;
  border-bottom: 1px solid var(--border); user-select: none;
}
.list-head .sortable {
  cursor: pointer; display: inline-flex; align-items: center; gap: 4px;
  border-radius: 7px; padding: 3px 8px; margin-left: -8px;
  transition: background-color .15s ease, color .15s ease;
}
.list-head .sortable:hover { background: var(--surface-hover); color: var(--text); }
.list-head .sortable.active { color: var(--accent); font-weight: 700; }
.row {
  padding-top: 6px; padding-bottom: 6px;
  transition: background-color .15s ease;
  animation: rowIn .4s cubic-bezier(0.25, 0.1, 0.25, 1) both;
}
.row.selected { background: var(--accent-soft); }
.row .ck {
  width: 16px; height: 16px; cursor: pointer; flex-shrink: 0;
  accent-color: var(--accent);
}
@keyframes rowIn {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: none; }
}
.row:hover, .row:focus-within { background: var(--surface-hover); }
.row .name { display: flex; align-items: center; gap: 12px; min-width: 0; }
.row .name .ic {
  font-size: 20px; flex-shrink: 0; width: 34px; height: 34px; text-align: center;
  display: flex; align-items: center; justify-content: center;
  background: var(--surface-hover); border-radius: 9px;
}
.row .name .nm {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 15px; text-decoration: none; color: inherit;
  display: flex; align-items: center; gap: 12px; min-width: 0; flex: 1;
  transition: color .15s ease;
}
.row .name .nm:hover { color: var(--accent); }
.row .size { font-size: 13px; color: var(--text-secondary); font-variant-numeric: tabular-nums; }
.row .time { font-size: 13px; color: var(--text-secondary); font-variant-numeric: tabular-nums; }
.row .ops { display: flex; gap: 2px; justify-content: flex-end; }
.ops .icon-btn {
  width: 30px; height: 30px; font-size: 15px; box-shadow: none;
  background: transparent; -webkit-backdrop-filter: none; backdrop-filter: none;
}
.ops .icon-btn:hover { background: var(--surface-hover); box-shadow: none; }
@media (hover: none) {
  .ops .icon-btn { background: var(--surface); -webkit-backdrop-filter: none; backdrop-filter: none; }
}

/* ── Empty / status ── */
.empty { text-align: center; padding: 64px 20px; color: var(--text-secondary); }
.empty .big { font-size: 48px; margin-bottom: 14px; opacity: .8; }
.empty p { font-size: 14px; }

/* ── Upload overlay ── */
#drop-zone {
  position: fixed; inset: 0; z-index: 100; display: none; align-items: center; justify-content: center;
  background: rgba(0, 0, 0, 0.35);
  -webkit-backdrop-filter: blur(6px); backdrop-filter: blur(6px);
  transition: opacity .2s ease;
}
#drop-zone.active { display: flex; }
#drop-zone .box {
  background: var(--surface-strong);
  -webkit-backdrop-filter: var(--blur); backdrop-filter: var(--blur);
  border: 2px dashed var(--accent); border-radius: 24px;
  padding: 48px 64px; text-align: center; font-size: 16px; font-weight: 600;
  box-shadow: var(--shadow-lg);
  animation: boxIn .3s cubic-bezier(0.25, 0.1, 0.25, 1) both;
}
@keyframes boxIn { from { opacity: 0; transform: scale(0.96); } to { opacity: 1; transform: none; } }
#drop-zone .box .big { font-size: 44px; margin-bottom: 10px; }

/* ── Toast ── */
#toast {
  position: fixed; left: 50%; bottom: 32px; transform: translateX(-50%) translateY(16px);
  background: rgba(29, 29, 31, 0.92);
  -webkit-backdrop-filter: blur(20px); backdrop-filter: blur(20px);
  color: #fff; padding: 10px 20px; border-radius: 999px;
  font-size: 13.5px; font-weight: 500; opacity: 0; pointer-events: none;
  transition: opacity .25s ease, transform .25s cubic-bezier(0.25, 0.1, 0.25, 1);
  z-index: 200; max-width: 86vw; text-align: center;
  box-shadow: var(--shadow-lg);
}
#toast.show { opacity: 1; transform: translateX(-50%) translateY(0); }
#toast.err { background: rgba(255, 59, 48, 0.95); }

/* ── Footer ── */
.footer { text-align: center; margin-top: 28px; font-size: 12.5px; color: var(--text-secondary); font-weight: 500; }

/* ── Mobile ── */
@media (max-width: 640px) {
  .list-head { grid-template-columns: 24px minmax(0, 1fr) auto; padding: 8px 14px; }
  .list-head .sortable[data-key="size"], .list-head .sortable[data-key="mtime"] { display: none; }
  .row { grid-template-columns: 24px minmax(0, 1fr) auto; padding: 8px 14px; row-gap: 2px; }
  .row .time { display: none; }
  .row .size { font-size: 12px; }
  .row .ops { grid-column: 2 / 4; justify-content: flex-end; }
  .toolbar .btn span.lbl { display: none; }
  .toolbar .btn { padding: 9px 11px; }
  .app { padding: 16px 12px 48px; }
  .brand .name { font-size: 21px; }
  .brand .subtitle { display: none; }
  .card { border-radius: var(--radius-md); }
  .toolbar { top: 8px; }
}

/* ── Accessibility: honor reduced motion / transparency ── */
@media (prefers-reduced-motion: reduce) {
  .row { animation: none; }
  #drop-zone .box { animation: none; }
  body, .icon-btn, .btn, .row, .crumbs a { transition: none; }
}
@media (prefers-reduced-transparency: reduce) {
  .toolbar, .card, .icon-btn, .btn, #toast, #drop-zone .box {
    -webkit-backdrop-filter: none; backdrop-filter: none;
    background: var(--surface-strong);
  }
}
/* ── Tabler icons (replaces emoji) ── */
.ti { flex-shrink: 0; display: block; }
.brand .logo .ti { width: 24px; height: 24px; }
.icon-btn .ti { width: 18px; height: 18px; }
.ops .icon-btn .ti { width: 16px; height: 16px; }
.search .ti { width: 18px; height: 18px; opacity: .45; }
.btn .ti { width: 17px; height: 17px; }
.row .name .ic .ti { width: 20px; height: 20px; }
.empty .big .ti { width: 48px; height: 48px; opacity: .8; }
#drop-zone .box .big .ti { width: 44px; height: 44px; }
.crumbs a .ti { display: inline-block; width: 16px; height: 16px; vertical-align: -3px; }
.list-head .sortable .ti { display: inline-block; width: 14px; height: 14px; vertical-align: -2px; }
  .modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.45); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
  .modal-card { background: var(--bg); color: var(--text); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 22px; width: min(420px, 92vw); box-shadow: 0 20px 60px rgba(0,0,0,0.35); }
  .modal-msg { font-size: 15px; line-height: 1.5; margin-bottom: 14px; white-space: pre-wrap; }
  .modal-input { width: 100%; box-sizing: border-box; padding: 10px 12px; border: 1px solid var(--border); border-radius: var(--radius-sm); background: transparent; color: var(--text); font-size: 14px; margin-bottom: 16px; outline: none; }
  .modal-input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
  .modal-actions { display: flex; justify-content: flex-end; gap: 10px; }
</style>
</head>
<body>
<div class="app">
  <div class="header">
    <div class="brand">
      <span class="logo" data-icon="moon"></span>
      <div>
        <div class="name">LunaShare</div>
        <div class="subtitle">局域网文件共享</div>
      </div>
    </div>
    <div class="header-actions">
      <button class="icon-btn" id="theme-btn" data-icon="sun" title="切换主题"></button>
      <button class="icon-btn" id="reload-btn" data-icon="refresh" title="刷新"></button>
    </div>
  </div>

  <div class="toolbar">
    <div class="search">
      <span data-icon="search"></span>
      <input id="search" type="search" placeholder="搜索文件…">
    </div>
    <button class="btn primary" id="upload-btn"><span data-icon="upload"></span><span class="lbl">上传</span></button>
    <button class="btn" id="mkdir-btn"><span data-icon="folder-plus"></span><span class="lbl">新建文件夹</span></button>
    <button class="btn" id="batch-dl"><span data-icon="download"></span><span class="lbl">下载</span></button>
    <button class="btn danger" id="batch-del"><span data-icon="trash"></span><span class="lbl">删除</span></button>
    <input type="file" id="file-input" multiple hidden>
  </div>

  <div class="bread-area">
    <span class="count" id="sel-count">已选 0 项</span>
    <nav class="crumbs" id="crumbs"></nav>
  </div>

  <div class="card">
    <div class="list-head">
      <input type="checkbox" id="check-all" class="ck" title="全选">
      <span class="sortable" data-key="name" id="sort-name">名称</span>
      <span class="sortable" data-key="size" id="sort-size">大小</span>
      <span class="sortable" data-key="mtime" id="sort-mtime">修改时间</span>
      <span style="text-align:right">操作</span>
    </div>
    <div id="list"></div>
  </div>

  <div class="footer">LunaShare · 局域网文件共享</div>
</div>

<div id="drop-zone"><div class="box"><div class="big" data-icon="cloud-upload"></div>松开以上传文件</div></div>
<div id="toast"></div>

<script>
'use strict';
/* Tabler icon SVGs (injected by the server) */
var ICONS = %ICONS%;
function injectIcons(root){ (root||document).querySelectorAll('[data-icon]').forEach(function(el){ var k=el.getAttribute('data-icon'); if(ICONS[k]) el.innerHTML=ICONS[k]; }); }
/* DATA injected by the server */
var DATA = %DATA%;
/* ── Session token（?_s=）兜底：移动端浏览器不会在 location.href 导航时发送
   fetch 设置的 SameSite=Lax cookie，故所有请求/链接统一附加 ?_s= ── */
var LUNA_SID = (new URLSearchParams(location.search).get('_s')) || '';
function withSid(u) {
  if (!LUNA_SID || typeof u !== 'string') return u;
  var s = '_s=' + encodeURIComponent(LUNA_SID);
  return (u.indexOf('?') >= 0) ? (u + '&' + s) : (u + '?' + s);
}
(function () {
  if (!LUNA_SID || !window.fetch) return;
  var _orig = window.fetch.bind(window);
  window.fetch = function (u, o) { return _orig(withSid(u), o); };
})();
/* 全局防御：强制剥离任何 URL 中的 user:pass@ 凭据（浏览器 fetch/XHR 拒绝 credentials-in-URL） */
(function(){
  function cleanUrl(u){
    if (typeof u !== 'string') return u;
    var i = u.indexOf('://');
    if (i < 0) return u;
    var rest = u.slice(i + 3);
    var at = rest.indexOf('@');
    if (at < 0) return u;
    return u.slice(0, i + 3) + rest.slice(at + 1);
  }
  if (window.fetch) {
    var _f = window.fetch.bind(window);
    window.fetch = function(url, opt){ return _f(cleanUrl(typeof url === 'string' ? url : (url && url.url) ? url.url : url), opt); };
  }
  if (window.XMLHttpRequest) {
    var _open = window.XMLHttpRequest.prototype.open;
    window.XMLHttpRequest.prototype.open = function(m, u, a, user, pass){ return _open.call(this, m, cleanUrl(u), a, user, pass); };
  }
})();

function stripCredentials(u) {
  if (u && u.indexOf('://') >= 0) {
    var m = u.match(/^[a-zA-Z][a-zA-Z0-9+.\-]*:\/\/[^/]*(\/.*)?$/);
    u = (m && m[1]) ? m[1] : '/';
  }
  return u || '/';
}
var curPath = stripCredentials(DATA.path || '/');
/* ── 主题一致的自定义弹窗（替代原生 confirm/prompt） ── */
var _modalEl = null;
function closeModal() { if (_modalEl && _modalEl.parentNode) { _modalEl.parentNode.removeChild(_modalEl); _modalEl = null; } }
function showModal(opts) {
  closeModal();
  var overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  var card = document.createElement('div');
  card.className = 'modal-card';
  var msg = document.createElement('div');
  msg.className = 'modal-msg';
  msg.textContent = opts.message || '';
  card.appendChild(msg);
  var input = null;
  if (opts.input !== false) {
    input = document.createElement('input');
    input.className = 'modal-input';
    input.type = 'text';
    input.value = opts.defaultValue || '';
    input.placeholder = opts.placeholder || '';
    card.appendChild(input);
  }
  var actions = document.createElement('div');
  actions.className = 'modal-actions';
  var cancelBtn = document.createElement('button');
  cancelBtn.className = 'btn';
  cancelBtn.textContent = opts.cancelText || '取消';
  var okBtn = document.createElement('button');
  okBtn.className = 'btn primary';
  okBtn.textContent = opts.okText || '确定';
  actions.appendChild(cancelBtn);
  actions.appendChild(okBtn);
  card.appendChild(actions);
  overlay.appendChild(card);
  document.body.appendChild(overlay);
  _modalEl = overlay;
  function finish(val) { closeModal(); if (opts.onClose) opts.onClose(val); }
  cancelBtn.onclick = function() { finish(null); };
  okBtn.onclick = function() { finish(input ? input.value : true); };
  overlay.onclick = function(e) { if (e.target === overlay) finish(null); };
  if (input) {
    input.focus();
    input.onkeydown = function(e){ if (e.key === 'Enter') finish(input.value); else if (e.key === 'Escape') finish(null); };
  } else {
    okBtn.focus();
    var onKey = function(e){ if (e.key === 'Escape') { finish(null); document.removeEventListener('keydown', onKey); } };
    document.addEventListener('keydown', onKey);
  }
}
function showConfirm(message, onOk) {
  showModal({ message: message, input: false, okText: '确定', cancelText: '取消',
    onClose: function(v){ if (v !== null && v !== false) onOk(); } });
}
function showPrompt(message, def, onOk) {
  showModal({ message: message, input: true, defaultValue: def || '', okText: '确定', cancelText: '取消',
    onClose: function(v){ if (v !== null && v !== false && v !== '') onOk(v); } });
}
var entries = DATA.entries || [];
var sortKey = localStorage.getItem('ls_sort') || 'name';
var sortDir = localStorage.getItem('ls_sortdir') || 'asc';
var searchText = '';

/* ── Helpers ── */
function esc(s) {
  var d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}
function fmtSize(n) {
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
  if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MB';
  return (n / 1073741824).toFixed(2) + ' GB';
}
function fmtTime(t) {
  if (!t) return '—';
  var d = new Date(t);
  var p = function(x){ return (x < 10 ? '0' : '') + x; };
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}
function iconFor(name, isDir) {
  if (isDir) return ICONS.folder;
  var ext = name.split('.').pop().toLowerCase();
  if (['png','jpg','jpeg','gif','webp','bmp','svg','ico','heic'].indexOf(ext) >= 0) return ICONS.photo;
  if (['mp4','mkv','avi','mov','wmv','flv','webm','m4v'].indexOf(ext) >= 0) return ICONS.movie;
  if (['mp3','wav','flac','ogg','m4a','aac','wma'].indexOf(ext) >= 0) return ICONS.music;
  if (['zip','rar','7z','tar','gz','bz2','xz'].indexOf(ext) >= 0) return ICONS['file-zip'];
  if (['pdf'].indexOf(ext) >= 0) return ICONS['file-type-pdf'];
  if (['doc','docx','odt','rtf'].indexOf(ext) >= 0) return ICONS['file-text'];
  if (['xls','xlsx','csv','ods'].indexOf(ext) >= 0) return ICONS['file-spreadsheet'];
  if (['ppt','pptx','odp'].indexOf(ext) >= 0) return ICONS.presentation;
  if (['txt','md','log','json','xml','ini','conf'].indexOf(ext) >= 0) return ICONS['file-text'];
  if (['apk','exe','msi','deb','rpm','dmg'].indexOf(ext) >= 0) return ICONS.binary;
  if (['kt','java','py','js','ts','c','cpp','h','go','rs','html','css','sh','sql'].indexOf(ext) >= 0) return ICONS['file-code'];
  return ICONS.file;
}
function absUrl(p) {
  p = stripCredentials(p);
  if (p.charAt(0) !== '/') p = '/' + p;
  return location.protocol + '//' + location.host + p;
}
function joinUrl(path, name) {
  var base = path === '/' ? '' : path;
  return absUrl(base + '/' + encodeURIComponent(name));
}
function toast(msg, isErr) {
  var t = document.getElementById('toast');
  t.textContent = msg;
  t.className = isErr ? 'err show' : 'show';
  clearTimeout(t._timer);
  t._timer = setTimeout(function(){ t.className = ''; }, 2600);
}
function buildUrl(path) {
  return absUrl(path);
}

/* ── Breadcrumbs ── */
function renderCrumbs() {
  var el = document.getElementById('crumbs');
  var html = '<a href="' + withSid('/') + '">' + ICONS.home + '</a>';
  var parts = curPath.split('/').filter(Boolean);
  var acc = '';
  for (var i = 0; i < parts.length; i++) {
    acc += '/' + encodeURIComponent(parts[i]);
    html += '<span class="sep">/</span>';
    if (i === parts.length - 1) {
      html += '<span class="current">' + esc(parts[i]) + '</span>';
    } else {
      html += '<a href="' + withSid(acc) + '">' + esc(parts[i]) + '</a>';
    }
  }
  el.innerHTML = html;
}

/* ── List rendering ── */
function sortedEntries() {
  var list = entries.slice();
  var key = sortKey;
  var dir = sortDir === 'desc' ? -1 : 1;
  list.sort(function(a, b) {
    if (a.dir !== b.dir) return a.dir ? -1 : 1;
    if (key === 'size') return (a.size - b.size) * dir;
    if (key === 'mtime') return (a.mtime - b.mtime) * dir;
    return a.name.localeCompare(b.name, 'zh-Hans-CN') * dir;
  });
  if (searchText) {
    var q = searchText.toLowerCase();
    list = list.filter(function(e){ return e.name.toLowerCase().indexOf(q) >= 0; });
  }
  return list;
}
function renderList() {
  var el = document.getElementById('list');
  var list = sortedEntries();
  if (!list.length) {
    el.innerHTML = '<div class="empty"><div class="big">' + ICONS['folder-open'] + '</div><p>' + (searchText ? '没有匹配的文件' : '此文件夹是空的') + '</p></div>';
    return;
  }
  el.textContent = '';
  for (var i = 0; i < list.length; i++) {
    var e = list[i];
    var url = joinUrl(curPath, e.name);
    var row = document.createElement('div');
    row.className = 'row';
    if (url in selected) row.classList.add('selected');

    // Selection checkbox
    var ck = document.createElement('input');
    ck.type = 'checkbox';
    ck.className = 'ck';
    ck.checked = (url in selected);
    ck.dataset.url = url;
    ck.title = '选择';
    ck.addEventListener('change', function(url, row, isDir) {
      return function() { toggleSelect(url, this.checked, row, isDir); };
    }(url, row, e.dir));
    row.appendChild(ck);

    var nameCell = document.createElement('div');
    nameCell.className = 'name';
    var ic = document.createElement('span');
    ic.className = 'ic';
    ic.innerHTML = e.dir ? ICONS.folder : iconFor(e.name, false);
    nameCell.appendChild(ic);
    var link = document.createElement('a');
    link.className = 'nm';
    // Trailing slash for folders — avoids the 301 hop (and non-ASCII Location bugs)
    link.href = withSid(e.dir ? url + '/' : url);
    link.textContent = e.name;
    if (!e.dir) link.setAttribute('download', '');
    nameCell.appendChild(link);
    row.appendChild(nameCell);

    var sizeCell = document.createElement('span');
    sizeCell.className = 'size';
    sizeCell.textContent = e.dir ? '—' : fmtSize(e.size);
    row.appendChild(sizeCell);

    var timeCell = document.createElement('span');
    timeCell.className = 'time';
    timeCell.textContent = fmtTime(e.mtime);
    row.appendChild(timeCell);

    var ops = document.createElement('span');
    ops.className = 'ops';
    if (e.dir) {
      var zipBtn = document.createElement('button');
      zipBtn.className = 'icon-btn';
      zipBtn.title = '打包下载';
      zipBtn.innerHTML = ICONS['file-zip'];
      zipBtn.dataset.act = 'zip';
      zipBtn.dataset.url = url + '?zip=1';
      ops.appendChild(zipBtn);
    }
    var dlBtn = document.createElement('button');
    dlBtn.className = 'icon-btn';
    dlBtn.title = '下载';
    dlBtn.innerHTML = ICONS.download;
    dlBtn.dataset.act = 'dl';
    dlBtn.dataset.url = url;
    ops.appendChild(dlBtn);
    var rnBtn = document.createElement('button');
    rnBtn.className = 'icon-btn';
    rnBtn.title = '重命名';
    rnBtn.innerHTML = ICONS.pencil;
    rnBtn.dataset.act = 'rename';
    rnBtn.dataset.url = url;
    rnBtn.dataset.name = e.name;
    ops.appendChild(rnBtn);
    var delBtn = document.createElement('button');
    delBtn.className = 'icon-btn';
    delBtn.title = '删除';
    delBtn.innerHTML = ICONS.trash;
    delBtn.dataset.act = 'del';
    delBtn.dataset.url = url;
    delBtn.dataset.name = e.name;
    ops.appendChild(delBtn);
    row.appendChild(ops);

    el.appendChild(row);
  }
}
function renderAll() {
  renderCrumbs();
  renderList();
  highlightSort();
  updateSelBar();
}

/* ── Batch selection ── */
var selected = {};  // url -> isDir
function toggleSelect(url, checked, rowEl, isDir) {
  if (checked) selected[url] = !!isDir; else delete selected[url];
  if (rowEl) rowEl.classList.toggle('selected', checked);
  updateSelBar();
}
function toggleSelectAll(checked) {
  selected = {};
  if (checked) {
    entries.forEach(function(e) {
      selected[joinUrl(curPath, e.name)] = e.dir;
    });
  }
  // 直接同步现有勾选框，避免整列表重渲染导致状态不同步
  var boxes = document.querySelectorAll('#list .ck');
  for (var i = 0; i < boxes.length; i++) {
    var ck = boxes[i];
    var on = (ck.dataset.url in selected);
    ck.checked = on;
    if (ck.parentElement) ck.parentElement.classList.toggle('selected', on);
  }
  updateSelBar();
}
function updateSelBar() {
  var n = Object.keys(selected).length;
  document.getElementById('sel-count').textContent = '已选 ' + n + ' 项';
  // Sync the header select-all checkbox (indeterminate for partial selection)
  var ca = document.getElementById('check-all');
  var total = entries.length;
  ca.checked = n > 0 && n === total;
  ca.indeterminate = n > 0 && n < total;
}
function batchDownload() {
  if (Object.keys(selected).length === 0) { toast('请先选择要下载的文件', true); return; }
  var urls = Object.keys(selected).filter(function(url) { return !selected[url]; }); // files only
  if (!urls.length) { toast('没有可下载的文件', true); return; }
  urls.forEach(function(url) {
    var a = document.createElement('a');
    a.href = url;
    a.download = '';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  });
  toast('已开始下载 ' + urls.length + ' 个文件');
}
function batchDelete() {
  if (Object.keys(selected).length === 0) { toast('请先选择要删除的项目', true); return; }
  var urls = Object.keys(selected);
  showConfirm('确定删除选中的 ' + urls.length + ' 个项目吗？此操作不可恢复！', function() {
  var done = 0, failed = 0;
  var finish = function() {
    selected = {};
    updateSelBar();
    toast('删除完成：成功 ' + done + '，失败 ' + failed, failed > 0);
    refresh();
  };
  urls.forEach(function(url) {
    fetch(url, { method: 'DELETE' })
      .then(function(r) {
        if (r.status === 204 || r.status === 200) done++; else failed++;
        if (done + failed === urls.length) finish();
      })
      .catch(function() {
        failed++;
        if (done + failed === urls.length) finish();
      });
  });
  });
}

/* ── Event delegation for row actions (bound in init) ── */
function bindRowActions() {
  document.getElementById('list').addEventListener('click', function(ev) {
    var btn = ev.target.closest('[data-act]');
    if (!btn) return;
    var act = btn.dataset.act;
    var url = btn.dataset.url;
    var name = btn.dataset.name;
  if (act === 'dl') { location.href = withSid(url); }
  else if (act === 'zip') { location.href = withSid(url); }
  else if (act === 'rename') renameAction(url, name);
  else if (act === 'del') deleteAction(url, name);
  });
}

/* ── Sort ── */
function highlightSort() {
  var keys = ['name', 'size', 'mtime'];
  for (var i = 0; i < keys.length; i++) {
    var el = document.getElementById('sort-' + keys[i]);
    if (!el) continue;
    el.classList.toggle('active', sortKey === keys[i]);
    el.innerHTML = (keys[i] === 'name' ? '名称' : keys[i] === 'size' ? '大小' : '修改时间')
      + (sortKey === keys[i] ? (sortDir === 'asc' ? ICONS['chevron-up'] : ICONS['chevron-down']) : '');
  }
}
function setSort(key) {
  if (sortKey === key) sortDir = sortDir === 'asc' ? 'desc' : 'asc';
  else { sortKey = key; sortDir = 'asc'; }
  localStorage.setItem('ls_sort', sortKey);
  localStorage.setItem('ls_sortdir', sortDir);
  renderList();
  highlightSort();
}
function bindSort() {
  var heads = document.querySelectorAll('.list-head .sortable');
  for (var i = 0; i < heads.length; i++) {
    heads[i].addEventListener('click', function() {
      setSort(this.getAttribute('data-key'));
    });
  }
}

/* ── Search (bound in init) ── */
function bindSearch() {
  document.getElementById('search').addEventListener('input', function() {
    searchText = this.value.trim();
    renderList();
  });
}

/* ── Theme ── */
function applyTheme(t) {
  document.documentElement.setAttribute('data-theme', t);
  document.getElementById('theme-btn').innerHTML = ICONS[t === 'dark' ? 'sun' : 'moon'];
}
var savedTheme = localStorage.getItem('ls_theme');
var sysDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
function bindTheme() {
  applyTheme(savedTheme || (sysDark ? 'dark' : 'light'));
  document.getElementById('theme-btn').onclick = function() {
    var cur = document.documentElement.getAttribute('data-theme');
    var next = cur === 'dark' ? 'light' : 'dark';
    localStorage.setItem('ls_theme', next);
    applyTheme(next);
  };
}

/* ── Refresh via JSON API ── */
function refresh(cb) {
  fetch(buildUrl(curPath), { headers: { 'Accept': 'application/json' }, cache: 'no-store' })
    .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
    .then(function(d) {
      entries = d.entries || [];
      renderList();
      if (cb) cb();
    })
    .catch(function(err) { toast('刷新失败: ' + err.message, true); });
}
function bindRefresh() {
  document.getElementById('reload-btn').onclick = function() { refresh(); };
}

/* ── Upload (bound in init) ── */
function bindUpload() {
  document.getElementById('upload-btn').onclick = function() { document.getElementById('file-input').click(); };
  document.getElementById('file-input').addEventListener('change', function() {
    uploadFiles(this.files);
    this.value = '';
  });
}
function uploadFiles(files) {
  if (!files || !files.length) return;
  var total = files.length, done = 0;
  Array.prototype.forEach.call(files, function(f) {
    var url = joinUrl(curPath, f.name);
    var xhr = new XMLHttpRequest();
    xhr.open('PUT', withSid(url), true);
    xhr.upload.onprogress = function(ev) {
      if (ev.lengthComputable) {
        var pct = Math.round(ev.loaded / ev.total * 100);
        toast('上传中 ' + f.name + ' ' + pct + '%');
      }
    };
    xhr.onload = function() {
      done++;
      if (xhr.status >= 200 && xhr.status < 300) {
        if (done === total) { toast('上传完成'); refresh(); }
      } else {
        toast(f.name + ' 上传失败 (' + xhr.status + ')', true);
      }
    };
    xhr.onerror = function() {
      done++;
      toast(f.name + ' 上传失败', true);
    };
    xhr.send(f);
  });
}
function bindDragDrop() {
  window.addEventListener('dragover', function(e) { e.preventDefault(); document.getElementById('drop-zone').classList.add('active'); });
  window.addEventListener('dragleave', function(e) { if (e.target === document.body) document.getElementById('drop-zone').classList.remove('active'); });
  window.addEventListener('drop', function(e) {
    e.preventDefault();
    document.getElementById('drop-zone').classList.remove('active');
    uploadFiles(e.dataTransfer.files);
  });
}

/* ── New folder (MKCOL, bound in init) ── */
function bindMkdir() {
  document.getElementById('mkdir-btn').onclick = function() {
    showPrompt('输入新文件夹名称：', '', function(name) {
    if (!name) return;
    fetch(joinUrl(curPath, name), { method: 'MKCOL' })
      .then(function(r) {
        if (r.status === 201 || r.status === 200) { toast('文件夹已创建'); refresh(); }
        else if (r.status === 405) { toast('文件夹已存在', true); }
        else throw new Error('HTTP ' + r.status);
      })
      .catch(function(err) { toast('创建失败: ' + err.message, true); });
    });
  };
}

/* ── Rename (MOVE) ── */
function renameAction(url, oldName) {
  showPrompt('重命名为：', oldName, function(name) {
  if (!name || name === oldName) return;
  var parent = url.substring(0, url.lastIndexOf('/'));
  var dest = parent + '/' + encodeURIComponent(name);
  fetch(url, { method: 'MOVE', headers: { 'Destination': dest } })
    .then(function(r) {
      if (r.status === 201 || r.status === 204) { toast('已重命名'); refresh(); }
      else throw new Error('HTTP ' + r.status);
    })
    .catch(function(err) { toast('重命名失败: ' + err.message, true); });
  });
}

/* ── Delete (DELETE) ── */
function deleteAction(url, name) {
  showConfirm('确定删除「' + name + '」吗？此操作不可恢复！', function() {
  fetch(url, { method: 'DELETE' })
    .then(function(r) {
      if (r.status === 204 || r.status === 200) { toast('已删除'); refresh(); }
      else throw new Error('HTTP ' + r.status);
    })
    .catch(function(err) { toast('删除失败: ' + err.message, true); });
  });
}

/* ── Init (runs only after the DOM is fully parsed) ── */
function init() {
  injectIcons();
  bindRowActions();
  bindSearch();
  bindSort();
  bindTheme();
  bindRefresh();
  bindUpload();
  bindDragDrop();
  bindMkdir();
  document.getElementById('batch-dl').onclick = batchDownload;
  document.getElementById('batch-del').onclick = batchDelete;
  document.getElementById('check-all').onclick = function() {
    toggleSelectAll(this.checked);
  };
  renderAll();
}
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}

</script>
</body>
</html>
""".trimIndent()
        .replace("%ICONS%", TablerIcons.iconsScript())
        .replace("%FAVICON%", TablerIcons.faviconDataUri())

    /**
     * 主题登录页（替代浏览器原生 Basic Auth 弹窗）。
     * 表单通过 absUrl('/_login') 构造绝对、不含凭据的 URL 提交，根治手机端 credentials-in-URL 报错。
     */
    fun loginPageTemplate(): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="color-scheme" content="light dark">
<title>LunaShare · 登录</title>
<style>
:root{ --bg:#f5f5f7; --surface:rgba(255,255,255,0.72); --surface-strong:rgba(255,255,255,0.92); --text:#1d1d1f; --text-secondary:#86868b; --border:rgba(0,0,0,0.08); --accent:#0071e3; --accent-hover:#0077ed; --accent-soft:rgba(0,113,227,0.1); --danger:#ff3b30; --shadow-md:0 4px 16px rgba(0,0,0,0.08),0 1px 3px rgba(0,0,0,0.04); --radius-md:14px; --blur:blur(20px) saturate(180%); }
[data-theme="dark"]{ --bg:#000; --surface:rgba(28,28,30,0.72); --surface-strong:rgba(28,28,30,0.94); --text:#f5f5f7; --text-secondary:#98989d; --border:rgba(255,255,255,0.12); --accent:#0a84ff; --accent-hover:#409cff; --accent-soft:rgba(10,132,255,0.18); --danger:#ff453a; --shadow-md:0 4px 16px rgba(0,0,0,0.35); }
*{box-sizing:border-box;margin:0;padding:0;}
body{font-family:-apple-system,BlinkMacSystemFont,"SF Pro Text","PingFang SC","Microsoft YaHei",sans-serif;background:var(--bg);color:var(--text);min-height:100vh;display:flex;align-items:center;justify-content:center;-webkit-font-smoothing:antialiased;transition:background-color .35s ease,color .35s ease;}
.card{width:min(360px,90vw);background:var(--surface);backdrop-filter:var(--blur);-webkit-backdrop-filter:var(--blur);border:1px solid var(--border);border-radius:var(--radius-md);box-shadow:var(--shadow-md);padding:32px 28px;}
.brand{display:flex;align-items:center;gap:12px;margin-bottom:24px;}
.logo{width:40px;height:40px;border-radius:11px;background:linear-gradient(135deg,#0a84ff,#0071e3);display:flex;align-items:center;justify-content:center;color:#fff;font-weight:700;font-size:20px;}
.brand .name{font-size:20px;font-weight:600;}
.brand .sub{font-size:13px;color:var(--text-secondary);}
h1{font-size:18px;margin-bottom:20px;font-weight:600;}
label{display:block;font-size:13px;color:var(--text-secondary);margin:14px 0 6px;}
input{width:100%;padding:11px 13px;font-size:15px;border:1px solid var(--border);border-radius:10px;background:var(--surface-strong);color:var(--text);outline:none;transition:border-color .2s;}
input:focus{border-color:var(--accent);}
.btn{width:100%;margin-top:22px;padding:12px;border:none;border-radius:10px;background:var(--accent);color:#fff;font-size:15px;font-weight:600;cursor:pointer;transition:background-color .2s;}
.btn:hover{background:var(--accent-hover);}
.btn:active{transform:translateY(1px);}
#err{display:none;margin-top:14px;padding:10px 12px;border-radius:10px;background:var(--danger);color:#fff;font-size:13px;text-align:center;}
.foot{margin-top:18px;text-align:center;font-size:12px;color:var(--text-secondary);}
</style>
</head>
<body>
<div class="card">
  <div class="brand"><div class="logo">L</div><div><div class="name">LunaShare</div><div class="sub">局域网文件共享</div></div></div>
  <h1>请输入访问密码</h1>
  <label for="user">用户名</label>
  <input id="user" type="text" autocomplete="username" placeholder="用户名">
  <label for="pass">密码</label>
  <input id="pass" type="password" autocomplete="current-password" placeholder="密码" onkeydown="if(event.key==='Enter')doLogin()">
  <button class="btn" onclick="doLogin()">登录</button>
  <div id="err"></div>
  <div class="foot">登录后可浏览与下载共享文件</div>
</div>
<script>
(function(){
  try{ var t=localStorage.getItem('theme'); if(t){document.documentElement.dataset.theme=t;} else if(window.matchMedia&&matchMedia('(prefers-color-scheme: dark)').matches){document.documentElement.dataset.theme='dark';} }catch(e){}
})();
function absUrl(p){ if(p.charAt(0)!=='/'){p='/'+p;} return location.protocol+'//'+location.host+p; }
function doLogin(){
  var u=document.getElementById('user').value;
  var p=document.getElementById('pass').value;
  var err=document.getElementById('err');
  err.style.display='none';
  fetch(absUrl('/_login'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({user:u,pass:p})})
    .then(function(r){return r.json().then(function(d){return {ok:r.ok,d:d};});})
    .then(function(res){ if(res.ok&&res.d.ok){location.href='/?_s='+encodeURIComponent(res.d.sid);} else {err.textContent=(res.d&&res.d.error)?res.d.error:'登录失败';err.style.display='block';} })
    .catch(function(){err.textContent='网络错误，请重试';err.style.display='block';});
}
</script>
</body>
</html>
""".trimIndent()
}
