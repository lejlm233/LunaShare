package com.lunashare.app.link

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lunashare.app.MainActivity
import com.lunashare.app.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * zcode web 页事件 → 系统通知。
 *
 * 在 Link 浏览器针对 zcode 页注入 JS（[ZCODE_WATCHER_JS]），复刻「ZCode 远程任务通知」Chrome 扩展
 * 的检测与降噪逻辑，做到与 Web 端一致、可靠的通知：
 *
 * 检测（页面内）——
 *  - 数据层 tap：钩住 JSON.parse，捕获中继 WebSocket 下发的真实任务对象（taskId + displayStatus/status）
 *    与阻塞交互对象（interactionId + kind/payload.kind），与页面渲染成什么布局无关
 *    （桌面工作台 / 移动端首页 / 移动端会话视图都能拿到同一份数据），
 *    且由 WS message / JSON.parse 事件驱动，不受后台定时器节流影响。
 *  - fiber 层：遍历 React fiber 树，读 `[data-web-remote-task-row]` 任务的 props.displayStatus、
 *    移动端会话视图的 `resolvedActiveTaskMeta`，以及**所有阻塞交互**（见下条）。
 *    ⚠️ 遍历必须用真正的 Set 去重：fiber 是对象，用普通对象当 seen 会全部塌成 "[object Object]"，
 *    导致只检查第一个节点（这正是「待确认有时行有时不行」的历史根因）。
 *  - 阻塞交互层（权威）：`ZCodeTaskMeta.pendingInteraction` / `TaskInteractionBadge` 的 props.interaction /
 *    弹窗组件的 props.pending / `ConversationSnapshot.pendingInteractions[]`（含 projection / snapshot /
 *    Context Provider 的 value 里的同名数组），按 `interactionId` 去重 —— 「需要确认」的权威事实源
 *    （ZCodeTaskRuntimeStatus 里根本没有等待态，所以读 task status 永远抓不到它）。
 *  - 徽标层（精确 DOM 锚点）：`[data-task-interaction-badge="permission"|"userInput"]`。
 *  - 标签层：精确匹配状态徽标短文本（等待确认/需要确认/需要权限…）。
 *  - 宿主心跳：由 LunaShare 每 2s 主动调用 `window.__lunaZcodeTick()`（[ZCODE_TICK_JS]）驱动一次完整检测
 *    —— 页面内的 setInterval 在 App 退到后台会被 WebView 节流到 ≥1 分钟，这是及时性的保证。
 *  - 各来源按来源独立去重，再汇入全局状态机，状态机只在**转变**时产生事件。
 *  - 缺席对账（`reconcilePending`）：交互在 fiber/DOM 里是**直接消失**的（等待期间任务 status 一直是
 *    running，「等待确认 → 其他」这种转变根本观察不到）→ 某 key 本轮所有来源都不再报、且持续
 *    ≥ [PEND_MISS_MS] → 判定「交互已被处理」→ 发 `confirm_cleared` 让宿主撤掉常驻通知。
 *    用**时间**而非轮数做门槛：每条 WS 消息都会驱动一次 tick，轮数没有时间意义。
 *
 * 降噪（页面内状态机）——
 *  - 仅 `运行中→完成/失败`、`任意→需要确认` 才通知（严格模式，不会把历史任务/重连当新事件）；
 *  - 同一 (kind, taskId) 90 秒冷却（「需要确认」单独收紧到 5s：它的 key 是 interactionId，每次交互都
 *    不同、不存在重复轰炸，长冷却只会吞掉徽标兜底路径复用的 key 而漏报）；
 *  - 重连横幅期间冻结「完成/失败」判定，避免重连误报「已完成」；
 *  - 页面处于前台可见时保持安静（你正盯着看），切走 / 退到后台才提醒——与 Web 扩展默认行为一致。
 *
 * 三类事件（各自独立通知渠道）——
 *   - completed（绿✓）：生成完成；
 *   - failed（红✗）：任务失败；
 *   - needs_confirm（橙💬，常驻直到你点掉 / 从它进入 App）：需要你回答 / 授权 / 批准；
 *   - confirm_cleared（不出通知，只撤销）：那条「需要确认」通知的交互已被处理（你已在 App 里
 *     回答/授权）→ 让宿主 cancel 掉对应那条通知。**不受任何开关/静音/可见性影响**
 *     （你回答时页面必然可见，走「可见时静音」就永远撤不掉）。
 * 通知点击回到 MainActivity 并落到 Link Tab。
 *
 * 堆叠（务必保持）——
 *  - 每条事件都是**独立通知 id**（[nextId]，ask/completed/failed 共用一个分配器），
 *    所有 zcode 通知用 [NOTIF_GROUP] 归到同一组 → 通知中心自动堆叠成一组，可同时挂很多条。
 *  - 「需要确认」是**一个交互一条**（[askSlots]），交互被处理时按 key 精确撤销自己那条，
 *    绝不误伤同时挂着的其它条。绝不要退回「全局单槽」：那会让第二件待确认覆盖第一件。
 *
 * 通知消失行为（务必保持）——
 *  - 三条通知 `setAutoCancel(true)` **恒定**：「常驻」只用「不设超时」表达，绝不再用
 *    `setAutoCancel(false)` 表达 —— 那会让「点通知进 App」后通知仍挂在通知中心（历史 bug）。
 *  - `contentIntent` 携带通知 id（`ZCODE_EXTRA_NOTIF_ID`），MainActivity 收到后显式 cancel：
 *    ROM 的 autoCancel 在 SINGLE_TOP 唤前台时不一定触发，显式取消才是确定行为。
 *  - `PendingIntent` 的 requestCode 必须是通知 id，否则共用 0 时后建通知会覆盖前者的 extras。
 *
 * 提醒只响一次（务必保持）——
 *  - 页面侧对**同一个**阻塞交互会重复上报（来源抖动后重现），而 Android 的
 *    `notify()` 语义是「更新同 id 通知」，默认**每次更新都重新响铃 / 振动 / 弹横幅**。
 *    于是「通知只有一条，却一直弹，直到你去确认」—— 两处必须同时成立才不弹：
 *    ① [notify] 恒定 `setOnlyAlertOnce(true)`（平台级：同一条通知更新时至多响一次）；
 *    ② [postAsk] 以 key 为身份：同 key 且文案没变 → 连 `notify()` 都不调；同 key 文案变了 →
 *       原地更新同一条（不响铃）。新的交互 = 新 key = 新 id → 才响一次。
 *  - 页面侧另有两道防线（见 [ZCODE_WATCHER_JS]）：`needs_confirm` 的 key 一律归一到 interactionId；
 *    缺席对账的存活标记在 `applyObservation` 统一登记 + [PEND_MISS_MS]/[PEND_REARM_MS] 双门槛，
 *    避免「撤掉通知 → 下一轮又报 → 又弹」的振荡。
 */

/** 通知点击携带的 extra：要求 MainActivity 打开 Link Tab。 */
const val ZCODE_EXTRA_OPEN_LINK_TAB = "zcode_open_link_tab"

