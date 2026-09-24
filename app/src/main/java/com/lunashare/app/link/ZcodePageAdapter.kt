package com.lunashare.app.link

import org.json.JSONObject
import kotlin.random.Random

/**
 * zcode 页面适配层（LunaShare Link ↔ zcode 网页）。
 *
 * 背景：zcode 远程页（remote/v4）有**两条**顶栏：
 *   - 第 1 栏（最外）=「任务会话」栏：左侧 `← 任务会话`、右侧一个圆形调色板（主题）图标；
 *   - 第 2 栏 = WorkspaceHeader（`<header data-testid="workspace-header">`，`📁 工作区 ⋯`）。
 * Link Tab 当前对 zcode 页显示一个原生右侧悬浮胶囊面板（主页 / 缩放复位 / 侧边栏）。
 * 本层把这三个按钮注入**第 1 栏「任务会话」栏里、主题图标左侧**，让原生悬浮面板可以隐藏
 * （与 DSH 移动端页同理），避免两套重复按钮 + 遮挡页面内容。
 * 注入锚点不写死 WorkspaceHeader：靠页面文字「任务会话」向上爬到该栏，再把按钮插到
 * 最后一个可见可点击元素（即主题图标）的左侧。
 *
 * 通信方式：与 [DshPageAdapter] 一致，走**控制台消息通道**（`console.log` →
 * `WebChromeClient.onConsoleMessage`），而非 `addJavascriptInterface` —— 注入脚本会跑到任意网页，
 * 暴露 JS 接口等于给所有页面开了原生入口。控制台消息零攻击面，且不依赖接口注册时机。
 *
 * 防伪：每个 WebView 生成一次性 `nonce`，写在脚本闭包内（外部脚本读不到）；回报必须带
 * 同一 nonce 才被采纳，避免第三方页面伪造「我是 zcode 页面」或直接触发原生动作。
 *
 * 与 DSH 适配层的差异：zcode 标题栏在 WebView 内容区顶部（其上方就是 App 状态栏色带，
 * 不存在 DSH 那种「页面被状态栏压住、需回填安全区」的问题），所以本层**不做**安全区补偿 /
 * 状态栏主题下发 —— 只负责「辨识 + 注入按钮 + 回报动作」。zcode 网页明暗现已锁定跟随 App
 * （WebView 的 prefers-color-scheme 由宿主 `android:isLightTheme` 驱动），页面自带的主题切换
 * 按钮失效且冗余，本层一并隐藏它（标记 `[data-luna-hidden-theme]`，CSS `display:none`）。
 */
object ZcodePageAdapter {

    /** 控制台消息前缀（与页面脚本约定的通道标识）；WebView 侧据此快速预筛 */
    const val MSG_PREFIX = "__LUNA_ZCODE__"

    /** 页面能力：已识别为 zcode 工作台（标题栏存在） */
    const val CAP_ZCODE = "zcode"

    /** 宿主注入按钮的动作标识（与注入脚本内 BTNS 的第一列一一对应，且与 DSH 同义） */
    const val ACTION_HOME = "home"
    const val ACTION_ZOOM = "zoom"
    const val ACTION_LUNA_SIDEBAR = "luna_sidebar"

