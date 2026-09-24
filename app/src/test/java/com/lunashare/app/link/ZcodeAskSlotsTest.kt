package com.lunashare.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ZcodeAskSlots] 的行为契约 —— 也就是「堆叠通知」这件事的正确性：
 * 多个待确认可以**同时**存在、互不覆盖；交互被处理时**只撤自己那条**；新的交互一定会响。
 *
 * 这些断言直接对应真实事故：
 *  - 过去全局单槽 → 第二条待确认把第一条顶掉（只剩最新一条可见）；
 *  - 过去按时间窗口猜「新 key 是不是同一交互」→ 新的被静默吞掉 / 旧的反复重发（一直弹）；
 *  - 撤销时不按键匹配 → 新交互的提示被旧交互的撤销误杀，提示彻底丢失。
 */
class ZcodeAskSlotsTest {

    /** 递增 id 分配器：固定起始值，便于断言「新开一条 = 新 id」。 */
    private class Ids(start: Int = 0x5a10) {
        private var n = start
        fun next(): Int = ++n
    }

    private fun store(maxSlots: Int = 8, ids: Ids = Ids()) = ZcodeAskSlots(maxSlots) { ids.next() }

    @Test
    fun `多个交互同时待确认时各占一条通知，互不覆盖`() {
        val s = store()
        val a = s.observe("ia", "任务A 等授权", "permission", 1_000) as ZcodeAskSlots.Decision.Create
        val b = s.observe("ib", "任务B 等回答", "userInput", 1_100) as ZcodeAskSlots.Decision.Create
        val c = s.observe("ic", "任务C 等选择", "", 1_200) as ZcodeAskSlots.Decision.Create

        assertNotEquals(a.id, b.id)
        assertNotEquals(b.id, c.id)
        assertNotEquals(a.id, c.id)
        assertEquals(3, s.pendingCount())
        assertEquals(listOf("ia", "ib", "ic"), s.pendingKeys())
    }

    @Test
    fun `同一交互重复上报不产生新通知（避免重复响铃）`() {
        val s = store()
        val first = s.observe("ia", "任务A 等授权", "permission", 1_000) as ZcodeAskSlots.Decision.Create
        repeat(5) { i ->
            val d = s.observe("ia", "任务A 等授权", "permission", 1_500 + i * 100L)
            assertEquals(ZcodeAskSlots.Decision.Ignore, d)
        }
        assertEquals(1, s.pendingCount())
        assertEquals(first.id, s.idOf("ia"))
    }

    @Test
    fun `同一交互文案变化时原地更新同一条通知（不响铃、不新建）`() {
        val s = store()
        val first = s.observe("ia", "任务A", "userInput", 1_000) as ZcodeAskSlots.Decision.Create
        val d = s.observe("ia", "任务A 等你的回答", "userInput", 2_000) as ZcodeAskSlots.Decision.Update

        assertEquals(first.id, d.id)
        assertEquals("任务A 等你的回答", d.text)
        assertEquals(1, s.pendingCount())
    }

    @Test
    fun `本次上报没带交互类型时保留已显示的具体文案（防止方案退化）`() {
        val s = store()
        s.observe("ia", "有工具调用在等你授权", "permission", 1_000)
        // 页面另一路来源没带出 kind → 正文会退化成笼统说法「Agent 在等你的回答」→ 必须忽略
        val d = s.observe("ia", "Agent 在等你的回答", "", 1_800)
        assertEquals(ZcodeAskSlots.Decision.Ignore, d)
        // 但带出具体类型时，正文更新照常生效
        val ok = s.observe("ia", "任务A 等授权", "permission", 2_600) as ZcodeAskSlots.Decision.Update
        assertEquals("任务A 等授权", ok.text)
    }

    @Test
    fun `撤销只撤被处理的那一条，同时挂着的其它条不受影响`() {
        val s = store()
        val a = s.observe("ia", "任务A", "permission", 1_000) as ZcodeAskSlots.Decision.Create
        val b = s.observe("ib", "任务B", "userInput", 1_100) as ZcodeAskSlots.Decision.Create

        assertEquals(a.id, s.clear("ia"))
        assertEquals(1, s.pendingCount())
        assertEquals(0, s.idOf("ia"))
        assertEquals(b.id, s.idOf("ib"))   // B 完好
    }

    @Test
    fun `撤销未知 key 返回 0（不误伤任何人）`() {
        val s = store()
        s.observe("ia", "任务A", "permission", 1_000)
        assertEquals(0, s.clear("不存在"))
        assertEquals(1, s.pendingCount())
    }

    @Test
    fun `交互被处理后同一 key 再次出现按新交互重新提示（新 id = 会再响一次）`() {
        val s = store()
        val first = s.observe("ia", "任务A 等授权", "permission", 1_000) as ZcodeAskSlots.Decision.Create
        assertEquals(first.id, s.clear("ia"))
        val again = s.observe("ia", "任务A 等授权", "permission", 2_000) as ZcodeAskSlots.Decision.Create
        assertNotEquals(first.id, again.id)
        assertEquals(1, s.pendingCount())
    }

    @Test
    fun `超过上限时撤掉最旧一条，并为新的交互让出位置`() {
        val s = store(maxSlots = 3)
        val a = s.observe("ia", "A", "", 1) as ZcodeAskSlots.Decision.Create
        s.observe("ib", "B", "", 2)
        s.observe("ic", "C", "", 3)
        assertEquals(3, s.pendingCount())

        val d = s.observe("id", "D", "", 4) as ZcodeAskSlots.Decision.Create
        assertEquals(a.id, d.evictedId)                 // 最旧的 A 被挤出
        assertEquals(3, s.pendingCount())
        assertEquals(0, s.idOf("ia"))
        assertEquals(d.id, s.idOf("id"))
    }

    @Test
    fun `更新已挂起的条目不触发淘汰（保持条数不变）`() {
        val s = store(maxSlots = 2)
        s.observe("ia", "A", "userInput", 1)
        s.observe("ib", "B", "userInput", 2)
        repeat(3) { i ->
            val d = s.observe("ia", "A$i", "userInput", 10L + i)
            assertTrue(d is ZcodeAskSlots.Decision.Update)
        }
        assertEquals(2, s.pendingCount())
    }

    @Test
    fun `clearAll 一次撤销全部（用于总开关关闭等场景）`() {
        val s = store()
        val a = s.observe("ia", "A", "", 1) as ZcodeAskSlots.Decision.Create
        val b = s.observe("ib", "B", "", 2) as ZcodeAskSlots.Decision.Create
        assertEquals(listOf(a.id, b.id), s.clearAll())
        assertEquals(0, s.pendingCount())
    }

    @Test
    fun `不同交互的同名任务也能各占一条（key 而非文案决定身份）`() {
        val s = store()
        val a = s.observe("ia", "有工具调用在等你授权", "permission", 1_000) as ZcodeAskSlots.Decision.Create
        val b = s.observe("ib", "有工具调用在等你授权", "permission", 1_050) as ZcodeAskSlots.Decision.Create
        assertNotEquals(a.id, b.id)
        assertEquals(2, s.pendingCount())
    }
}
