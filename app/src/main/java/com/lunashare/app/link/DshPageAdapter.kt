package com.lunashare.app.link

import org.json.JSONObject
import kotlin.random.Random

/**
 * DSH 移动端页面适配层（LunaShare Link ↔ dsh-bridge 的移动端皮肤）。
 *
 * 背景：dsh-bridge 插件会给 DSH 控制台注入一整套移动端 UI（顶栏 `.dsh-mobile-app-header`
 * ＋抽屉＋面板管理）。它跑在 Link Tab 的常驻 WebView 里，于是有三件"只有宿主 App 才知道"
 * 的信息需要和页面互通：
 *
 *  1. **真实状态栏高度**：WebView 里 `env(safe-area-inset-top)` 在 Android WebView 上
 *     经常是 0（除非页面带 `viewport-fit=cover` 且内核上报了刘海），插件顶栏会钻到状态栏
 *     底下、按钮点不到。本层把 App 实测的高度回填给页面。
 *     ⚠️ **单位坑**：App 量到的是 Android 物理 px，页面的 CSS 用的是 CSS px，
 *     两者差一个 devicePixelRatio。曾直接把物理 px 写进 `--dsh-mobile-safe-top`，
 *     高密度屏（dpr≈3.5）上被放大 3 倍多，顶栏直接占了屏幕 1/3。
 *     现在统一由页面侧换算，并夹到 0..60 CSS px 的硬上限（状态栏不可能更高）。
 *  2. **页面主题明暗 + 真实底色**：状态栏要跟着页面走（深色页面配浅色图标，
 *     并且状态栏背景要染成页面底色）。页面侧读 `--dsw-alias-*` 的实际色值算出来回报。
 *  3. **顶栏宿主按钮**：把「回主页 / 缩放复位 / 连接」放进左插槽、「会话侧边栏」放进右插槽
 *     （紧邻「+」），点「会话侧边栏」时页面自己开 DSH 会话抽屉——面板在右侧滑出，与按钮同侧。
 *     同时给 `<html>` 打上 `luna-host` 标志，让插件隐藏它自己那个功能重复的左侧「双横线」
 *     菜单按钮（两个按钮都开同一个抽屉，只保留宿主这一个入口）。
 *
 * 通信方式：**控制台消息通道**（`console.log` → `WebChromeClient.onConsoleMessage`），
 * 而不是 `addJavascriptInterface`——注入脚本会跑到任意网页里，暴露 JS 接口等于给所有
 * 页面开了原生入口。控制台消息零攻击面，且不依赖接口注册时机。
 *
 * 防伪：每个 WebView 生成一次性 `nonce`，写在脚本闭包内（外部脚本读不到）；回报必须带
 * 同一 nonce 才被采纳，避免第三方页面伪造「我是 DSH 页面」或直接触发原生动作。
 */
object DshPageAdapter {

    /** 控制台消息前缀（与页面脚本约定的通道标识）；WebView 侧据此快速预筛 */
    const val MSG_PREFIX = "__LUNA_LINK__"

    /** 页面能力：已识别为 DSH 控制台（且 dsh-bridge 移动端皮肤已生效） */
    const val CAP_DSH = "dsh"

    /** 宿主注入按钮的动作标识（与注入脚本内 BTNS 的第一列一一对应） */
    const val ACTION_HOME = "home"
    const val ACTION_ZOOM = "zoom"
    const val ACTION_LUNA_SIDEBAR = "luna_sidebar"

    /** 页面回传的一条消息（字段都可选，按实际发生的事件填充） */
    data class PageMessage(
        /** 页面能力上报：`dsh` / `none` */
        val capability: String? = null,
        /** 页面当前是否深色主题（null = 本次未上报） */
        val dark: Boolean? = null,
        /** 页面真实底色，"r,g,b"（null = 本次未上报）；用于把状态栏背景染成页面色 */
        val bg: String? = null,
        /** 宿主注入按钮被点击：home / zoom / luna_sidebar */
        val action: String? = null,
    )

