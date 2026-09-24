package com.lunashare.app.easytier

/**
 * 极简 TOML 解析器（纯 Kotlin，可单测）。
 *
 * 只覆盖 EasyTier 配置用到的子集：
 * - 顶层 `key = value`（字符串 / 布尔 / 整数 / 浮点 / 同行数组）
 * - `[table]` 与 `[a.b.c]` 嵌套表
 * - `[[array]]` 表数组
 * - `#` 注释（引号外生效）
 * - 基本字符串 "..."（含 \\ \" \n \t 转义）、literal 字符串 '...'
 *
 * 未识别的 key（在 root/任意表内）会记录「表路径.键」到 [Doc.unknown]，供 UI 提示哪些
 * 字段未被解析进表单（连接时若用表单模式生成 TOML，这些字段会被丢弃）。
 */
object TomlMini {

    /** 值类型：String / Boolean / Long / Double / List<Any> / Table */
    class Table(val path: String) {
        val entries = LinkedHashMap<String, Any>()
        operator fun get(k: String): Any? = entries[k]
        operator fun set(k: String, v: Any) { entries[k] = v }
    }

    class Doc(val root: Table = Table("")) {
        /** 未映射进 EasyTierConfig 的键路径（如 listeners / rpc_portal），供 UI 提示。 */
        val unknown = LinkedHashSet<String>()
    }

    // ---------------- 解析入口 ----------------

    /**
     * 把物理行合并为逻辑行：引号外方括号未闭合（多行数组）时继续吞并下一行。
     * 返回 (起始行号 1-based, 逻辑行)。
     */
    private fun logicalLines(text: String): List<Pair<Int, String>> {
        // 先逐行去注释（注释只在单行内生效），再按括号深度合并多行数组
        val lines = text.lines().map { stripComment(it) }
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (i < lines.size) {
            val startLine = i + 1
            var logical = lines[i]
            while (bracketDepth(logical) > 0 && i + 1 < lines.size) {
                i++
                logical = "$logical\n${lines[i]}"
            }
            out.add(startLine to logical)
            i++
        }
        return out
    }

