package com.lunashare.app.link

/**
 * 「需要确认」通知的槽位簿记 —— **纯逻辑，不碰任何 Android API**，因此可被 JVM 单测直接覆盖
 * （见 `app/src/test/java/com/lunashare/app/link/ZcodeAskSlotsTest.kt`）。
 *
 * 语义：**一个交互一条通知**（key = 交互身份，通常是 `interactionId`），
 * 于是「任务 A 等授权 + 任务 B 等回答 + 任务 C 等选择」可以同时在通知中心堆叠，互不覆盖。
 *
 * 为什么不用「全局单槽」：单槽时第二条待确认会把第一条顶掉，只有最新那条可见；
 * 而且单槽必须靠时间窗口去猜「新 key 是同一个交互的另一路 key，还是新交互」，
 * 猜错就漏提醒（把新的静默吞掉）或一直弹（把旧的重复重发）。现在 key 就是身份，不需要猜。
 *
 * 通知 id 由外部注入（[idAllocator]），保证与「完成 / 失败」通知共用同一个分配器、永不撞号。
 */
internal class ZcodeAskSlots(
    private val maxSlots: Int = 8,
    private val idAllocator: () -> Int,
) {

    /** 一条挂起的「需要确认」通知。 */
    internal class Slot(
        /** 该交互专属的通知 id。 */
        val id: Int,
        /** 最近一次上报的正文。 */
        var text: String,
        /** 交互类型（`permission` / `userInput`，可能为空）。 */
        var kind: String,
        /** 最近一次被页面上报的时间戳（由调用方提供，便于测试与统一时钟）。 */
        var seenAt: Long,
    )

    /** 一次上报考量的结论。 */
    internal sealed class Decision {
        /** 什么都不做：完全重复的上报，或本次缺少类型信息而选择保留原（更具体的）文案。 */
        internal object Ignore : Decision()

        /** 原地更新同一条通知（id 不变 → 系统视为同一条，配合 onlyAlertOnce 不会重新响铃）。 */
        internal class Update(val id: Int, val text: String) : Decision()

        /**
         * 新开一条通知（新 id = 系统视为新记录 → 会响一次，新的交互不会被静默吞掉）。
         * [evictedId] 非 0 表示同时要撤掉被挤出上限的那条最旧通知。
         */
        internal class Create(val id: Int, val text: String, val evictedId: Int) : Decision()
    }

    private val slots = LinkedHashMap<String, Slot>()
    private val lock = Any()

    /** 当前挂起条数（供日志与单测断言）。 */
    internal fun pendingCount(): Int = synchronized(lock) { slots.size }

    /** 当前挂起的交互 key（测试用）。 */
    internal fun pendingKeys(): List<String> = synchronized(lock) { slots.keys.toList() }

    /** 某 key 对应的通知 id，不存在返回 0（测试用）。 */
    internal fun idOf(key: String): Int = synchronized(lock) { slots[key]?.id ?: 0 }

    /**
     * 页面报告一个「需要确认」交互**仍然存在 / 刚刚出现**。
     *
     * 决策表：
     *  - 已有同 key 且正文未变 → [Decision.Ignore]（连 `notify()` 都不调：重复调用在通知已被
     *    用户划掉时会让它重新响一次，因此「忽略」同时也是「尊重用户的划掉」）；
     *  - 已有同 key、正文变了、但本次**没带出类型** → [Decision.Ignore]（保留已显示的具体文案，
     *    否则「有工具调用在等你授权」会自己退化成笼统的「Agent 在等你的回答」）；
     *  - 已有同 key、正文变了 → [Decision.Update]（原地更新同一条，不响铃）；
     *  - 新 key → [Decision.Create]（新开一条；超出 [maxSlots] 时顺带撤掉最旧的那条）。
     *
     * @param text 通知正文（调用方已算好：优先任务标题，缺失时按 [kind] 兜底）
     * @param kind `permission` / `userInput`，可能为空
     * @param now  单调时钟时间戳（`SystemClock.elapsedRealtime()`）
     */
    internal fun observe(key: String, text: String, kind: String, now: Long): Decision {
        synchronized(lock) {
            val slot = slots[key]
            if (slot != null) {
                slot.seenAt = now
                if (kind.isNotBlank()) slot.kind = kind
                if (slot.text == text) return Decision.Ignore
                if (kind.isBlank() && slot.kind.isNotBlank()) return Decision.Ignore
                slot.text = text
                return Decision.Update(slot.id, text)
            }
            val id = idAllocator()
            slots[key] = Slot(id = id, text = text, kind = kind, seenAt = now)
            var evictedId = 0
            if (slots.size > maxSlots) {
                val oldest = slots.keys.firstOrNull()
                if (oldest != null && oldest != key) {
                    evictedId = slots.remove(oldest)?.id ?: 0
                }
            }
            return Decision.Create(id, text, evictedId)
        }
    }

    /**
     * 交互已被处理 / 已从页面消失 → 返回要撤销的通知 id；没有对应槽则返回 0。
     *
     * **精确撤销**：只撤被处理的那一条，绝不动同时挂着的其它条
     * （历史上全局单槽的实现会把新交互的提示误撤掉，导致提醒彻底丢失）。
     */
    internal fun clear(key: String): Int = synchronized(lock) { slots.remove(key)?.id ?: 0 }

    /** 全部撤销（例如通知总开关被关掉、共享停止）。 */
    internal fun clearAll(): List<Int> = synchronized(lock) {
        val ids = slots.values.map { it.id }
        slots.clear()
        ids
    }
}
