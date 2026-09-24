package com.lunashare.app.util

import java.nio.charset.Charset

/**
 * 中文文件名乱码修复工具。
 *
 * 老中文站点 / GBK 客户端常把 GBK 字节当「字符串」直接传出（未按 RFC 编码），
 * 逐字节转成字符串后就是 "ÖÐÎÄ"（"中文" 的 GBK 字节 D6 D0 CE C4 落在 Latin-1 区）。
 * 这类乱码是**可逆**的：把 0x80..0xFF 的字符还原回字节、按 GBK 重新解码即可。
 * （另一类 UTF-8 宽松解码替换出的 U+FFFD "�" 则不可逆，无法修复。）
 */
object EncodingFixer {

    /** GBK 字符集（Android 内置 ICU 提供；取不到则为 null，相关修复自动跳过）。 */
    val GBK: Charset? = try { Charset.forName("GBK") } catch (_: Exception) { null }

    /**
     * 是否像「Latin-1 化的 GBK」乱码：
     * - 非 ASCII 字符全部落在 0x80..0xFF（GBK 双字节汉字每字恰好贡献 2 个这种字符，
     *   所以每个连续乱码段长度应为偶数——奇数段多半是合法西文名，不动它）；
     * - 一旦出现 ≥ 0x100 的字符（正常表意文字），说明本来就是好名字，直接放过。
     */
    fun looksLikeGbkMojibake(name: String): Boolean {
        var run = 0
        var sawLatin = false
        for (c in name) {
            if (c.code in 0x80..0xFF) {
                run++
                sawLatin = true
            } else {
                if (run % 2 != 0) return false
                run = 0
                if (c.code >= 0x100) return false
            }
        }
        return sawLatin && run % 2 == 0
    }

    /**
     * 修复 GBK 乱码文件名：Latin-1 字符还原为字节后按 GBK 重解码。
     * 结果必须「像正常中文文件名」（只含 ASCII / 常用中日韩字符）才采用，
     * 否则返回 null 保持原名，避免误伤真正的西文文件名。
     */
    fun fixFileName(name: String): String? {
        if (!looksLikeGbkMojibake(name)) return null
        val cs = GBK ?: return null
        return try {
            val bytes = ByteArray(name.length) { name[it].code.toByte() }
            val fixed = String(bytes, cs)
            if (fixed != name && isReasonableChinese(fixed)) fixed else null
        } catch (_: Exception) { null }
    }

    /** 修复后的名字是否「像正常中文文件名」：ASCII、CJK 统一表意、假名、全角/中文标点。 */
    private fun isReasonableChinese(s: String): Boolean = s.all { c ->
        c.code < 0x80 ||
            c.code in 0x4E00..0x9FFF ||  // CJK 统一表意（常用汉字）
            c.code in 0x3000..0x303F ||  // CJK 标点（，。「」…）
            c.code in 0xFF00..0xFFEF ||  // 全角形式（！？（）０-９）
            c.code in 0x3040..0x30FF     // 平假名 / 片假名
    }
}