/**
 * 通知点击携带的 extra：被点击那条通知的 id。
 * MainActivity 收到后显式 `cancel(id)` —— 系统 `setAutoCancel` 在部分 ROM（华为 MagicOS 等）
 * 上，当 Activity 只是被 SINGLE_TOP 唤到前台时并不总触发，而通知是否消失完全取决于这条系统行为。
 * 显式取消让「点了通知 / 从通知进入 App，通知就消失」变成确定行为，不依赖 ROM。
 */
const val ZCODE_EXTRA_NOTIF_ID = "zcode_notif_id"

/**
 * 宿主侧心跳：让页面立刻跑一次通知检测（[ZCODE_WATCHER_JS] 把它暴露为 window.__lunaZcodeTick）。
 *
 * 为什么必须有它：App 退到后台 / 息屏时，WebView 会把页面内的 setInterval 节流到 ≥1 分钟，
 * 而「待确认」常常正好发生在你切走之后（Agent 在等你回答）。宿主主动 evaluateJavascript
 * 不受节流限制，因此由宿主按固定节奏驱动，页面内的定时器只作前台兜底。
 */
const val ZCODE_TICK_JS =
    "(function(){try{if(window.__lunaZcodeTick)window.__lunaZcodeTick();}catch(e){}})();"

/**
 * 注入 zcode 网页的监听脚本：在页面内完成「多层检测 + 状态机 + 降噪」，仅在真实转变量上回调
 * window.LunaZcode.onTaskEvent(kind, taskId, title, from, to)。
 *
 * 设计要点（复刻 Chrome 扩展 probe.js + detector.js 的核心）：
 *  - JSON.parse tap 在 MAIN world 钩住，捕获 taskId 以 `sess_` 开头的任务对象及其 displayStatus/status；
 *  - 四个来源各自记忆「上次上报状态」(srcLast)，冻结源（如会话视图 meta）重复重放旧状态不会与
 *    实时源互相打架造成 running↔completed 振荡（这是重复/误报的根因）；
 *  - 全局 state 记忆每个任务的最后状态，primed 前的首批只做快照、不触发，之后只在转变时发事件；
 *  - 可见性抑制：Kotlin 侧写入 window.__lunaLinkVisible（Link Tab 激活 && 当前是 zcode && App 前台）
 *    为 true 时不发「完成/失败」通知；「需要确认」是必须操作的提示，不受此抑制、始终弹出。
 */