    /** 引号外的 [ 与 ] 之差（>0 表示数组/表未闭合）。 */
    private fun bracketDepth(line: String): Int {
        var depth = 0
        var inBasic = false; var inLiteral = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && inBasic -> i++
                c == '"' && !inLiteral -> inBasic = !inBasic
                c == '\'' && !inBasic -> inLiteral = !inLiteral
                c == '[' && !inBasic && !inLiteral -> depth++
                c == ']' && !inBasic && !inLiteral -> depth--
            }
            i++
        }
        return depth
    }

    fun parse(text: String): Doc {
        val doc = Doc()
        var current = doc.root
        for ((lineNo, raw) in logicalLines(text)) {
            val line = stripComment(raw).trim()
            if (line.isEmpty()) continue
            try {
                when {
                    line.startsWith("[[") && line.endsWith("]]") -> {
                        val path = line.substring(2, line.length - 2).trim()
                        current = appendArrayTable(doc, doc.root, splitPath(path), path)
                    }
                    line.startsWith("[") && line.endsWith("]") -> {
                        val path = line.substring(1, line.length - 2 + 1).trim()
                        current = getOrCreateTable(doc, doc.root, splitPath(path))
                    }
                    else -> {
                        val eq = findTopLevelEq(line)
                        if (eq < 0) throw TomlError("无法识别的行: ${line.take(40)}")
                        val key = line.substring(0, eq).trim().removeSurrounding("\"")
                        val value = parseValue(doc, line.substring(eq + 1).trim())
                        current[key] = value
                    }
                }
            } catch (e: TomlError) {
                throw TomlError("第 $lineNo 行: ${e.message}")
            }
        }
        return doc
    }

    class TomlError(msg: String) : Exception(msg)

    // ---------------- 行级工具 ----------------

    /** 去掉引号外的 # 注释。 */
    private fun stripComment(line: String): String {
        var inBasic = false; var inLiteral = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && inBasic -> i++ // 跳过转义
                c == '"' && !inLiteral -> inBasic = !inBasic
                c == '\'' && !inBasic -> inLiteral = !inLiteral
                c == '#' && !inBasic && !inLiteral -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    /** 找引号外的第一个 '='。 */
    private fun findTopLevelEq(line: String): Int {
        var inBasic = false; var inLiteral = false
        line.forEachIndexed { i, c ->
            when {
                c == '\\' && inBasic -> {}
                c == '"' && !inLiteral -> inBasic = !inBasic
                c == '\'' && !inBasic -> inLiteral = !inLiteral
                c == '=' && !inBasic && !inLiteral -> return i
            }
        }
        return -1
    }

    private fun splitPath(path: String): List<String> =
        path.split('.').map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }

    // ---------------- 表导航 ----------------

    private fun getOrCreateTable(doc: Doc, parent: Table, path: List<String>, full: String = path.joinToString(".")): Table {
        var cur = parent
        var curPath = parent.path
        path.forEachIndexed { i, seg ->
            curPath = if (curPath.isEmpty()) seg else "$curPath.$seg"
            val next = cur[seg]
            cur = when (next) {
                is Table -> next
                null -> Table(curPath).also { cur[seg] = it }
                is MutableList<*> -> {
                    // [a] 之前有 [[a]]：取最后一个元素（TOML 规范允许 [a.b] 引用数组最后一项）
                    val last = next.lastOrNull()
                    if (last is Table) last else throw TomlError("表冲突: $full")
                }
                else -> throw TomlError("表冲突: $full")
            }
        }
        return cur
    }

    private fun appendArrayTable(doc: Doc, parent: Table, path: List<String>, full: String): Table {
        require(path.isNotEmpty()) { throw TomlError("空表名: $full") }
        val seg = path.last()
        val parentTbl = getOrCreateTable(doc, parent, path.dropLast(1))
        val arr = (parentTbl[seg] as? MutableList<Any>) ?: mutableListOf<Any>().also { parentTbl[seg] = it }
        return Table(full).also { arr.add(it) }
    }

    // ---------------- 值解析 ----------------

    private fun parseValue(doc: Doc, s0: String): Any {
        val s = s0.trim()
        if (s.isEmpty()) throw TomlError("缺少值")
        when (s.first()) {
            '"' -> return parseBasicString(s, doubleQuoted = true)
            '\'' -> return parseBasicString(s, doubleQuoted = false)
            '[' -> return parseInlineArray(doc, s)
            else -> {}
        }
        val token = s.split(Regex("\\s+"))[0]
        return when {
            token == "true" -> true
            token == "false" -> false
            token.toLongOrNull() != null -> token.toLong()
            token.toDoubleOrNull() != null -> token.toDouble()
            else -> throw TomlError("不支持的值: ${token.take(30)}")
        }
    }

    private fun parseBasicString(s: String, doubleQuoted: Boolean): String {
        val quote = if (doubleQuoted) '"' else '\''
        val sb = StringBuilder()
        var i = 1
        while (i < s.length) {
            val c = s[i]
            if (c == quote) return sb.toString()
            if (doubleQuoted && c == '\\' && i + 1 < s.length) {
                i++
                when (val e = s[i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    '"' -> sb.append('"'); '\\' -> sb.append('\\')
                    'u' -> { // \uXXXX
                        val hex = s.substring(i + 1, (i + 5).coerceAtMost(s.length))
                        sb.append(hex.toInt(16).toChar()); i += 4
                    }
                    else -> sb.append(e)
                }
            } else sb.append(c)
            i++
        }
        throw TomlError("字符串未闭合")
    }

    private fun parseInlineArray(doc: Doc, s: String): List<Any> {
        require(s.startsWith('['))
        var depth = 0
        var end = -1
        var inBasic = false; var inLiteral = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && inBasic -> i++
                c == '"' && !inLiteral -> inBasic = !inBasic
                c == '\'' && !inBasic -> inLiteral = !inLiteral
                c == '[' && !inBasic && !inLiteral -> depth++
                c == ']' && !inBasic && !inLiteral -> { depth--; if (depth == 0) { end = i; break } }
            }
            i++
        }
        if (end < 0) throw TomlError("数组未闭合")
        val inner = s.substring(1, end).trim()
        if (inner.isEmpty()) return emptyList()
        // 按引号外逗号切分
        val items = mutableListOf<String>()
        var start = 0; depth = 0; inBasic = false; inLiteral = false
        i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                c == '\\' && inBasic -> i++
                c == '"' && !inLiteral -> inBasic = !inBasic
                c == '\'' && !inBasic -> inLiteral = !inLiteral
                c == '[' && !inBasic && !inLiteral -> depth++
                c == ']' && !inBasic && !inLiteral -> depth--
                c == ',' && depth == 0 && !inBasic && !inLiteral -> {
                    items.add(inner.substring(start, i)); start = i + 1
                }
            }
            i++
        }
        items.add(inner.substring(start))
        return items.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.map { parseValue(doc, it) }
    }

    // ---------------- EasyTier 配置映射 ----------------

    /**
     * 从 TOML 提取 EasyTierConfig（可编辑表单字段）。
     * instance_name 固定由 LunaShare 生成，不映射；未知键记入 doc.unknown。
     */
    fun toEasyTierConfig(doc: Doc): EasyTierManager.EasyTierConfig {
        val ni = doc.root["network_identity"] as? Table
        val peers = (doc.root["peer"] as? List<*>)?.mapNotNull { p ->
            (p as? Table)?.get("uri") as? String
        } ?: emptyList()
        val dhcp = doc.root["dhcp"] == true
        val ipv4 = doc.root["ipv4"] as? String ?: ""
        // 全部字段在清理前取值（清理会把键从 root 移除）
        val hostname = doc.root["hostname"] as? String ?: ""

        // 未映射键收集：先收集表内部件，再移除 root 已知键，最后把 root 剩余键全部记为 unknown
        ni?.let { t ->
            t.entries.keys.filter { it !in setOf("network_name", "network_secret") }.forEach {
                doc.unknown.add("network_identity.$it")
            }
        }
        (doc.root["peer"] as? List<*>)?.forEach { p ->
            (p as? Table)?.let { t ->
                t.entries.keys.filter { it != "uri" }.forEach { doc.unknown.add("peer.$it") }
            }
        }
        KNOWN_ROOT_KEYS.forEach { doc.root.entries.remove(it) }
        doc.root.entries.keys.toList().forEach { k ->
            doc.unknown.add(k)
            doc.root.entries.remove(k)
        }

        return EasyTierManager.EasyTierConfig(
            networkName = (ni?.get("network_name") as? String).orEmpty(),
            networkSecret = (ni?.get("network_secret") as? String).orEmpty(),
            hostname = hostname,
            peers = peers,
            virtualIp = if (dhcp) "" else ipv4
        )
    }

    private val KNOWN_ROOT_KEYS = setOf("hostname", "ipv4", "dhcp", "network_identity", "peer", "instance_name")
}