    /** 生成一次性口令（十六进制，可安全嵌入 JS 字符串字面量） */
    fun newNonce(): String {
        val bytes = ByteArray(16)
        Random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * 构造注入脚本。
     *
     * @param nonce [newNonce] 生成的一次性口令
     * @param statusBarTopPx 需要为页面补偿的状态栏高度（**Android 物理 px**）；
     *        0 表示不需要补偿（窗口本身不是 edge-to-edge，WebView 已在状态栏下方）。
     *        页面侧会自行 ÷ devicePixelRatio 换成 CSS px 并夹上限。
     */
    fun buildInjectScript(nonce: String, statusBarTopPx: Int, hostDark: Boolean = false): String =
        INJECT_JS
            .replace("__NONCE__", nonce)
            .replace("__SB_TOP__", statusBarTopPx.coerceAtLeast(0).toString())
            .replace("__HOST_DARK__", if (hostDark) "true" else "false")

    /**
     * 只推送「宿主当前是亮还是暗」（App 切主题时用）。
     *
     * 页面侧据此调用自己的深色开关（DSH 是靠 `<body data-ds-dark-theme>` 切色板的），
     * 所以**不需要重载页面**、也不会丢会话状态。
     */
    fun buildHostThemeScript(hostDark: Boolean): String =
        "(function(){try{window.__lunaDshTheme&&window.__lunaDshTheme(" +
            (if (hostDark) "true" else "false") + ");}catch(e){}})();"

    /**
     * 只更新安全区（窗口 inset 变化、但脚本已注入过时用）。
     * 页面重载会重置内联变量，故每次 onPageFinished 仍要重新注入整段脚本。
     */
    fun buildSafeAreaScript(statusBarTopPx: Int): String =
        "(function(){try{window.__lunaDshSafe&&window.__lunaDshSafe(${statusBarTopPx.coerceAtLeast(0)});}catch(e){}})();"

    /**
     * 让页面重置去重状态并重报一次（切回该页 / 宿主重建后调用）。
     *
     * 页面侧的上报是按"变化"去重的：页面在后台停留一段时间后再切回来，主题没变就不会重发，
     * 宿主此时拿到的底色/明暗可能已过期 → 状态栏会闪回 App 主题色。主动 ping 一次即可。
     */
    fun buildPingScript(): String =
        "(function(){try{window.__lunaDshPing&&window.__lunaDshPing();}catch(e){}})();"

    /**
     * 解析页面回传；非本通道或口令不符 → null（调用方直接忽略）。
     */
    fun parse(text: String, nonce: String): PageMessage? {
        if (!text.startsWith(MSG_PREFIX)) return null
        return try {
            val obj = JSONObject(text.substring(MSG_PREFIX.length))
            if (obj.optString("n") != nonce) return null
            PageMessage(
                capability = obj.optString("cap", "").takeIf { it.isNotEmpty() },
                dark = if (obj.has("dark")) obj.optBoolean("dark") else null,
                bg = obj.optString("bg", "").takeIf { it.isNotEmpty() },
                action = obj.optString("action", "").takeIf { it.isNotEmpty() },
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 把页面回报的 "r,g,b" 解析成 ARGB int；失败返回 null。
     */
    fun parseBg(bg: String?): Int? {
        if (bg.isNullOrBlank()) return null
        return try {
            val p = bg.split(',')
            if (p.size < 3) return null
            val r = p[0].trim().toInt().coerceIn(0, 255)
            val g = p[1].trim().toInt().coerceIn(0, 255)
            val b = p[2].trim().toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 注入脚本：辨识页面 → 补偿安全区 → 注入顶栏按钮 → 上报主题与底色。
     *
     * 性能：把"外观变化"与"结构变化"拆成两个观察者，聊天流式输出时不会因为文本节点高频
     * 变动而反复跑 getComputedStyle；并用 300ms 去抖合并。
     */
    private val INJECT_JS = """
(function(){
  try{
    if (window.__lunaDshAdapt) return;
    window.__lunaDshAdapt = true;

    var NONCE = '__NONCE__';
    var HOST_TOP_PX = __SB_TOP__;   // 宿主实测的 Android 物理 px
    var HOST_DARK = __HOST_DARK__;  // 宿主(App)当前是否深色 —— 见 applyHostTheme()
    var PREFIX = '__LUNA_LINK__';
    var MOBILE_MAX_WIDTH = 767;
    var SAFE_TOP_MAX_CSS = 60;      // CSS px 硬上限：状态栏不可能比这更高

    function send(o){
      try{ o.n = NONCE; console.log(PREFIX + JSON.stringify(o)); }catch(e){}
    }

    // ── 1. 页面辨识：是否 DSH 控制台（且 dsh-bridge 移动端皮肤已生效）──
    function isDsh(){
      try{
        if(document.getElementById('dsh-bridge-mobile-styles')) return true;
        if(document.querySelector('style[data-plugin^="@wenbin_wb/dsh-bridge"]')) return true;
        if(document.querySelector('.dsh-mobile-app-header')) return true;
        var b = document.body;
        if(b && (b.hasAttribute('data-ds-dark-theme') || b.classList.contains('dsh-drawer-open'))) return true;
        if(document.querySelector('div[class*="_sidebarCol"]')
           && document.querySelector('button[aria-label="新建会话"]')) return true;
      }catch(e){}
      return false;
    }

    // ── 2. 主题：读页面真实底色（dsh 把色板挂在 --dsw-alias-* 上）──
    // ⚠️ 必须同时支持 **十六进制** 与 rgb()/rgba()：DSH 的 CSS 变量值就是 `#ffffff`
    // 这种十六进制。旧实现只 regex 了 `rgb(...)`，于是 #ffffff 解析失败 → 底色永远报空
    // （实测宿主收到的消息里 bg=null），状态栏就没法染成页面色。
    function parseColor(c){
      if(!c) return null;
      var s = String(c).trim();
      if(!s || s === 'transparent') return null;
      var m = s.match(/^#([0-9a-fA-F]{3,8})$/);
      if(m){
        var h = m[1];
        var r, g, b, a = 1;
        if(h.length === 3 || h.length === 4){
          r = parseInt(h[0] + h[0], 16); g = parseInt(h[1] + h[1], 16); b = parseInt(h[2] + h[2], 16);
          if(h.length === 4) a = parseInt(h[3] + h[3], 16) / 255;
        } else if(h.length === 6 || h.length === 8){
          r = parseInt(h.slice(0, 2), 16); g = parseInt(h.slice(2, 4), 16); b = parseInt(h.slice(4, 6), 16);
          if(h.length === 8) a = parseInt(h.slice(6, 8), 16) / 255;
        } else {
          return null;
        }
        if(isNaN(r) || isNaN(g) || isNaN(b) || a === 0) return null;
        return [r, g, b];
      }
      var m2 = s.match(/rgba?\(([^)]+)\)/);
      if(!m2) return null;
      var p = m2[1].split(',').map(function(x){ return parseFloat(x); });
      if(p.length < 3 || isNaN(p[0]) || isNaN(p[1]) || isNaN(p[2])) return null;
      if(p.length > 3 && p[3] === 0) return null;   // 全透明 → 不算数，继续找下层
      return [p[0], p[1], p[2]];
    }
    function pageBgRgb(){
      try{
        var cs = getComputedStyle(document.body);
        var vars = ['--dsw-alias-bg-base', '--dsw-alias-bg-layer-1', '--dsw-alias-bg-layer-2'];
        for(var i = 0; i < vars.length; i++){
          var rgb = parseColor(cs.getPropertyValue(vars[i]));
          if(rgb) return rgb;
        }
        // 变量拿不到 → 直接看「页面顶部实际渲染出来的颜色」：从顶边中点那个元素
        // 逐级向上找第一个不透明背景。这比猜某个变量名更贴近用户看到的顶栏底色。
        var el = document.elementFromPoint(Math.round(window.innerWidth / 2), 2) || document.body;
        for(var hop = 0; el && hop < 12; hop++){
          var bg = parseColor(getComputedStyle(el).backgroundColor);
          if(bg) return bg;
          el = el.parentElement;
        }
      }catch(e){}
      try{
        if(document.body && document.body.hasAttribute('data-ds-dark-theme')) return [27, 27, 28];
      }catch(e){}
      return null;
    }
    function pageIsDark(){
      var rgb = pageBgRgb();
      if(rgb) return (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / 255 < 0.5;
      try{ return !!(document.body && document.body.hasAttribute('data-ds-dark-theme')); }catch(e){}
      return false;
    }

    // ── 3. 安全区补偿 ──
    // 先问页面自己：env(safe-area-inset-top) 有效就直接不干预（插件的 :root 已经用了它）。
    // 只有拿不到时才回落到宿主给的值 —— 宿主给的是 Android 物理 px，必须 ÷ devicePixelRatio
    // 才是 CSS px（差这一步会让高密度屏的顶栏被放大 3 倍以上，直接把屏幕顶掉 1/3）。
    function measureEnvTop(){
      try{
        var probe = document.createElement('div');
        probe.style.cssText = 'position:fixed;top:0;left:0;width:0;height:env(safe-area-inset-top,0px);'
          + 'visibility:hidden;pointer-events:none;';
        (document.body || document.documentElement).appendChild(probe);
        var h = probe.getBoundingClientRect().height;
        if(probe.parentNode) probe.parentNode.removeChild(probe);
        return (h > 0 && isFinite(h)) ? Math.round(h) : 0;
      }catch(e){ return 0; }
    }
    // env() 只探一次并缓存：探针要插进 DOM，而 report() 每 3s 跑一次，
    // 每次都插节点会把 body 的 MutationObserver 打成 300ms 的循环。旋转/缩放时再失效重探。
    var envTopCached = -1;
    function envTop(){
      if(envTopCached >= 0) return envTopCached;
      envTopCached = measureEnvTop();
      return envTopCached;
    }
    function hostTopCss(hostPx){
      var dpr = window.devicePixelRatio || 1;
      var v = hostPx / dpr;
      if(!isFinite(v) || v < 0) v = 0;
      return Math.min(Math.round(v), SAFE_TOP_MAX_CSS);
    }
    function applySafeArea(hostPx){
      try{
        if(envTop() > 0) return;   // 页面自己能拿到，交给插件的 env() 即可
        document.documentElement.style.setProperty('--dsh-mobile-safe-top', hostTopCss(hostPx) + 'px');
      }catch(e){}
    }

    // ── 4. 顶栏宿主按钮：左＝回主页/缩放复位/连接，右＝会话侧边栏（紧邻 +）──
    var BTN_CSS_ID = 'luna-link-dsh-style';
    function ensureStyle(){
      try{
        if(document.getElementById(BTN_CSS_ID)) return;
        var st = document.createElement('style');
        st.id = BTN_CSS_ID;
        st.textContent =
          '.luna-hdr-actions{display:none;align-items:center;gap:2px;flex:0 0 auto;pointer-events:auto !important}' +
          '.luna-hdr-actions:not(:empty){display:inline-flex}' +
          '.luna-hdr-actions>button{width:36px;height:36px;border:0;border-radius:50%;background:transparent;' +
          'color:var(--dsw-alias-label-primary,#111827);display:inline-flex;align-items:center;justify-content:center;' +
          'padding:0;cursor:pointer;transition:opacity .15s}' +
          '.luna-hdr-actions>button:active{opacity:.6}';
        (document.head || document.documentElement).appendChild(st);
      }catch(e){}
    }

    // 左组：回主页 / 缩放复位 / 连接（LunaShare 连接抽屉）
    var BTNS_LEFT = [
      ['home',         '回主页',   '<path d="M3 10.6 12 3.2l9 7.4"></path><path d="M5.6 9.4V20.4h12.8V9.4"></path>'],
      ['zoom',         '缩放复位', '<circle cx="11" cy="11" r="7"></circle><path d="M20 20l-3.7-3.7"></path><path d="M8 11h6"></path>'],
      ['luna_sidebar', '连接列表', '<rect x="3" y="5" width="18" height="14" rx="2.4"></rect><path d="M8 9h8"></path><path d="M8 13h5"></path>']
    ];
    // 右组：会话侧边栏（DSH 原生会话列表）。按钮在右、面板也从右滑出，方向一致。
    var BTNS_RIGHT = [
      ['dsh_sidebar',  '会话侧边栏', '<rect x="3" y="4" width="18" height="16" rx="2.4"></rect><path d="M15 4v16"></path><path d="M6.5 9h5"></path><path d="M6.5 13h5"></path>']
    ];

    function makeBox(btns){
      var box = document.createElement('div');
      box.className = 'luna-hdr-actions';
      for(var i = 0; i < btns.length; i++){
        (function(k, title, path){
          var b = document.createElement('button');
          b.type = 'button';
          b.title = title;
          b.setAttribute('aria-label', title);
          b.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
            + 'stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">' + path + '</svg>';
          b.addEventListener('click', function(ev){
            ev.preventDefault();
            ev.stopPropagation();
            if(k === 'dsh_sidebar'){
              // DSH 会话侧边栏：纯页面行为，直接切 body 上的抽屉类即可，
              // 不必绕一圈原生再回来（插件自己的菜单按钮也是这么切的）。
              try{
                var isOpen = document.body.classList.toggle('dsh-drawer-open');
                if(isOpen){
                  var expand = document.querySelector('button[aria-label*="打开侧边栏"], button[title*="打开侧边栏"]');
                  if(expand) expand.click();
                }
              }catch(e){}
              return;
            }
            send({ action: k });
          }, true);
          box.appendChild(b);
        })(btns[i][0], btns[i][1], btns[i][2]);
      }
      return box;
    }

    function buildActions(){
      var header = document.querySelector('.dsh-mobile-app-header');
      if(!header) return;
      ensureStyle();

      // 优先用插件预留的两个插槽；老版本插件没有插槽 → 退回自己插在等效位置
      var slotL = header.querySelector('.dsh-mobile-header-extras-left');
      var slotR = header.querySelector('.dsh-mobile-header-extras');
      var menuBtn = header.querySelector('.dsh-header-menu-btn');
      var newBtn = header.querySelector('.dsh-header-new-btn');

      var boxL = header.querySelector('.luna-hdr-actions-left');
      if(slotL || menuBtn){
        if(!boxL){
          boxL = makeBox(BTNS_LEFT);
          boxL.classList.add('luna-hdr-actions-left');
        }
        if(slotL){
          if(boxL.parentNode !== slotL) slotL.appendChild(boxL);
        } else if(menuBtn) {
          if(boxL.previousSibling !== menuBtn) header.insertBefore(boxL, menuBtn.nextSibling);
        }
      }

      var boxR = header.querySelector('.luna-hdr-actions-right');
      if(!boxR){
        boxR = makeBox(BTNS_RIGHT);
        boxR.classList.add('luna-hdr-actions-right');
      }
      if(slotR){
        if(boxR.parentNode !== slotR) slotR.appendChild(boxR);
      } else if(newBtn && newBtn.parentNode === header){
        if(boxR.nextSibling !== newBtn) header.insertBefore(boxR, newBtn);
      } else if(boxR.parentNode !== header){
        header.appendChild(boxR);
      }
    }
    function removeActionsIfWide(){
      try{
        if(window.innerWidth > MOBILE_MAX_WIDTH){
          var boxes = document.querySelectorAll('.luna-hdr-actions');
          for(var i = 0; i < boxes.length; i++){
            if(boxes[i].parentNode) boxes[i].parentNode.removeChild(boxes[i]);
          }
        }
      }catch(e){}
    }

    // ── 5. 上报：拆成「结构」与「外观」两条独立链路 ──
    // ⚠️ 这里曾经是"两个观察者都调同一个 report()"，等于没拆：body 的 childList 在聊天流式
    //    输出时会高频触发，report() 里的 getComputedStyle / elementFromPoint 会强制同步样式
    //    计算，跟高频 DOM 变更撞在一起就是肉眼可见的掉帧。现在结构链路只做 DOM 动作，
    //    颜色读取只留在外观链路。
    var lastCap = null, lastDark = null, lastBg = '', hostTop = HOST_TOP_PX;
    var timerS = null, timerA = null;

    // 结构链路（便宜）：辨识页面 / 补顶栏按钮。**不读颜色**。
    function reportStructure(){
      removeActionsIfWide();
      var dsh = isDsh();
      var cap = dsh ? 'dsh' : 'none';
      var capChanged = (cap !== lastCap);
      if(capChanged){ lastCap = cap; send({ cap: cap }); }
      if(!dsh) return;
      // 接管标志：宿主（LunaShare）已经能往顶栏塞自己的按钮了。插件凭这个 class
      // 隐藏它自己那个功能重复的左侧「双横线」菜单按钮（同样开 dsh-drawer-open），
      // 避免顶栏出现两个入口。手机浏览器直开（无宿主）时不会有这个 class。
      try{ document.documentElement.classList.add('luna-host'); }catch(e){}
      buildActions();
      // 页面刚被辨识为 DSH（顶栏/插件样式是异步注入的）→ 立刻把宿主的明暗贴上去，
      // 否则会先白一下再变黑。
      applyHostTheme();
      // 刚辨识出是 DSH 页面 → 立刻补一次外观上报，别让状态栏等 3s 兜底
      if(capChanged) reportAppearance();
    }

    // 外观链路（较贵，含颜色读取）：只在主题类信号 / 定时兜底 / 宿主 ping 时跑
    function reportAppearance(){
      if(!isDsh()) return;
      applySafeArea(hostTop);
      var rgb = pageBgRgb();
      var bg = rgb ? (Math.round(rgb[0]) + ',' + Math.round(rgb[1]) + ',' + Math.round(rgb[2])) : '';
      if(bg !== lastBg){ lastBg = bg; if(bg) send({ bg: bg }); }
      var dk = pageIsDark();
      if(dk !== lastDark){ lastDark = dk; send({ dark: dk }); }
    }

    function scheduleStructure(){
      if(timerS) return;
      timerS = setTimeout(function(){ timerS = null; reportStructure(); }, 300);
    }
    function scheduleAppearance(){
      if(timerA) return;
      timerA = setTimeout(function(){ timerA = null; reportAppearance(); }, 300);
    }

    reportStructure();

    // 宿主可主动要求页面重报一次当前状态（切回该页 / 宿主重建时用）：
    // 页面侧上报是按"变化"去重的，长时间停留后又切回来不会再发，宿主拿不到当前主题。
    window.__lunaDshPing = function(){
      lastCap = null; lastDark = null; lastBg = '';
      reportStructure();
      reportAppearance();
    };
    // 宿主更新安全区（窗口 inset 变了但脚本已注入过）：走这里，不重跑整段脚本
    window.__lunaDshSafe = function(px){
      hostTop = Number(px) || 0;
      applySafeArea(hostTop);
    };

    // ── 宿主主题下发 ──
    // 为什么需要它：Android WebView 的 prefers-color-scheme 来自**宿主 App 的 theme**
    // （isLightTheme），宿主已让它跟随系统深色模式。但 DSH 控制台的色板根本不看 media
    // query —— 它靠 `<body data-ds-dark-theme>` 这个属性整体切色板。所以「宿主深色」这一
    // 事实必须显式驱动该属性，否则手机开了深色、DSH 页面照样是白的。
    // 走属性而非重载页面：不丢会话状态、不闪屏。
    function applyHostTheme(){
      try{
        if(!isDsh()) return;
        var b = document.body;
        if(!b) return;
        var has = b.hasAttribute('data-ds-dark-theme');
        if(HOST_DARK && !has) b.setAttribute('data-ds-dark-theme', '');
        else if(!HOST_DARK && has) b.removeAttribute('data-ds-dark-theme');
        // 顺带对齐 CSS color-scheme：页面切了色板，但 `color-scheme` 仍跟着**建 WebView 时**
        // 的 prefers-color-scheme（运行期改不了），于是深色页面上输入框/滚动条/原生控件
        // 会按亮色渲染，出现"白底白字"一类错配。这里直接跟色板对齐。
        var scheme = HOST_DARK ? 'dark' : 'light';
        var root = document.documentElement;
        if(root && root.style.colorScheme !== scheme) root.style.colorScheme = scheme;
      }catch(e){}
    }
    // 宿主切换主题时实时下发（App 侧 buildHostThemeScript → 这里）
    window.__lunaDshTheme = function(dark){
      HOST_DARK = !!dark;
      applyHostTheme();
      // 去重状态清掉，逼一次完整上报，让状态栏立刻跟着换色
      lastDark = null; lastBg = '';
      reportAppearance();
    };

    // 结构变化（顶栏插入/重建）→ 只补按钮，不做颜色读取（聊天流式文本高频触发的就是这里）
    try{ new MutationObserver(scheduleStructure).observe(document.body, { childList: true }); }catch(e){}
    // 外观变化（主题切换 / 抽屉开关）→ 只盯 class / 主题属性，触发完整上报
    try{
      var opt = { attributes: true, attributeFilter: ['class', 'data-ds-dark-theme'] };
      new MutationObserver(scheduleAppearance).observe(document.documentElement, opt);
      new MutationObserver(scheduleAppearance).observe(document.body, opt);
    }catch(e){}
    document.addEventListener('visibilitychange', function(){ scheduleStructure(); scheduleAppearance(); });
    window.addEventListener('resize', function(){ envTopCached = -1; scheduleAppearance(); });
    // 兜底：主题经样式表切换等观察不到的情况；结构补按钮也顺带复核（两条链路现在都便宜，
    // 重活已从高频路径上摘掉）
    setInterval(function(){ reportStructure(); reportAppearance(); }, 3000);
  }catch(e){}
})();
"""
}