const val ZCODE_WATCHER_JS = """
(function(){
  try {
    if (window.__lunaZcodeWatcher) return;
    window.__lunaZcodeWatcher = true;

    function classifyStatus(status){
      var s = String(status||'').toLowerCase();
      if(!s) return null;
      if(/await|approval|confirm|permission|pending|attention|waiting|input/.test(s)) return 'needs_confirm';
      if(/error|fail|crash|abort/.test(s)) return 'failed';
      if(/complete|done|finish|success/.test(s)) return 'completed';
      if(/run|stream|generat|work|exec|progress|active/.test(s)) return 'running';
      return null;
    }

    // 全局状态：key -> {kind, title, confirmActive}
    var state = {};
    var metaTitles = {};
    var primed = false;
    // 各来源独立记忆上次上报值，避免冻结源与实时源振荡
    var srcLast = {};
    // 冷却：同一 (kind|taskId) 若干秒内只发一次（时长由 Kotlin 侧按用户配置写入）
    var lastFire = {};
    var COOLDOWN_MS = 90000;
    var tickCount = 0;

    // 「阻塞交互已被处理」的缺席检测（见 reconcilePending）——
    // 交互在 fiber/DOM 里是**直接消失**的，没有状态转变可观察，只能靠缺席判定。
    var roundSeen = {};        // 本轮所有来源观察到的 needs_confirm key（每轮 tick 开头重置）
    var pendGoneSince = {};    // key -> 首次发现缺席的时间戳
    // 缺席持续 ≥ 此值才认定「交互已处理」（WS 每条消息都驱动 tick，轮数无时间意义，必须用时间）。
    // ⚠️ 必须大于**最慢来源**的采样间隔：文字标签层每 2 次 tick 才扫一次（宿主心跳 2s → 最慢 4s），
    //    门槛若比它小，标签层的 key 会在两次采样之间的那次 tick 被判「已处理」→ 撤了下一轮又弹。
    var PEND_MISS_MS = 8000;
    // 再武装保护窗口：这条 key 刚提示过（距今 < 此值）时，不因一次「缺席」就撤通知 ——
    // 来源抖动导致的假缺席如果立刻撤掉通知，下一轮它又出现，就又弹一次（每次都响铃/振动）。
    var PEND_REARM_MS = 10000;

    // 运行时配置：由 Kotlin 侧在注入/改配置时写入 window.__lunaNotifyCfg。
    // 缺省值与 NotifySettings 默认值一致，保证未配置时行为不变。
    function cfg(){
      var d = {
        enabled:true, notifyOnConfirm:true, notifyOnCompleted:true, notifyOnFailed:true,
        notifyWhenVisible:false, checkOnOpen:true, strictRunning:true, cooldownMs:90000
      };
      try {
        var c = window.__lunaNotifyCfg;
        if(!c || typeof c!=='object') return d;
        for(var k in d){ if(c[k] !== undefined) d[k] = c[k]; }
      } catch(e){}
      return d;
    }
    function kindEnabled(kind, c){
      // 「已处理」不是通知类别，而是撤销指令（撤掉已弹出的常驻通知）→ 永远放行，
      // 否则用户关掉「任务完成时通知」会连撤销一起关掉，通知永远撤不掉。
      if(kind === 'confirm_cleared') return true;
      if(!c.enabled) return false;
      if(kind === 'needs_confirm') return c.notifyOnConfirm !== false;
      if(kind === 'failed') return c.notifyOnFailed !== false;
      return c.notifyOnCompleted !== false;
    }

    // 是否「正被用户注视」：由 Kotlin 侧按 Link Tab 是否激活 + 当前是否为 zcode 页写入
    // window.__lunaLinkVisible（true=正在看，其余 false/undefined=没在看）。
    // 不用 document.visibilityState：本 App 的「文件/服务」Tab 只是把 zcode WebView 移出屏幕，
    // document 仍可能报 visible，会导致完成/失败永远被抑制。
    function pageVisible(){
      try { return window.__lunaLinkVisible === true; } catch(e){ return false; }
    }

    // 重连横幅检测：冻结「完成/失败」判定，避免重连误报已完成
    var lastReconnectAt = 0;
    function isReconnecting(){
      try {
        var txt = document.body ? document.body.innerText : '';
        if(/连接中断|正在重连|连接已断开|Connection interrupted|reconnecting\.\.\./i.test(txt)){
          lastReconnectAt = Date.now();
          return true;
        }
      } catch(e){}
      return (Date.now() - lastReconnectAt) < 3000;
    }

    function fireNow(kind, taskId, title, detail){
      var key = kind + '|' + taskId;
      var c = cfg();
      var cd = (typeof c.cooldownMs === 'number' && c.cooldownMs >= 0) ? c.cooldownMs : COOLDOWN_MS;
      // 「需要确认」的 key 通常是 interactionId（每次交互都不同，不存在重复轰炸）；
      // 只有 DOM 徽标兜底路径会退化成复用同一串 key，被长冷却吞掉就会漏报 → 单独收紧冷却。
      if(kind === 'needs_confirm' && cd > 5000) cd = 5000;
      // 「已处理」是幂等的撤销动作，不需要冷却（否则连续多个交互被处理时会漏撤）
      if(kind === 'confirm_cleared') cd = 0;
      var now = Date.now();
      if(now - (lastFire[key] || 0) < cd) return;
      lastFire[key] = now;
      try {
        if(window.LunaZcode && window.LunaZcode.onTaskEvent)
          window.LunaZcode.onTaskEvent(kind, taskId, title||'', '', detail||'');
      } catch(e){}
    }

    // 发送决策（用户可在「通知行为」里逐项配置）：
    //  - 总开关关闭 → 一律不发；
    //  - 该类别事件被关闭 → 不发；
    //  - 待确认是必须操作的提示，默认即便正看着也弹（受 notifyWhenVisible 之外的独立规则保护）；
    //  - 完成/失败：页面正被注视时静音（除非用户勾选「页面正被查看时也通知」），
    //    重连横幅期间冻结。
    function requestEmit(kind, taskId, title, detail){
      var c = cfg();
      if(!kindEnabled(kind, c)) return;
      if(kind === 'needs_confirm' || kind === 'confirm_cleared'){
        fireNow(kind, taskId, title, detail);
        return;
      }
      if(pageVisible() && c.notifyWhenVisible !== true) return;
      if(isReconnecting() && (kind==='completed'||kind==='failed')) return;
      fireNow(kind, taskId, title, detail);
    }

    // 状态机：仅在转变量上发事件（与扩展一致）
    function applyObservation(key, kind, title, detail){
      if(!key || !kind) return;
      // ⚠️「本轮该 key 仍然存在」的存活标记必须登记在**这个唯一漏斗**里，不能只写在 ingestFrom：
      //    scanStatusLabels / scanInteractionBadges 是直接调用本函数的（不经过 ingestFrom），
      //    漏登记 → reconcilePending 把这些 key 当成「交互已被处理」→ 撤掉通知 → 下一轮又观察到
      //    → 重新发 needs_confirm → 通知被反复弹出（这正是「一条通知但一直弹」的根因）。
      if(kind === 'needs_confirm') roundSeen[key] = 1;
      if(title) metaTitles[key] = String(title);
      var entry = state[key] || {};
      if(title) entry.title = String(title);
      if(detail) entry.detail = String(detail);
      var prev = entry.kind || null;
      if(prev === kind){ state[key] = entry; return; }
      entry.kind = kind; state[key] = entry;
      var c = cfg();
      if(!primed){
        // 初始快照：已处于“需要确认”且用户开启「页面刚打开时提醒」→ 立即提醒一次
        if(kind === 'needs_confirm'){
          entry.confirmActive = true;
          if(c.checkOnOpen !== false) requestEmit('needs_confirm', key, entry.title, entry.detail);
        }
        return;
      }
      // 同一个 key 从「待确认」转走（任务恢复运行 / 直接完成）→ 交互已被处理，先撤掉常驻通知。
      // 这条只覆盖 taskId 型 key；interactionId 型 key 是「直接消失」，靠 reconcilePending 的缺席检测。
      if(prev === 'needs_confirm' && kind !== 'needs_confirm'){
        requestEmit('confirm_cleared', key, entry.title, entry.detail);
      }
      var fire = null;
      if(kind === 'needs_confirm' && prev !== 'needs_confirm'){ fire = 'needs_confirm'; entry.confirmActive = true; }
      else if(kind === 'completed' || kind === 'failed'){
        // 严格模式（默认）：仅「运行中/待确认 → 终态」的转变才通知，避免把历史任务当新事件。
        // 宽松模式：只要观察到终态就通知（页面刷新可能误报，用户在设置里自行权衡）。
        if(c.strictRunning !== false){
          if(prev === 'running' || prev === 'needs_confirm') fire = kind;
        } else {
          fire = kind;
        }
      }
      if(fire) requestEmit(fire, key, entry.title, entry.detail);
    }

    // 各来源独立去重后再交给状态机（status 为原始状态串，由 classifyStatus 归类）
    function ingestFrom(src, list){
      if(!list || !list.length) return;
      var sl = srcLast[src] || (srcLast[src] = {});
      for(var i=0;i<list.length;i++){
        var it = list[i];
        if(!it || !it.taskId || !it.status) continue;
        var k = classifyStatus(it.status);
        // ⚠️ 存活标记必须在「来源去重」之前：状态没变时会 continue，那之后再记就漏掉了
        //    「本轮该交互仍然存在」这个事实 → 缺席检测会把它误判成已处理（误撤通知）。
        if(k === 'needs_confirm') roundSeen[it.taskId] = 1;
        if(sl[it.taskId] === it.status) continue;
        sl[it.taskId] = it.status;
        if(k) applyObservation(it.taskId, k, it.title, it.icKind);
      }
    }

    // ---------- 来源 1：JSON.parse tap（数据层，视图无关） ----------
    // 同时收集两类事实：
    //   ① `sess_` 任务对象的 displayStatus/status（完成/失败/运行中）；
    //   ② 阻塞交互对象（interactionId + kind/payload.kind）→ 「待确认」。
    // 由中继 WebSocket 的 message 驱动，事件到达即判定，不受后台定时器节流影响。
    function scanTaskish(obj, depth, hits){
      if(!obj || typeof obj!=='object' || depth>8 || hits.nodes>1500) return;
      hits.nodes++;
      if(obj.taskId && typeof obj.taskId==='string' && obj.taskId.slice(0,5)==='sess_'){
        var st = obj.displayStatus!==undefined ? obj.displayStatus : obj.status;
        if(st && typeof st==='string'){
          hits.found.push({taskId:obj.taskId, status:st, title:typeof obj.title==='string'?obj.title:null});
        }
      }
      var pi = pendInfo(obj);
      if(pi) hits.pend.push({taskId:pi.id, status:'needs_confirm', icKind:pi.icKind,
                             title:typeof obj.title==='string'?obj.title:null});
      for(var k in obj){
        var v = obj[k];
        if(v && typeof v==='object'){
          if(Array.isArray(v)){ var n=Math.min(v.length,80); for(var i=0;i<n;i++) scanTaskish(v[i],depth+1,hits); }
          else scanTaskish(v,depth+1,hits);
        }
      }
    }
    var tapInstalled = false;
    function installTap(){
      if(tapInstalled) return; tapInstalled=true;
      try{
        var origParse = JSON.parse;
        JSON.parse = function(text, reviver){
          var result = origParse.call(JSON, text, reviver);
          try{
            if(result && typeof result==='object'){
              var hits={nodes:0,found:[],pend:[]};
              scanTaskish(result,0,hits);
              if(hits.found.length) ingestFrom('tap', hits.found);
              if(hits.pend.length) ingestFrom('tap-pend', hits.pend);
            }
          }catch(e){}
          return result;
        };
      }catch(e){}
    }

    // ---------- 来源 2：fiber 任务行（桌面工作台） ----------
    function extractRows(){
      var rows = document.querySelectorAll('[data-web-remote-task-row]');
      var list=[];
      for(var i=0;i<rows.length;i++){
        try{
          var el=rows[i];
          var keys=Object.keys(el); var fk=null;
          for(var k=0;k<keys.length;k++){ if(keys[k].indexOf('__reactFiber')===0){fk=keys[k];break;} }
          if(!fk) continue;
          var f=el[fk]; var task=null;
          for(var d=0; d<5 && !task && f; d++){
            var p=f.memoizedProps;
            if(p && p.task && p.task.taskId) task=p.task;
            f=f.return;
          }
          if(!task) continue;
          list.push({taskId:String(task.taskId), status:String(task.displayStatus||''), title: task.title?String(task.title):null});
        }catch(e){}
      }
      return list;
    }

    // ---------- 来源 3：当前打开任务的元数据（移动端会话视图） ----------
    function fiberOf(el){
      try{
        var keys=Object.keys(el);
        for(var k=0;k<keys.length;k++){ if(keys[k].indexOf('__reactFiber')===0) return el[keys[k]]; }
      }catch(e){}
      return null;
    }

    // ---------- 来源 4（权威）：阻塞交互 pendingInteraction / pendingInteractions ----------
    // zcode 源码事实（shared/src/zcode-task-types-core.ts、zcode-protocol-v4/snapshot.ts）：
    //  - ZCodeTaskMeta.pendingInteraction: {interactionId, kind:'permission'|'userInput', toolName?}
    //    在 App props 里以 resolvedActiveTaskMeta 出现，任务列表行以 task.pendingInteraction 出现。
    //  - ConversationSnapshot.pendingInteractions[]: 完整阻塞交互，payload.kind 可为
    //    'permission' | 'userInput' | 'workspaceHookReview'（后者官方也明确不发系统通知）。
    //  - 官方去重键 = interactionId（见 hooks/useTaskNotifications.ts 的 seenRequestIds）。
    // ⚠️ 旧脚本只读 resolvedActiveTaskMeta.status，但「待确认」根本不是 task status
    //    （ZCodeTaskRuntimeStatus 无 waiting），所以永远抓不到 —— 这是本次修复的核心。
    // 统一提取阻塞交互，兼容页面里出现的两种形状：
    //   ① ZCodeTaskMeta.pendingInteraction / TaskInteractionBadge props.interaction → {interactionId, kind}
    //   ② ConversationSnapshot.pendingInteractions[] 的元素 → {interactionId, payload:{kind}}
    // 官方排除项：payload.kind === 'workspaceHookReview'（Settings/Hooks 行内处理，官方也明确不发系统通知）。
    function pendInfo(o){
      try{
        if(!o || typeof o!=='object') return null;
        var id=o.interactionId;
        if(typeof id!=='string' || !id) return null;
        var k=String(o.kind || (o.payload && o.payload.kind) || '').toLowerCase();
        if(k==='workspacehookreview') return null;
        if(k!=='permission' && k!=='userinput' && k!=='user_input') return null;
        return {id:String(id), icKind:(k==='permission'?'permission':'userInput')};
      }catch(e){ return null; }
    }
    function isPendingInteraction(o){ return !!pendInfo(o); }
    function addPend(out, seenId, pi, title){
      try{
        var info=pendInfo(pi);
        if(!info) return;
        if(seenId[info.id]) return; seenId[info.id]=1;
        out.push({taskId:info.id, status:'needs_confirm', title:title?String(title):null,
                  icKind:info.icKind});
      }catch(e){}
    }
    function collectSnapshotPendings(out, seenId, arr){
      try{
        if(!arr || !arr.length) return;
        var n=Math.min(arr.length, 200);
        for(var i=0;i<n;i++) addPend(out, seenId, arr[i], null);
      }catch(e){}
    }
    // 一次 fiber 遍历同时取「活动任务元数据」与「所有阻塞交互」。
    //
    // ⚠️ 关键修复（本版的根因）：上一版用 `var seen={}` 对 fiber **对象** 去重 —— JS 里对象作
    // 普通属性键会被 String() 成同一个 "[object Object]"，于是第一个 fiber 之后**全部都**被当成
    // 「已访问」跳过，BFS 实际只检查了第一个种子元素的 memoizedProps。来源 4 因此形同失效，
    // 「待确认」能不能抓到纯看第一个 DOM 元素碰运气 → 现象就是「有时行有时不行」。
    // 这里改用真正的 Set 去重，并让每个种子先爬到 React root 再遍历，保证整棵树（含 portal）被覆盖。
    function extractFromFibers(){
      var meta=null, pendings=[], seenId={};
      try{
        var all=document.querySelectorAll('body *');
        var slimit = all.length < 400 ? all.length : 400;
        var visited = new Set();
        var queue=[];
        for(var i=0;i<slimit;i++){
          var f0=fiberOf(all[i]);
          if(!f0) continue;
          var r=f0;
          for(var up=0; up<120 && r.return; up++) r=r.return;   // 爬到 root（同 root 的种子会被 visited 合并）
          queue.push(r);
        }
        var steps=0;
        for(var qi=0; qi<queue.length && steps<150000; qi++){
          var f=queue[qi];
          if(!f || visited.has(f)) continue;
          visited.add(f);
          steps++;
          var p=f.memoizedProps;
          if(p && typeof p==='object'){
            collectFromProps(p, pendings, seenId, function(m){
              if(!meta && m && m.taskId){
                meta={taskId:String(m.taskId),
                      status:String(m.status||(m.__zcodeSessionActivity&&m.__zcodeSessionActivity.phase)||''),
                      title:m.title?String(m.title):null};
              }
            });
          }
          if(f.child) queue.push(f.child);
          if(f.sibling) queue.push(f.sibling);
        }
      }catch(e){}
      return {meta:meta, pendings:pendings};
    }

    // 一个 props 对象上可能承载任务/交互的所有位置（zcode 的 projection 层层透传，单看 props 会漏）：
    //   p.interaction / p.pending                      → PermissionDialog / ElicitationDialog / V4UserInputDialog
    //   p.task.pendingInteraction                      → 任务列表行
    //   p.resolvedActiveTaskMeta / p.activeTaskMeta / p.taskMeta / p.meta
    //   p.pendingInteractions / p.snapshot.* / p.projection.* / p.conversation.* / p.value(Context Provider)
    function collectFromProps(p, pendings, seenId, onMeta){
      try{
        if(p.interaction) addPend(pendings, seenId, p.interaction, null);
        if(p.pending) addPend(pendings, seenId, p.pending, null);

        var metas=[p.task, p.meta, p.taskMeta, p.activeTaskMeta, p.resolvedActiveTaskMeta];
        for(var i=0;i<metas.length;i++){
          var m=metas[i];
          if(!m || typeof m!=='object') continue;
          if(onMeta) onMeta(m);
          if(m.pendingInteraction) addPend(pendings, seenId, m.pendingInteraction, m.title);
        }

        collectSnapshotPendings(pendings, seenId, p.pendingInteractions);
        var holders=[p.snapshot, p.projection, p.conversation, p.conversationSnapshot, p.value];
        for(var j=0;j<holders.length;j++){
          var h=holders[j];
          if(!h || typeof h!=='object') continue;
          collectSnapshotPendings(pendings, seenId, h.pendingInteractions);
          if(onMeta && h.activeTaskMeta) onMeta(h.activeTaskMeta);
          if(h.pending) addPend(pendings, seenId, h.pending, null);
        }
      }catch(e){}
    }

    // ---------- 阻塞交互的**统一身份** ----------
    // ⚠️ 一个阻塞交互在 fiber 层 / 官方徽标层 / 文字标签层里必须是**同一个** key，否则同一交互
    //    会被状态机当成两个交互上报两次。真实现象：同一条通知的文案在两种说法之间变来变去
    //    （「有工具调用在等你授权」↔「Agent 在等你的回答」）—— 因为两层携带的 kind 信息不一样：
    //    fiber 层带 kind=permission，文字层只报了 key、没带 kind，于是走兜底文案。
    // 统一推导：
    //   ① 能沿 fiber 祖先反查到 pendingInteraction → interactionId + kind（最权威）；
    //   ② 否则退到 DOM 锚点（最近的官方徽标）→ 'pend:' + 徽标的稳定 key + 徽标属性里的 kind。
    //      徽标层与文字层都走这里，同一个徽标就得到同一个串。
    function interactionAnchor(el){
      try{
        if(el && el.getAttribute && el.getAttribute('data-task-interaction-badge')) return el;
        return (el && el.closest) ? el.closest('[data-task-interaction-badge]') : null;
      }catch(e){ return null; }
    }
    function pendingIdentity(el){
      try{
        var f=fiberOf(el);
        for(var d=0; d<14 && f; d++){
          var p=f.memoizedProps;
          if(p && typeof p==='object'){
            var info = pendInfo(p.interaction)
              || pendInfo(p.task && p.task.pendingInteraction)
              || pendInfo(p.resolvedActiveTaskMeta && p.resolvedActiveTaskMeta.pendingInteraction);
            if(info) return info;
          }
          f=f.return;
        }
      }catch(e){}
      var b=interactionAnchor(el);
      if(b){
        var ka=String(b.getAttribute('data-task-interaction-badge')||'').toLowerCase();
        if(ka==='permission' || ka==='userinput'){
          return {id:'pend:'+findKey(b), icKind:(ka==='permission'?'permission':'userInput')};
        }
      }
      return {id:null, icKind:null};
    }
    // 「需要确认」的 key：统一身份拿不到就退化为元素自身的稳定 key（同样加 pend: 前缀）
    function pendingKeyOf(el, ident){
      if(ident && ident.id) return ident.id;
      var anchor = interactionAnchor(el) || el;
      return 'pend:' + findKey(anchor);
    }

    // ---------- 来源 5：DOM 精确锚点（zcode 官方徽标 data-task-interaction-badge） ----------
    // TaskInteractionBadge.tsx 渲染 [data-task-interaction-badge="permission"|"userInput"]。
    // 该属性是 zcode 自己的语义锚点，比文字匹配稳：旧脚本文字层把 button 例入 SKIP_INTERACTIVE，
    // 而 userInput 徽标恰好渲染成 <button>，整类被跳过 —— 这也是「待确认」丢失的一个原因。
    function scanInteractionBadges(){
      var out=[];
      try{
        var nodes=document.querySelectorAll('[data-task-interaction-badge]');
        var seenId={};
        for(var i=0;i<nodes.length;i++){
          var el=nodes[i];
          var kindAttr=String(el.getAttribute('data-task-interaction-badge')||'').toLowerCase();
          if(kindAttr!=='permission' && kindAttr!=='userinput') continue;
          var ident=pendingIdentity(el);
          var id=pendingKeyOf(el, ident);
          var icKind=ident.icKind || (kindAttr==='permission'?'permission':'userInput');
          if(seenId[id]) continue; seenId[id]=1;
          out.push({taskId:id, status:'needs_confirm', title:findCardTitle(el), icKind:icKind});
        }
      }catch(e){}
      return out;
    }

    // ---------- 移动端「待确认」标签扫描（核心缺失项） ----------
    // PC 扩展在移动端主要靠卡片状态徽标的精确文本匹配；原脚本漏了这条，导致待确认检测不到。
    var SKIP_INTERACTIVE = 'button,[role="button"],[role="menuitem"],[role="tab"],input,select,textarea,[contenteditable="true"],[contenteditable=""],span[title]';
    // 状态徽标精确匹配表（完整对齐 PC 扩展 STATUS_LABELS：精确匹配可免疫标题/聊天记录污染）
    var STATUS_LABELS = {
      '空闲':null,'已就绪':null,'未就绪':null,'恢复会话':'running',
      '运行中':'running','生成中':'running','恢复中':'running','执行中':'running',
      '已完成':'completed',
      '等待确认':'needs_confirm','需要确认':'needs_confirm','需要权限':'needs_confirm',
      '失败':'failed','错误':'failed','任务失败':'failed',
      'idle':null,'ready':null,'not ready':null,'notready':null,
      'running':'running','streaming':'running','restoring':'running',
      'completed':'completed','done':'completed',
      'failed':'failed','error':'failed',
      'awaiting approval':'needs_confirm','permission required':'needs_confirm'
    };
    function statusLabelKind(text){
      var s = String(text||'').trim().toLowerCase();
      if(!s || s.length>20) return null;
      return Object.prototype.hasOwnProperty.call(STATUS_LABELS, s) ? STATUS_LABELS[s] : null;
    }
    function fnv1a(str){
      var h=0x811c9dc5; for(var i=0;i<str.length;i++){ h^=str.charCodeAt(i); h=Math.imul(h,0x01000193)>>>0; }
      return ('0000000'+h.toString(36)).slice(-7);
    }
    function findKey(el){
      try{
        var cur=el;
        for(var i=0;i<8 && cur && cur!==document.body;i++){
          if(cur.getAttribute){
            var t=cur.getAttribute('data-testid');
            if(t){ var m=t.match(/^task-item-(.+)$/); if(m) return m[1]; }
            var tid=cur.getAttribute('data-task-id')||cur.getAttribute('data-session-id')||cur.getAttribute('data-id');
            if(tid) return tid;
          }
          cur=cur.parentElement;
        }
      }catch(e){}
      return 'x:'+fnv1a((el.textContent||'').trim());
    }
    // 从状态徽标叶子节点反查所属任务卡片，取最长文本作为标题（与 PC cardInfo 一致，<=80）
    function findCardTitle(el){
      try{
        var card = el.closest ? el.closest('[data-web-remote-task-row]') : null;
        if(!card && el.closest) card = el.closest('[data-testid^="task-item-"]');
        if(!card){
          var cur = el;
          for(var i=0;i<10 && cur && cur!==document.body;i++){
            var t=(cur.getAttribute?cur.getAttribute('data-testid'):'')||'';
            if(t.indexOf('task-item-')===0){ card=cur; break; }
            cur=cur.parentElement;
          }
        }
        if(!card) return null;
        var best=''; var len=0;
        var nodes=card.querySelectorAll('*');
        for(var i=0;i<nodes.length;i++){
          var n=nodes[i];
          if(n.childElementCount!==0) continue;
          var v=(n.textContent||'').trim();
          if(v && v.length>len && v.length<=80){ len=v.length; best=v; }
        }
        return best || null;
      }catch(e){ return null; }
    }

    // 扫描状态徽标式的短文本（精确匹配，免疫标题污染）：命中 needs_confirm/completed/failed 均上报。
    // 移动端首页没有 fiber 数据源，这是它感知「完成/失败/待确认」的主路径。
    function scanStatusLabels(){
      try{
        var els=document.querySelectorAll('*');
        var lim=0;
        // 大页面元素可达上万，限遍历上限避免每次 tick 都全页扫（标签层只是兜底）
        var max=els.length<8000?els.length:8000;
        for(var i=0;i<max && lim<3000;i++){
          var el=els[i];
          if(el.childElementCount!==0) continue;            // 仅叶子文本节点
          var txt=(el.textContent||'').trim();
          if(txt.length<2||txt.length>10) continue;
          // 官方待确认徽标（data-task-interaction-badge）即使在 button/span[title] 内也必须纳入
          if(el.closest && el.closest(SKIP_INTERACTIVE) && !el.closest('[data-task-interaction-badge]')) continue;
          if(el.closest && el.closest('[aria-hidden="true"]')) continue;
          var kind=statusLabelKind(txt);
          if(!kind) continue;
          lim++;
          var key=findKey(el);
          var detail=null;
          // 「需要确认」必须归一到**统一身份**（见 pendingIdentity）：文字标签层的天然 key 是
          // 「任务卡片」、kind 信息也缺失，而 fiber / 徽标层的 key 是 interactionId 且带 kind
          // → 不归一就会出现「同一交互两个 key」+「同一交互两种文案」。
          if(kind === 'needs_confirm'){
            var ident = pendingIdentity(el);
            key = pendingKeyOf(el, ident);
            if(ident.icKind) detail = ident.icKind;
          }
          var title=findCardTitle(el);
          applyObservation(key, kind, title, detail);
        }
      }catch(e){}
    }

    // ---------- 调度 ----------
    /**
     * 缺席对账：判定「阻塞交互已被处理」，进而让宿主撤掉那条常驻的「需要确认」通知。
     *
     * 为什么必须靠缺席：`ZCodeTaskRuntimeStatus` 里根本没有等待态，任务在等待确认期间
     * status 一直是 running —— 「等待确认 → 其他」这种转变**观察不到**；交互在 fiber / DOM 里
     * 是**直接消失**的。所以只能：某 key 曾被观察为 needs_confirm，而本轮所有来源都不再报它，
     * 且持续 ≥ [PEND_MISS_MS]（不能按轮数防抖：WS 每条消息都会驱动一次 tick，轮数没有时间意义）。
     *
     * 顺带承担原 gcBadge 的职责：key 消失时清掉状态机记忆与各来源的去重记忆，
     * 否则同一 interactionId 再次出现会被 srcLast 直接 continue 掉（漏报的另一个来源）。
     */
    function reconcilePending(){
      try{
        var now = Date.now();
        for(var k in state){
          var e = state[k];
          if(!e || e.kind !== 'needs_confirm'){ delete pendGoneSince[k]; continue; }
          if(roundSeen[k]){ delete pendGoneSince[k]; continue; }   // 本轮仍被任一来源观察到
          if(pendGoneSince[k] === undefined){ pendGoneSince[k] = now; continue; }  // 首次发现缺席，开始计时
          if(now - pendGoneSince[k] < PEND_MISS_MS) continue;
          // 刚提示过就「缺席」：先按抖动处理，重新计时（宁可晚撤几秒，也不要撤了又弹）。
          if(now - (lastFire['needs_confirm|' + k] || 0) < PEND_REARM_MS){ pendGoneSince[k] = now; continue; }
          // 交互已被处理（你在 App 里回答 / 授权了）
          requestEmit('confirm_cleared', k, e.title, e.detail);
          delete state[k];
          delete pendGoneSince[k];
          for(var s in srcLast){ if(srcLast[s] && srcLast[s][k] !== undefined) delete srcLast[s][k]; }
        }
      }catch(e){}
    }

    function tick(){
      tickCount++;
      roundSeen = {};   // 每轮重置「存活集合」（reconcilePending 据此判缺席）
      try{ ingestFrom('rows', extractRows()); }catch(e){}
      try{
        var ff=extractFromFibers();
        if(ff.meta) ingestFrom('meta',[ff.meta]);
        if(ff.pendings && ff.pendings.length) ingestFrom('pend', ff.pendings);
      }catch(e){}
      try{
        var bg=scanInteractionBadges();
        if(bg.length) ingestFrom('badge', bg);
      }catch(e){}
      // 文字标签扫描最贵（枚举全页元素）：降频到每 2 次 tick 一次，它只是兜底不是主路径
      if(tickCount % 2 === 1){ try{ scanStatusLabels(); }catch(e){} }
      reconcilePending();
    }

    // WebSocket hook：仅作为节拍器驱动 fiber/meta 复查；message 内容不解析
    try{
      var RealWS = window.WebSocket;
      function watchWs(ws){
        ws.addEventListener('message', tick);
        ws.addEventListener('open', function(){ lastReconnectAt = 0; });
      }
      var WsWrapper = function(url, proto){ var ws = proto===undefined? new RealWS(url): new RealWS(url,proto); watchWs(ws); return ws; };
      WsWrapper.prototype = RealWS.prototype;
      WsWrapper.CONNECTING=0;WsWrapper.OPEN=1;WsWrapper.CLOSING=2;WsWrapper.CLOSED=3;
      window.WebSocket = WsWrapper;
    }catch(e){}

    installTap();
    tick();          // 首轮：建立快照；已存在的“待确认”按 checkOnOpen 提醒一次
    primed = true;   // 之后只按转变量发事件

    // 供宿主（LunaShare）主动驱动：页面退到后台时 setInterval 会被 WebView/Chromium
    // 节流到 ≥1 分钟，而宿主 evaluateJavascript 是主动调用、不受节流影响 ——
    // 这是 App 切走 / 息屏后「待确认」仍能及时弹出的关键。
    window.__lunaZcodeTick = tick;
    setInterval(tick, 15000); // 页面侧兜底：前台且 WS 静默时仍能感知变化
    document.addEventListener('visibilitychange', function(){ if(document.visibilityState==='visible') tick(); });
  } catch(e){}
})();
"""

