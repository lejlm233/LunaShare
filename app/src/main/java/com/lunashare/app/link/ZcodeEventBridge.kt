package com.lunashare.app.link

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * 注入 zcode 网页的 JS Bridge（window.LunaZcode）。
 *
 * 页面内 [ZCODE_WATCHER_JS] 采用与「ZCode 远程任务通知」Chrome 扩展一致的多层检测：
 *  1. 数据层 tap：钩住 JSON.parse 捕获中继 WebSocket 下发的真实任务对象（taskId + displayStatus/status）；
 *  2. fiber 层：读取桌面任务行的 React fiber props（displayStatus）；
 *  3. meta 层：读取移动端会话视图的 resolvedActiveTaskMeta；
 *  4. 标签层：精确匹配状态徽标短文本。
 * 四个来源按来源独立去重后汇入统一的「状态机」，仅在**转变**时（运行中→完成/失败、任意→需要确认）
 * 经本 Bridge 的 [onTaskEvent] 回调 Kotlin 层，再由 [ZcodeNotifier] 发系统通知。
 *
 * 通知行为（哪类事件提醒 / 是否常驻 / 是否静音等）按**页面来源**存于 [NotifySettingsStore]，
 * 每次事件都现场读取，因此用户在「通知行为」弹窗里改完立即生效，无需重载页面。
 *
 * 仅对 zcode 页面 addJavascriptInterface，且持有 applicationContext，避免 Activity 泄漏。
 */
class ZcodeEventBridge(
    private val context: Context,
    private val pageUrl: String,
) {

    /** 该页面对应的通知配置 key（同域共享一份配置） */
    private val pageKey: String = NotifySettingsStore.keyOf(pageUrl)

    /**
     * 任务状态转变回调（由页面内状态机在「真正的转变量」上触发）。
     * @param kind   事件类别：completed / failed / needs_confirm / confirm_cleared
     *               （confirm_cleared = 那条待确认的交互已被处理，撤销常驻通知，本身不出通知）
     * @param taskId zcode 任务 id（如 sess_xxx）或阻塞交互的 interactionId
     * @param title  任务标题（可能为空）
     * @param from   转变前状态（原始 displayStatus，可能为空）
     * @param to     转变后状态（原始 displayStatus）或交互类型 permission / userInput
     */
    @JavascriptInterface
    fun onTaskEvent(kind: String, taskId: String, title: String, from: String, to: String) {
        Log.d("ZcodeBridge", "task event kind=$kind task=$taskId title=$title from=$from to=$to")
        val settings = NotifySettingsStore(context).getOrDefault(pageKey)
        ZcodeNotifier.notifyTaskEvent(context, kind, taskId, title, from, to, settings)
    }
}
