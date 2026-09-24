package com.lunashare.app.frpc

import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

/**
 * NaCl crypto_secretbox round-trip validation using manual HSalsa20 + Salsa20.
 * Tests the EXACT same algorithm as OpenFrpRemoteLogin.cryptoSecretboxOpen().
 */
class OpenFrpRemoteLoginCryptoTest {

    private val SIGMA = byteArrayOf(
        'e'.code.toByte(), 'x'.code.toByte(), 'p'.code.toByte(), 'a'.code.toByte(),
        'n'.code.toByte(), 'd'.code.toByte(), ' '.code.toByte(), '3'.code.toByte(),
        '2'.code.toByte(), '-'.code.toByte(), 'b'.code.toByte(), 'y'.code.toByte(),
        't'.code.toByte(), 'e'.code.toByte(), ' '.code.toByte(), 'k'.code.toByte()
    )

    @Test
    fun `secretbox round-trip encrypt then decrypt recovers original`() {
        val random = SecureRandom()
        val key = ByteArray(32).also { random.nextBytes(it) }
        val nonce = ByteArray(24).also { random.nextBytes(it) }
        val message = "Hello, OpenFrp Remote Login! 你好，世界！测试1234 ✅".toByteArray(Charsets.UTF_8)

        val box = secretboxSeal(message, nonce, key)
        val decrypted = cryptoSecretboxOpen(box, key)
        assertNotNull("Decryption should succeed", decrypted)
        assertEquals(String(message, Charsets.UTF_8), String(decrypted!!, Charsets.UTF_8))
    }

    @Test
    fun `secretbox tampered MAC returns null`() {
        val random = SecureRandom()
        val key = ByteArray(32).also { random.nextBytes(it) }
        val nonce = ByteArray(24).also { random.nextBytes(it) }
        val message = "tamper resistance test".toByteArray()

        val box = secretboxSeal(message, nonce, key)
        box[30] = (box[30].toInt() xor 0x01).toByte()
        val result = cryptoSecretboxOpen(box, key)
        assertNull("Tampered MAC must fail verification", result)
    }

    @Test
    fun `secretbox tampered ciphertext returns null`() {
        val random = SecureRandom()
        val key = ByteArray(32).also { random.nextBytes(it) }
        val nonce = ByteArray(24).also { random.nextBytes(it) }
        val message = "ciphertext tamper test data longer than 32 bytes to test multi-block".toByteArray()

        val box = secretboxSeal(message, nonce, key)
        box[50] = (box[50].toInt() xor 0xFF).toByte()
        val result = cryptoSecretboxOpen(box, key)
        assertNull("Tampered ciphertext must fail verification", result)
    }

    @Test
    fun `secretbox known vector short message`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(24) { (it * 2).toByte() }
        val message = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

        val box1 = secretboxSeal(message, nonce, key)
        val box2 = secretboxSeal(message, nonce, key)
        assertEquals(box1.toList(), box2.toList())

