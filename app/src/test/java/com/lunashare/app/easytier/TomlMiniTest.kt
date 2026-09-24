package com.lunashare.app.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TomlMini] 的行为契约：把用户导入的 EasyTier TOML 准确解析进可编辑表单。
 * 对应真实事故：导入的 TOML 只做了只读预览，网络名/密码/节点想改一个字都做不到。
 */
class TomlMiniTest {

    @Test
    fun `parse full easytier config`() {
        val toml = """
            # 主配置
            instance_name = "default"
            hostname = "my-node"          # 行内注释
            ipv4 = "10.126.126.2/24"
            dhcp = false
            listeners = ["tcp://0.0.0.0:11010", "udp://0.0.0.0:11010"]

            [network_identity]
            network_name = "abc"
            network_secret = "xyz"

            [[peer]]
            uri = "tcp://39.98.83.46:51012"

            [[peer]]
            uri = "wss://config-server.example.com"
            name = "backup"
        """.trimIndent()

        val doc = TomlMini.parse(toml)
        val cfg = TomlMini.toEasyTierConfig(doc)

        assertEquals("abc", cfg.networkName)
        assertEquals("xyz", cfg.networkSecret)
        assertEquals("my-node", cfg.hostname)
        assertEquals(listOf("tcp://39.98.83.46:51012", "wss://config-server.example.com"), cfg.peers)
        assertEquals("10.126.126.2/24", cfg.virtualIp)

        // instance_name 是已知键（LunaShare 固定生成），listeners/name 是未映射键
        assertTrue("listeners" in doc.unknown)
        assertTrue("peer.name" in doc.unknown)
        assertTrue("instance_name" !in doc.unknown)
    }

    @Test
    fun `dhcp true means empty virtual ip`() {
        val doc = TomlMini.parse("dhcp = true\n[network_identity]\nnetwork_name = \"n1\"")
        val cfg = TomlMini.toEasyTierConfig(doc)
        assertEquals("", cfg.virtualIp)
        assertEquals("n1", cfg.networkName)
    }

    @Test
    fun `string escapes are decoded`() {
        val doc = TomlMini.parse("hostname = \"a\\\"b\\\\c\"")
        assertEquals("a\"b\\c", doc.root["hostname"])
    }

    @Test
    fun `comments with hash inside quotes are kept`() {
        val doc = TomlMini.parse("hostname = \"node#1\" # real comment")
        assertEquals("node#1", doc.root["hostname"])
    }

    @Test
    fun `nested table path and dotted header`() {
        val doc = TomlMini.parse("[a.b]\nx = 1\n[c]\ny = true")
        val a = doc.root["a"] as TomlMini.Table
        val b = a["b"] as TomlMini.Table
        assertEquals(1L, b["x"])
        assertEquals(true, doc.root["c"].let { (it as TomlMini.Table)["y"] })
    }

    @Test
    fun `multiline array is merged into one logical line`() {
        val toml = """
            listeners = [
                "tcp://0.0.0.0:11010",   # tcp
                "udp://0.0.0.0:11010",
                "wg://0.0.0.0:11011",
            ]

            [network_identity]
            network_name = "n2"
        """.trimIndent()
        val doc = TomlMini.parse(toml)
        val arr = doc.root["listeners"] as List<*>
        assertEquals(3, arr.size)
        assertEquals("n2", TomlMini.toEasyTierConfig(doc).networkName)
    }

    @Test
    fun `ipv6 uri in array is kept intact`() {
        val doc = TomlMini.parse("""listeners = ["tcp://[::1]:11010"]""")
        assertEquals("tcp://[::1]:11010", (doc.root["listeners"] as List<*>)[0])
    }

    @Test
    fun `error line number is reported`() {
        try {
            TomlMini.parse("ok = 1\nbroken line without eq")
            throw AssertionError("expected TomlError")
        } catch (e: TomlMini.TomlError) {
            assertTrue(e.message!!.contains("第 2 行"))
        }
    }
}