object ZcodeNotifier {

    private const val CHANNEL_DONE = "zcode_done"
    private const val CHANNEL_FAILED = "zcode_failed"
    private const val CHANNEL_ASK = "zcode_ask"
    private const val CHANNEL_NAME_DONE = "ZCode 生成完成"
    private const val CHANNEL_NAME_FAILED = "ZCode 任务失败"
    private const val CHANNEL_NAME_ASK = "ZCode 需要你确认"
    /**
     * 通知分组键。所有 zcode 通知（完成 / 失败 / 需要确认）都进这一组 →
     * 通知中心自动把它们**堆叠**成一组（顶部一张汇总卡，展开逐条可见），
     * 和别的 App 一样可以同时挂很多条，互不覆盖。
     */
    private const val NOTIF_GROUP = "zcode_events"

    /**
     * 「需要确认」同时最多保留几条。超出后撤掉**最旧**的那条。
     * 交互被处理时会精确撤销自己那条（见 confirm_cleared），正常永远触不到上限；
     * 这里只是防御性上限，避免页面异常时通知无限堆积把通知中心刷满。
     */
    private const val ASK_SLOT_MAX = 8
    private const val TAG = "ZcodeNotifier"

    /**
     * 全局唯一的通知 id 分配器，`ask` / `completed` / `failed` **共用**。
     *
     * ⚠️ 必须共用：历史实现里 ask 从 `0x5a02` 自增、completed/failed 从 `0x5a10` 自增，
     * 前者的自增区间会**撞进**后者的起始值 → 一条「生成完成」会把某条「需要确认」原地覆盖掉。
     */
    private val idSeq = AtomicInteger(0x5a10)
    private fun nextId(): Int = idSeq.incrementAndGet()