        val decrypted = cryptoSecretboxOpen(box1, key)
        assertNotNull(decrypted)
        assertEquals(message.toList(), decrypted!!.toList())
    }

    @Test
    fun `secretbox multi-block message round-trip`() {
        val random = SecureRandom()
        val key = ByteArray(32).also { random.nextBytes(it) }
        val nonce = ByteArray(24).also { random.nextBytes(it) }
        // Message longer than 64 bytes to test multi-block decryption
        val message = ByteArray(200) { it.toByte() }

        val box = secretboxSeal(message, nonce, key)
        val decrypted = cryptoSecretboxOpen(box, key)
        assertNotNull(decrypted)
        assertEquals(message.toList(), decrypted!!.toList())
    }

    // ── Mirror of OpenFrpRemoteLogin's crypto (exact copy) ────────────

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putU32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v shr 8).toByte()
        b[off + 2] = (v shr 16).toByte()
        b[off + 3] = (v shr 24).toByte()
    }

    private fun rotl32(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun hsalsa20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val c = SIGMA
        var x0 = u32(c, 0); var x1 = u32(key, 0); var x2 = u32(key, 4); var x3 = u32(key, 8)
        var x4 = u32(key, 12); var x5 = u32(c, 4); var x6 = u32(nonce16, 0); var x7 = u32(nonce16, 4)
        var x8 = u32(nonce16, 8); var x9 = u32(nonce16, 12); var x10 = u32(c, 8); var x11 = u32(key, 16)
        var x12 = u32(key, 20); var x13 = u32(key, 24); var x14 = u32(key, 28); var x15 = u32(c, 12)

        for (i in 0 until 20 step 2) {
            var u = x0 + x12; x4 = x4 xor rotl32(u, 7)
            u = x4 + x0; x8 = x8 xor rotl32(u, 9)
            u = x8 + x4; x12 = x12 xor rotl32(u, 13)
            u = x12 + x8; x0 = x0 xor rotl32(u, 18)
            u = x5 + x1; x9 = x9 xor rotl32(u, 7)
            u = x9 + x5; x13 = x13 xor rotl32(u, 9)
            u = x13 + x9; x1 = x1 xor rotl32(u, 13)
            u = x1 + x13; x5 = x5 xor rotl32(u, 18)
            u = x10 + x6; x14 = x14 xor rotl32(u, 7)
            u = x14 + x10; x2 = x2 xor rotl32(u, 9)
            u = x2 + x14; x6 = x6 xor rotl32(u, 13)
            u = x6 + x2; x10 = x10 xor rotl32(u, 18)
            u = x15 + x11; x3 = x3 xor rotl32(u, 7)
            u = x3 + x15; x7 = x7 xor rotl32(u, 9)
            u = x7 + x3; x11 = x11 xor rotl32(u, 13)
            u = x11 + x7; x15 = x15 xor rotl32(u, 18)
            u = x0 + x3; x1 = x1 xor rotl32(u, 7)
            u = x1 + x0; x2 = x2 xor rotl32(u, 9)
            u = x2 + x1; x3 = x3 xor rotl32(u, 13)
            u = x3 + x2; x0 = x0 xor rotl32(u, 18)
            u = x5 + x4; x6 = x6 xor rotl32(u, 7)
            u = x6 + x5; x7 = x7 xor rotl32(u, 9)
            u = x7 + x6; x4 = x4 xor rotl32(u, 13)
            u = x4 + x7; x5 = x5 xor rotl32(u, 18)
            u = x10 + x9; x11 = x11 xor rotl32(u, 7)
            u = x11 + x10; x8 = x8 xor rotl32(u, 9)
            u = x8 + x11; x9 = x9 xor rotl32(u, 13)
            u = x9 + x8; x10 = x10 xor rotl32(u, 18)
            u = x15 + x14; x12 = x12 xor rotl32(u, 7)
            u = x12 + x15; x13 = x13 xor rotl32(u, 9)
            u = x13 + x12; x14 = x14 xor rotl32(u, 13)
            u = x14 + x13; x15 = x15 xor rotl32(u, 18)
        }

        val out = ByteArray(32)
        putU32(out, 0, x0); putU32(out, 4, x5); putU32(out, 8, x10); putU32(out, 12, x15)
        putU32(out, 16, x6); putU32(out, 20, x7); putU32(out, 24, x8); putU32(out, 28, x9)
        return out
    }

    private fun salsa20Core(input: ByteArray, key: ByteArray): ByteArray {
        val c = SIGMA
        val j0 = u32(c, 0); val j1 = u32(key, 0); val j2 = u32(key, 4); val j3 = u32(key, 8)
        val j4 = u32(key, 12); val j5 = u32(c, 4); val j6 = u32(input, 0); val j7 = u32(input, 4)
        val j8 = u32(input, 8); val j9 = u32(input, 12); val j10 = u32(c, 8); val j11 = u32(key, 16)
        val j12 = u32(key, 20); val j13 = u32(key, 24); val j14 = u32(key, 28); val j15 = u32(c, 12)

        var x0 = j0; var x1 = j1; var x2 = j2; var x3 = j3
        var x4 = j4; var x5 = j5; var x6 = j6; var x7 = j7
        var x8 = j8; var x9 = j9; var x10 = j10; var x11 = j11
        var x12 = j12; var x13 = j13; var x14 = j14; var x15 = j15

        for (i in 0 until 20 step 2) {
            var u = x0 + x12; x4 = x4 xor rotl32(u, 7)
            u = x4 + x0; x8 = x8 xor rotl32(u, 9)
            u = x8 + x4; x12 = x12 xor rotl32(u, 13)
            u = x12 + x8; x0 = x0 xor rotl32(u, 18)
            u = x5 + x1; x9 = x9 xor rotl32(u, 7)
            u = x9 + x5; x13 = x13 xor rotl32(u, 9)
            u = x13 + x9; x1 = x1 xor rotl32(u, 13)
            u = x1 + x13; x5 = x5 xor rotl32(u, 18)
            u = x10 + x6; x14 = x14 xor rotl32(u, 7)
            u = x14 + x10; x2 = x2 xor rotl32(u, 9)
            u = x2 + x14; x6 = x6 xor rotl32(u, 13)
            u = x6 + x2; x10 = x10 xor rotl32(u, 18)
            u = x15 + x11; x3 = x3 xor rotl32(u, 7)
            u = x3 + x15; x7 = x7 xor rotl32(u, 9)
            u = x7 + x3; x11 = x11 xor rotl32(u, 13)
            u = x11 + x7; x15 = x15 xor rotl32(u, 18)
            u = x0 + x3; x1 = x1 xor rotl32(u, 7)
            u = x1 + x0; x2 = x2 xor rotl32(u, 9)
            u = x2 + x1; x3 = x3 xor rotl32(u, 13)
            u = x3 + x2; x0 = x0 xor rotl32(u, 18)
            u = x5 + x4; x6 = x6 xor rotl32(u, 7)
            u = x6 + x5; x7 = x7 xor rotl32(u, 9)
            u = x7 + x6; x4 = x4 xor rotl32(u, 13)
            u = x4 + x7; x5 = x5 xor rotl32(u, 18)
            u = x10 + x9; x11 = x11 xor rotl32(u, 7)
            u = x11 + x10; x8 = x8 xor rotl32(u, 9)
            u = x8 + x11; x9 = x9 xor rotl32(u, 13)
            u = x9 + x8; x10 = x10 xor rotl32(u, 18)
            u = x15 + x14; x12 = x12 xor rotl32(u, 7)
            u = x12 + x15; x13 = x13 xor rotl32(u, 9)
            u = x13 + x12; x14 = x14 xor rotl32(u, 13)
            u = x14 + x13; x15 = x15 xor rotl32(u, 18)
        }

        x0 += j0; x1 += j1; x2 += j2; x3 += j3; x4 += j4; x5 += j5; x6 += j6; x7 += j7
        x8 += j8; x9 += j9; x10 += j10; x11 += j11; x12 += j12; x13 += j13; x14 += j14; x15 += j15

        val out = ByteArray(64)
        putU32(out, 0, x0); putU32(out, 4, x1); putU32(out, 8, x2); putU32(out, 12, x3)
        putU32(out, 16, x4); putU32(out, 20, x5); putU32(out, 24, x6); putU32(out, 28, x7)
        putU32(out, 32, x8); putU32(out, 36, x9); putU32(out, 40, x10); putU32(out, 44, x11)
        putU32(out, 48, x12); putU32(out, 52, x13); putU32(out, 56, x14); putU32(out, 60, x15)
        return out
    }

    private fun cryptoSecretboxOpen(input: ByteArray, key: ByteArray): ByteArray? {
        if (input.size < 24 + 16) return null

        val nonce = input.copyOfRange(0, 24)
        val box = input.copyOfRange(24, input.size)

        val subKey = hsalsa20(key, nonce.copyOfRange(0, 16))
        val counter = ByteArray(16)
        System.arraycopy(nonce, 16, counter, 0, 8)

        val firstBlock = salsa20Core(counter, subKey)
        val polyKey = firstBlock.copyOfRange(0, 32)

        val macTag = box.copyOfRange(0, 16)
        val ciphertext = box.copyOfRange(16, box.size)

        val mac = Poly1305()
        mac.init(KeyParameter(polyKey))
        mac.update(ciphertext, 0, ciphertext.size)
        val expectedMac = ByteArray(16)
        mac.doFinal(expectedMac, 0)

        var diff = 0
        for (i in 0..15) diff = diff or (macTag[i].toInt() xor expectedMac[i].toInt())
        if (diff != 0) return null

        val plaintext = ByteArray(ciphertext.size)
        val firstMsgBlock = minOf(ciphertext.size, 32)
        for (i in 0 until firstMsgBlock) {
            plaintext[i] = (firstBlock[32 + i].toInt() xor ciphertext[i].toInt()).toByte()
        }

        if (ciphertext.size > 32) {
            counter[8] = 1
            val remaining = ciphertext.size - 32
            var offset = 0
            val ctrCopy = counter.copyOf()
            while (offset < remaining) {
                val block = salsa20Core(ctrCopy, subKey)
                val len = minOf(64, remaining - offset)
                for (i in 0 until len) {
                    plaintext[32 + offset + i] = (block[i].toInt() xor ciphertext[32 + offset + i].toInt()).toByte()
                }
                offset += 64
                var u = 1
                for (i in 8..15) {
                    u += (ctrCopy[i].toInt() and 0xFF)
                    ctrCopy[i] = u.toByte()
                    u = u shr 8
                    if (u == 0) break
                }
            }
        }
        return plaintext
    }

    private fun secretboxSeal(message: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val subKey = hsalsa20(key, nonce.copyOfRange(0, 16))
        val counter = ByteArray(16)
        System.arraycopy(nonce, 16, counter, 0, 8)

        val firstBlock = salsa20Core(counter, subKey)
        val polyKey = firstBlock.copyOfRange(0, 32)

        // Encrypt: first 32 bytes using firstBlock[32:64]
        val ciphertext = ByteArray(message.size)
        val firstLen = minOf(message.size, 32)
        for (i in 0 until firstLen) {
            ciphertext[i] = (firstBlock[32 + i].toInt() xor message[i].toInt()).toByte()
        }

        // Encrypt rest with counter+1
        if (message.size > 32) {
            counter[8] = 1
            val remaining = message.size - 32
            var offset = 0
            val ctrCopy = counter.copyOf()
            while (offset < remaining) {
                val block = salsa20Core(ctrCopy, subKey)
                val len = minOf(64, remaining - offset)
                for (i in 0 until len) {
                    ciphertext[32 + offset + i] = (block[i].toInt() xor message[32 + offset + i].toInt()).toByte()
                }
                offset += 64
                var u = 1
                for (i in 8..15) {
                    u += (ctrCopy[i].toInt() and 0xFF)
                    ctrCopy[i] = u.toByte()
                    u = u shr 8
                    if (u == 0) break
                }
            }
        }

        // MAC over ciphertext
        val mac = Poly1305()
        mac.init(KeyParameter(polyKey))
        mac.update(ciphertext, 0, ciphertext.size)
        val macTag = ByteArray(16)
        mac.doFinal(macTag, 0)

        // Output: [nonce][MAC][ciphertext]
        val result = ByteArray(24 + 16 + ciphertext.size)
        System.arraycopy(nonce, 0, result, 0, 24)
        System.arraycopy(macTag, 0, result, 24, 16)
        System.arraycopy(ciphertext, 0, result, 40, ciphertext.size)
        return result
    }
}