    /** 页面回传的一条消息（字段都可选，按实际发生的事件填充） */
    data class PageMessage(
        /** 页面能力上报：`zcode` / `none` */
        val capability: String? = null,
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
     * @param nonce [newNonce] 生成的一次性口令
     */
    fun buildInjectScript(nonce: String): String =
        INJECT_JS.replace("__NONCE__", nonce)

    /**
     * 让页面重置去重状态并重报一次（切回该页 / 宿主重建后调用）。
     * 页面侧的上报是按"变化"去重的：页面在别的 Tab 停留一段时间后再切回来，cap 没变就不会重发，
     * 宿主此时拿到的 isZcodeTitlebar 可能已过期。主动 ping 一次即可。
     */
    fun buildPingScript(): String =
        "(function(){try{window.__lunaZcodePing&&window.__lunaZcodePing();}catch(e){}})();"

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
                action = obj.optString("action", "").takeIf { it.isNotEmpty() },
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 注入脚本：辨识页面 → 注入标题栏右侧按钮 → 回报能力。
     *
     * 性能：用 300ms 去抖合并高频 DOM 变更（zcode 聊天流式输出会频繁改动 DOM），
     * 且 [buildActions] 是幂等的（按钮已存在就跳过插入），避免重复节点与抖动。
     */
    private val INJECT_JS = """
(function(){
  try{
    if (window.__lunaZcodeAdapt) return;
    window.__lunaZcodeAdapt = true;

    var NONCE = '__NONCE__';
    var PREFIX = '__LUNA_ZCODE__';
    function send(o){ try{ o.n = NONCE; console.log(PREFIX + JSON.stringify(o)); }catch(e){} }

    // ── 1. 页面辨识 + 找「任务会话」栏 ──
    // zcode 远程页有两条顶栏：最外「任务会话」栏（左=← 任务会话 / 右=主题调色板）
    // 与内层 WorkspaceHeader。我们注入到第 1 栏里主题图标左侧。
    var _barCache = null;
    // 判定一个元素是否像"顶栏"：header 类 / role=banner / 或顶部一条短而宽的 flex 容器
    function isTopBar(el){
      try{
        var cls = (typeof el.className === 'string') ? el.className
                : (el.className && el.className.baseVal != null ? el.className.baseVal : '');
        if(/header|topbar|top-bar|navbar|titlebar|appheader|app-header/i.test(cls)) return true;
        if(el.tagName === 'HEADER' || (el.getAttribute && el.getAttribute('role') === 'banner')) return true;
        var cs = getComputedStyle(el);
        var r = el.getBoundingClientRect();
        if((cs.display === 'flex' || cs.display === 'inline-flex')
           && r.top < 80 && r.height < 90 && r.width > (window.innerWidth * 0.5)) return true;
      }catch(e){}
      return false;
    }
    // 「任务会话」栏：含"任务会话"文字且至少有一个按钮（主题切换）的容器。
    // 比 isTopBar 宽松——不卡 top/height/width（这些值在 SPA 切回/主题切换后可能有细微差异，
    // 旧逻辑因此误判为"非顶栏"→ 返回后 isZcode() 返回 false → 悬浮面板复现）。
    function isBarLike(el){
      try{
        if(el === document.body) return false;
        if(el.tagName === 'HEADER') return true;
        var cls = (typeof el.className === 'string') ? el.className
                : (el.className && el.className.baseVal != null ? el.className.baseVal : '');
        if(/header|topbar|top-bar|navbar|titlebar|appheader|app-header|bar/i.test(cls)) return true;
        var cs = getComputedStyle(el);
        if(!(cs.display === 'flex' || cs.display === 'inline-flex' || cs.display === 'grid')) return false;
        if((el.innerText || '').indexOf('任务会话') === -1) return false;
        if(!el.querySelector('button, a, [role="button"]')) return false;
        return true;
      }catch(e){ return false; }
    }
    function findTaskSessionBar(){
      if(_barCache && document.contains(_barCache)) return _barCache;
      var el = null;
      try{
        // (a) 含文字"任务会话"的节点，向上爬到**最近**一个"栏状"容器
        //     （含该文字 + 至少一个按钮≡主题切换）。取最近一层 → 即「任务会话」栏本身，
        //     不会误取到外层包住 WorkspaceHeader 的容器。
        var tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
        var n, titleEl = null;
        while((n = tw.nextNode())){
          if((n.nodeValue || '').indexOf('任务会话') !== -1){ titleEl = n.parentElement; break; }
        }
        if(titleEl){
          var cur = titleEl;
          while(cur && cur !== document.body){
            if(isBarLike(cur)){ el = cur; break; } // 取最近的栏状容器
            cur = cur.parentElement;
          }
        }
        // (b) 兜底：从 WorkspaceHeader 向上爬，取最外一层顶栏容器
        if(!el){
          var wh = document.querySelector('[data-testid="workspace-header"], header[data-workspace-header-variant]');
          var p = wh ? wh.parentElement : null;
          while(p && p !== document.body){
            if(isTopBar(p)) el = p;
            p = p.parentElement;
          }
        }
      }catch(e){}
      if(el) _barCache = el;
      return el;
    }

    function isZcode(){
      try{
        if(document.querySelector('[data-testid="workspace-header"], header[data-workspace-header-variant]')) return true;
        return !!findTaskSessionBar();
      }catch(e){ return false; }
    }

    // 与 DSH 注入同义的按钮：主页 / 缩放复位 / 连接列表（LunaShare 连接抽屉）
    var BTNS = [
      ['home',         '主页',     '<path d="M3 10.6 12 3.2l9 7.4"></path><path d="M5.6 9.4V20.4h12.8V9.4"></path>'],
      ['zoom',         '缩放复位', '<circle cx="11" cy="11" r="7"></circle><path d="M20 20l-3.7-3.7"></path><path d="M8 11h6"></path>'],
      ['luna_sidebar', '连接列表', '<rect x="3" y="5" width="18" height="14" rx="2.4"></rect><path d="M8 9h8"></path><path d="M8 13h5"></path>']
    ];

    // ── 2. 注入样式：第 1 栏里、主题图标左侧的胶囊组 ──
    // ⚠️ zcode 顶栏整条可能是窗口拖拽区（[app-region:drag]），按钮必须 no-drag 才能点。
    var CSS_ID = 'luna-zcode-hdr-style';
    function ensureStyle(){
      try{
        if(document.getElementById(CSS_ID)) return;
        var st = document.createElement('style');
        st.id = CSS_ID;
        st.textContent =
          '.luna-zcode-hdr-actions{display:inline-flex;align-items:center;gap:2px;flex:0 0 auto;' +
            'pointer-events:auto !important;-webkit-app-region:no-drag;app-region:no-drag}' +
          '.luna-zcode-hdr-actions:empty{display:none}' +
          '.luna-zcode-hdr-actions>button{width:32px;height:32px;border:0;border-radius:8px;' +
            'background:transparent;color:var(--foreground,#1f2328);display:inline-flex;' +
            'align-items:center;justify-content:center;padding:0;cursor:pointer;' +
            'transition:background .15s,opacity .15s}' +
          '.luna-zcode-hdr-actions>button:hover{background:rgba(127,127,127,.14)}' +
          '.luna-zcode-hdr-actions>button:active{opacity:.6}' +
          // 深色模式：zcode 在 <html> 上挂 .dark / .theme-zai-dark。此时 var(--foreground) 兜底成深色→按钮不可见，
          // 强制改白色（图标用 stroke=currentColor，随之变白）。
          'html.dark .luna-zcode-hdr-actions>button,html.theme-zai-dark .luna-zcode-hdr-actions>button{color:#fff}' +
          'html.dark .luna-zcode-hdr-actions>button:hover,html.theme-zai-dark .luna-zcode-hdr-actions>button:hover{background:rgba(255,255,255,.16)}' +
          // zcode 自带主题切换按钮：web 页明暗已锁定跟随 App（prefers-color-scheme 由宿主驱动）→ 隐藏
          '[data-luna-hidden-theme]{display:none !important}';
        (document.head || document.documentElement).appendChild(st);
      }catch(e){}
    }

    function makeBox(){
      var box = document.createElement('div');
      box.className = 'luna-zcode-hdr-actions';
      for(var i = 0; i < BTNS.length; i++){
        (function(k, title, path){
          var b = document.createElement('button');
          b.type = 'button';
          b.title = title;
          b.setAttribute('aria-label', title);
          b.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
            'stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">' + path + '</svg>';
          // 捕获阶段拦截，避免点到 zcode 自身的拖拽/路由行为
          b.addEventListener('click', function(ev){
            ev.preventDefault();
            ev.stopPropagation();
            send({ action: k });
          }, true);
          box.appendChild(b);
        })(BTNS[i][0], BTNS[i][1], BTNS[i][2]);
      }
      return box;
    }

    // 是否是宿主注入的按钮（或其容器）——用于把自身排除在"主题图标"候选之外
    function isLunaInjected(el){
      try{
        if(el.closest && el.closest('.luna-zcode-hdr-actions')) return true;
        if(el.querySelector && el.querySelector('.luna-zcode-hdr-actions')) return true;
      }catch(e){}
      return false;
    }

    // 最后一个可见的可点击元素（主题/调色板图标通常是最右那个），按钮插到它左侧
    function lastVisibleAction(bar){
      try{
        var els = bar.querySelectorAll('button, a, [role="button"]');
        for(var i = els.length - 1; i >= 0; i--){
          var el = els[i];
          if(isLunaInjected(el)) continue;
          if(el.offsetParent !== null || el.getClientRects().length > 0) return el;
        }
      }catch(e){}
      return null;
    }

    // zcode 自带主题切换按钮（该栏右侧调色板图标）：web 明暗已锁定跟随 App，按钮冗余 → 隐藏。
    // 优先按主题语义匹配图标/类名；匹配不到则退化为「最右可见可点击元素」（与注入锚点同源）。
    function findThemeToggle(bar){
      try{
        var els = bar.querySelectorAll('button, a, [role="button"]');
        for(var i = els.length - 1; i >= 0; i--){
          var e = els[i];
          if(isLunaInjected(e)) continue;
          var s = ((e.getAttribute && (e.getAttribute('aria-label') || '')) + ' ' +
                   (e.getAttribute && (e.getAttribute('title') || '')) + ' ' +
                   ((typeof e.className === 'string') ? e.className : '') + ' ' +
                   (e.innerHTML || ''));
          if(/palette|sun|moon|contrast|eclipse|theme|appearance|主题|配色|深浅|暗黑|明暗/i.test(s)) return e;
        }
      }catch(e){}
      return null;
    }
    function hideThemeToggle(bar){
      try{
        if(bar.querySelector('[data-luna-hidden-theme]')) return; // 已隐藏则跳过（避免退化分支误伤）
        var el = findThemeToggle(bar) || lastVisibleAction(bar);
        if(el && !isLunaInjected(el)){
          el.setAttribute('data-luna-hidden-theme', '');
          try{ console.log('[luna-zcode] hid theme toggle'); }catch(e){}
        }
      }catch(e){}
    }

    // ── 3. 注入「任务会话」栏（主题图标左侧）+ 隐藏自带主题按钮 ──
    function buildActions(){
      var bar = findTaskSessionBar();
      if(!bar) return;
      ensureStyle();
      // 先按锚点插入按钮（此刻主题按钮仍可见，锚点才准），再隐藏主题按钮
      var box = bar.querySelector('.luna-zcode-hdr-actions');
      if(!box){
        box = makeBox();
        var ref = lastVisibleAction(bar);
        if(ref && ref.parentElement){
          ref.parentElement.insertBefore(box, ref);
        } else {
          bar.appendChild(box);
        }
        try{ console.log('[luna-zcode] injected into <' + bar.tagName + '> cls=' + (typeof bar.className === 'string' ? bar.className : '')); }catch(e){}
      }
      hideThemeToggle(bar);
    }

    // ── 4. 上报（去抖，避免聊天流式高频 DOM 变更反复跑）──
    var lastCap = null, timer = null;
    function report(){
      var zc = isZcode();
      var cap = zc ? 'zcode' : 'none';
      if(cap !== lastCap){ lastCap = cap; send({ cap: cap }); }
      if(!zc) return;
      buildActions();
    }
    function schedule(){
      if(timer) return;
      timer = setTimeout(function(){ timer = null; report(); }, 300);
    }

    window.__lunaZcodePing = function(){ lastCap = null; report(); };
    report();
    try{ new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true }); }catch(e){}
    // 兜底：SPA 切走/切回标题栏可能观察不到，定时复核
    setInterval(function(){ report(); }, 2000);
  }catch(e){}
})();
"""
}