    // ---- 「需要确认」通知：**一个交互一条**，可同时挂多条（堆叠）----
    // 簿记逻辑抽到纯 Kotlin 的 [ZcodeAskSlots]（不依赖 Android API → 有 JVM 单测覆盖）。

    /**
     * 交互 key（通常是 `interactionId`）→ 挂起通知，**允许同时存在多条**，这是「堆叠通知」的核心。
     * 通知 id 取自 [nextId]，与「完成 / 失败」共用同一分配器，保证永不撞号。
     */
    private val askSlots = ZcodeAskSlots(ASK_SLOT_MAX) { nextId() }

    /**
     * 创建/迁移通知渠道。Android 8+ 必需；三条都高优 + 响铃 + 振动 + 呼吸灯，确保都会弹横幅。
     * 渠道不可原地改：若设备上已存在同 id 却是静默配置（无振动/无声，如早期版本），删后重建。
     */
    private fun ensureChannel(
        mgr: NotificationManager,
        id: String,
        name: String,
        desc: String,
        lightColor: Int,
        vibrationPattern: LongArray
    ) {
        val existing = mgr.getNotificationChannel(id)
        if (existing != null && (!existing.shouldVibrate() || existing.sound == null)) {
            mgr.deleteNotificationChannel(id)
        }
        if (mgr.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = desc
                setShowBadge(true)
                enableVibration(true)
                this.vibrationPattern = vibrationPattern
                enableLights(true)
                this.lightColor = lightColor
            }
            mgr.createNotificationChannel(ch)
        }
    }

    /**
     * 从通知进入 App 后显式撤销该条通知。
     * 系统 `setAutoCancel` 在部分 ROM（华为 MagicOS 等）上「Activity 已被唤到前台、只是
     * SINGLE_TOP 复用」时不会触发；由宿主自己取消，行为才确定。
     */
    fun cancel(context: Context, notifId: Int) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(notifId)
            Log.d(TAG, "cancel by tap id=$notifId")
        }
    }

    /**
     * 撤销当前**所有**挂着的「需要确认」通知，返回撤销条数。
     *
     * 用在设置里把「需要确认」提醒（或总开关）关掉之后：已经弹出来的那几条要一并收走，
     * 否则关掉开关后通知中心还挂着，用户会以为开关没生效。
     * 单条撤销（交互被处理）走 `confirm_cleared`，见 [notifyTaskEvent]。
     */
    fun clearPendingAsks(context: Context): Int {
        val ids = askSlots.clearAll()
        if (ids.isEmpty()) return 0
        val mgr = NotificationManagerCompat.from(context.applicationContext)
        ids.forEach { runCatching { mgr.cancel(it) } }
        Log.d(TAG, "clearPendingAsks → 撤销 ${ids.size} 条待确认通知")
        return ids.size
    }

    /** 三条渠道：完成(绿)/失败(红)/需要确认(橙)。 */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 清理最早单渠道版本（zcode_events）与旧双渠道（zcode_done/zcode_ask）残留
            if (mgr.getNotificationChannel("zcode_events") != null) mgr.deleteNotificationChannel("zcode_events")
            ensureChannel(
                mgr, CHANNEL_DONE, CHANNEL_NAME_DONE,
                "ZCode 网页生成完成时提醒", 0xFF4CAF50.toInt(), longArrayOf(0, 120)
            )
            ensureChannel(
                mgr, CHANNEL_FAILED, CHANNEL_NAME_FAILED,
                "ZCode 网页任务失败时提醒", 0xFFF44336.toInt(), longArrayOf(0, 160, 100, 160)
            )
            ensureChannel(
                mgr, CHANNEL_ASK, CHANNEL_NAME_ASK,
                "ZCode 网页需要你回答 / 确认时提醒", 0xFFFF9800.toInt(), longArrayOf(0, 220, 120, 220)
            )
        }
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            Log.w(TAG, "通知权限未授予，跳过 zcode 通知")
            return false
        }
        return true
    }

    /** 通知点击 → 回到 MainActivity 并落到 Link Tab（无论当前在哪个 Tab）。 */
    private fun contentIntent(context: Context, notifId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ZCODE_EXTRA_OPEN_LINK_TAB, true)
            putExtra(ZCODE_EXTRA_NOTIF_ID, notifId)
        }
        // requestCode 必须用通知 id：全部用 0 + FLAG_UPDATE_CURRENT 时，后建的通知会覆盖
        // 先建那条的 extras（于是点「已完成」却取消掉「需要确认」）。
        return PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 任务状态转变 → 系统通知。settings 为该页面的通知行为配置（页面 ID 由调用方解析）。
     *
     * 页面内的 JS 状态机已按用户配置做了第一层过滤（总开关 / 事件开关 / 可见性 / 严格模式 / 冷却），
     * 这里再做一次 Kotlin 侧兜底（防止脚本被替换或配置在事件飞行途中被改动），
     * 并负责「需要确认是否常驻」「是否带声音」这类只有原生通知才有的能力。
     *
     * @param kind completed / failed / needs_confirm
     */
    fun notifyTaskEvent(
        context: Context,
        kind: String,
        taskId: String,
        title: String,
        from: String,
        to: String,
        settings: NotifySettings = NotifySettings(),
    ) {
        // 「交互已被处理」是撤销指令，不是通知事件 → 放在所有开关/静音判断**之前**。
        // 你在 App 里回答时页面必然处于「可见」状态，若走「页面可见时静音」那条路就永远撤不掉。
        if (kind == "confirm_cleared") {
            // 精确撤销**被处理的那一条**：key 就是交互身份，一交互一槽 →
            // 交互 A 被处理只会撤掉 A 那条，不会误伤同时挂着的 B/C（这正是过去单槽实现的经典事故）。
            val id = askSlots.clear(taskId)
            if (id == 0) {
                Log.d(TAG, "confirm_cleared 忽略：key=$taskId 不在挂起列表里（已被点掉 / 已撤销）")
                return
            }
            runCatching {
                NotificationManagerCompat.from(context.applicationContext).cancel(id)
            }
            // 槽已移除 → 同一个 key 若之后再次出现（新的一次阻塞交互），会按新交互重新提示。
            Log.d(TAG, "confirm_cleared → 撤销「需要确认」通知 id=$id key=$taskId（剩余 ${askSlots.pendingCount()} 条）")
            return
        }
        if (!settings.enabled) {
            Log.d(TAG, "通知总开关关闭，跳过 kind=$kind")
            return
        }
        val allowed = when (kind) {
            "needs_confirm" -> settings.notifyOnConfirm
            "failed" -> settings.notifyOnFailed
            "completed" -> settings.notifyOnCompleted
            else -> true
        }
        if (!allowed) {
            Log.d(TAG, "该事件类别已关闭，跳过 kind=$kind")
            return
        }

        // 「待确认」经常拿不到任务标题（fiber 里的 pendingInteraction 只带 interactionId/kind），
        // 这时按交互类型给文案，比千篇一律的「未命名任务」有用得多。
        val taskTitle = when {
            title.isNotBlank() -> title
            kind == "needs_confirm" && to == "permission" -> "有工具调用在等你授权"
            kind == "needs_confirm" -> "Agent 在等你的回答"
            else -> "未命名任务"
        }
        when (kind) {
            "failed" -> notify(
                context, CHANNEL_FAILED, nextId(), R.drawable.ic_notify_fail,
                "✗ ZCode 任务失败", taskTitle, sound = settings.sound
            )
            "needs_confirm" -> postAsk(context, taskId, taskTitle, to, settings)
            "completed" -> notify(
                context, CHANNEL_DONE, nextId(), R.drawable.ic_notify_done,
                "✓ ZCode 生成完成", taskTitle, sound = settings.sound
            )
            // 未知类别一律忽略：这里若用 else 兜底发「生成完成」，将来新增事件类别会静默误报。
            else -> Log.d(TAG, "未知事件类别，忽略 kind=$kind")
        }
    }

    /**
     * 发/更新一条「需要确认」通知 —— **一个交互一条，可同时挂很多条**（堆叠，互不覆盖）。
     *
     * 为什么是「一 key 一槽」而不是过去的「全局单槽」：
     *  - 单槽时两件待确认会互相覆盖，只有最新那条在通知中心可见；
     *  - 单槽还必须靠时间窗口（曾经的 ASK_STALE_MS）去猜「新 key 到底是新交互还是同一交互的另一路
     *    key」，猜错就会把新的静默吞掉（漏提醒）或把旧的反复重发（一直弹）。
     * 现在 key 就是身份，各槽独立 id：既不覆盖，也不需要猜。
     *
     * 每个槽的行为：
     *  - 同 key、正文未变 → 什么都不做（连 `notify()` 都不调：调用本身在通知已被你划掉时
     *    会让它重新响一次，也就等于尊重了你的划掉）；
     *  - 同 key、正文变了 → **原地更新**那条通知（同 id + `setOnlyAlertOnce(true)` → 不重新响铃）；
     *  - 同 key、本次没带出类型（正文退化成笼统说法）→ 保留已显示的具体文案，不覆盖；
     *  - 新 key → 新开一条（新 id → 会响一次，新的交互不会被静默吞掉）。
     *
     * 撤销由页面侧的 `confirm_cleared`（交互被处理 / 消失）按 key 精确完成；见 [notifyTaskEvent]。
     */
    private fun postAsk(
        context: Context,
        key: String,
        text: String,
        icKind: String,
        settings: NotifySettings,
    ) {
        // 常驻（默认）：不设超时，一直留到你点掉 / 从它进入 App；
        // 关闭常驻：10 分钟兜底自动撤销（两种情况下点按都会立刻消失）
        val timeoutMs = if (settings.requireInteractionConfirm) 0L else 10 * 60 * 1000L
        when (val d = askSlots.observe(key, text, icKind, SystemClock.elapsedRealtime())) {
            ZcodeAskSlots.Decision.Ignore -> {
                Log.d(TAG, "ask 忽略本次上报 key=$key（文案未变 / 本次无类型信息）")
            }
            is ZcodeAskSlots.Decision.Update -> {
                Log.d(TAG, "ask 原地更新 id=${d.id} key=$key →「${d.text}」")
                notify(
                    context, CHANNEL_ASK, d.id, R.drawable.ic_notify_ask,
                    "⚠ ZCode 需要你确认", d.text, timeoutMs = timeoutMs, sound = settings.sound
                )
            }
            is ZcodeAskSlots.Decision.Create -> {
                if (d.evictedId != 0) {
                    runCatching {
                        NotificationManagerCompat.from(context.applicationContext).cancel(d.evictedId)
                    }
                    Log.d(TAG, "ask 超出上限 $ASK_SLOT_MAX，撤掉最旧一条 id=${d.evictedId}")
                }
                Log.d(TAG, "ask 新交互 key=$key kind=$icKind id=${d.id}（同时挂起 ${askSlots.pendingCount()} 条）")
                notify(
                    context, CHANNEL_ASK, d.id, R.drawable.ic_notify_ask,
                    "⚠ ZCode 需要你确认", d.text, timeoutMs = timeoutMs, sound = settings.sound
                )
            }
        }
    }

    private fun notify(
        context: Context,
        channel: String,
        id: Int,
        icon: Int,
        title: String,
        text: String,
        timeoutMs: Long = 0L,
        sound: Boolean = true
    ) {
        try {
            val appCtx = context.applicationContext
            ensureChannels(appCtx)
            if (!canNotify(appCtx)) return
            // 用户在「通知行为」里关掉声音：改用静音渠道（渠道不可原地改音，只能走另一条渠道）
            val useChannel =
                if (!sound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) silentChannel(appCtx, channel)
                else channel
            val builder = NotificationCompat.Builder(appCtx, useChannel)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                // 只提醒一次：同一条通知（同 id）**被更新**时至多响一次。
                // 没有它，任何重复上报都会让通知重新响铃/振动/弹横幅 ——「一条通知一直弹」的直接原因。
                .setOnlyAlertOnce(true)
                // 恒为 true：点通知（含「从通知中心点它回到 App」）后自动消失。
                // 「常驻」不再用 autoCancel 表达 —— 那会让点按也留痕，正是「进了 App 通知还挂着」的原因。
                .setAutoCancel(true)
                .setContentIntent(contentIntent(appCtx, id))
                // 全部进同一组 → 通知中心把它们堆叠成一组（顶部汇总卡「N 条通知」，展开逐条可见），
                // 于是「任务完成 × 3 + 待确认 × 2」可以同时在，而不是互相覆盖。
                .setGroup(NOTIF_GROUP)
                // 组内每条各自提醒：不加这句，Android 8+ 在组已提醒过之后会**静默**后续子通知
                // → 第二件「待确认」就悄悄出现，你根本不会知道。
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            if (!sound) builder.setSilent(true)
            // 「常驻」= 不设超时（留到你点掉 / 从它进入 App）；非常驻 = 超时兜底自动撤销
            if (timeoutMs > 0L) builder.setTimeoutAfter(timeoutMs)
            NotificationManagerCompat.from(appCtx).notify(id, builder.build())
            Log.d(TAG, "notify id=$id channel=$useChannel title=$title")
        } catch (e: Exception) {
            Log.e(TAG, "notify failed", e)
        }
    }

    /**
     * 取（并按需创建）与原渠道同名的静音副本渠道：`<id>_silent`。
     * Android 通知渠道的音量/振动创建后不可改，因此「静音」必须走独立渠道。
     */
    private fun silentChannel(context: Context, baseId: String): String {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = "${baseId}_silent"
        if (mgr.getNotificationChannel(id) == null) {
            val name = when (baseId) {
                CHANNEL_DONE -> "$CHANNEL_NAME_DONE（静音）"
                CHANNEL_FAILED -> "$CHANNEL_NAME_FAILED（静音）"
                else -> "$CHANNEL_NAME_ASK（静音）"
            }
            val ch = NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "已在「通知行为」中关闭声音"
                setShowBadge(true)
                enableVibration(false)
                setSound(null, null)
                enableLights(true)
            }
            mgr.createNotificationChannel(ch)
        }
        return id
    }
}
