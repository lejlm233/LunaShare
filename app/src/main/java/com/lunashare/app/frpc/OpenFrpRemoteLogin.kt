package com.lunashare.app.frpc

import android.util.Base64
import android.util.Log
import com.lunashare.app.frpc.model.OpenFrpApiResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Implements the OpenFrp Remote Login flow (简易且安全的登录到第三方客户端).
 *
 * Flow:
 *   1. Generate X25519 keypair
 *   2. POST public key to requestLogin → get authorization_url + request_uuid
 *   3. Open browser with authorization_url for user to authorize
 *   4. Poll pollLogin every 5s for up to 5 minutes
 *   5. Decrypt authorization_data using ECDH (X25519) + NaCl (XSalsa20-Poly1305)
 *   6. Return the Authorization token
 *
 * Reference: https://github.com/ZGIT-Network/OPENFRP-APIDOC
 */
class OpenFrpRemoteLogin {

    companion object {
        private const val TAG = "OpenFrpRemoteLogin"

        private const val ACCESS_BASE = "https://access.openfrp.net"
        private const val REQUEST_LOGIN_PATH = "/argoAccess/requestLogin"
        private const val POLL_LOGIN_PATH = "/argoAccess/pollLogin"

        const val POLL_INTERVAL_MS = 5000L
        const val POLL_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
        const val MAX_POLL_ATTEMPTS = 60

        private val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ── Data classes ───────────────────────────────────────────────

    data class KeyPair(
        val privateKey: ByteArray,
        val publicKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeyPair) return false
            return privateKey.contentEquals(other.privateKey) &&
                    publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int {
            var result = privateKey.contentHashCode()
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
    }

    data class RequestLoginResult(
        val authorizationUrl: String,
        val requestUuid: String
    )

    data class PollLoginResult(
        val authorizationData: String?,
        val serverPublicKey: String?,
        val requestUuid: String
    )

    // ── Public API ────────────────────────────────────────────────

    /**
     * Generate a new X25519 keypair for Remote Login.
     */
    fun generateKeyPair(): KeyPair {
        val privateKeyBytes = ByteArray(32)
        SecureRandom().nextBytes(privateKeyBytes)
        val privKey = X25519PrivateKeyParameters(privateKeyBytes, 0)
        val pubKey = privKey.generatePublicKey()
        return KeyPair(
            privateKey = privateKeyBytes,
            publicKey = pubKey.encoded
        )
    }

    /**
     * Step 1: Request a login URL from OpenFrp.
     * Send the client's public key and get back an authorization URL.
     */
    suspend fun requestLogin(publicKey: ByteArray): RequestLoginResult {
        // OpenFrp uses URL-safe Base64 (with - and _ instead of + and /) for public_key
        val publicKeyB64 = Base64.encodeToString(publicKey, Base64.URL_SAFE or Base64.NO_WRAP)
        val body = """{"public_key":"$publicKeyB64"}"""

        val url = "$ACCESS_BASE$REQUEST_LOGIN_PATH"
        Log.d(TAG, "POST $url body=$body")

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", "LunaShare-OpenFrp/1.0")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        Log.d(TAG, "Response code=${response.code} body=$responseBody")

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $responseBody")
        }

        val jsonResponse = JSON.parseToJsonElement(responseBody)
        val jsonObj = jsonResponse.jsonObject

        val code = jsonObj["code"]?.toString()?.trim('"')?.toIntOrNull() ?: 0
        if (code != 200) {
            val msg = jsonObj["msg"]?.toString()?.trim('"') ?: "Unknown error"
            throw Exception("Request login failed: $msg (code=$code)")
        }

        val data = jsonObj["data"]?.jsonObject
            ?: throw Exception("Missing data in response")

        val authorizationUrl = data["authorization_url"]?.toString()?.trim('"')
            ?: throw Exception("Missing authorization_url")
        val requestUuid = data["request_uuid"]?.toString()?.trim('"')
            ?: throw Exception("Missing request_uuid")

        return RequestLoginResult(authorizationUrl, requestUuid)
    }

    /**
     * Step 2: Poll for the user's authorization result.
     * Call this in a loop every 5 seconds. Returns null when still waiting.
     * Throws when the request_uuid expires or an error occurs.
     *
     * @return PollLoginResult with authorizationData (may be null if still waiting)
     */
    suspend fun pollLogin(requestUuid: String): PollLoginResult {
        val url = "$ACCESS_BASE$POLL_LOGIN_PATH?request_uuid=$requestUuid"
        Log.d(TAG, "GET $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LunaShare-OpenFrp/1.0")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        val serverPublicKey = response.header("x-request-public-key")
        Log.d(TAG, "Response: $responseBody, serverPublicKey=$serverPublicKey")

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.message}")
        }

        val jsonResponse = JSON.parseToJsonElement(responseBody)
        val jsonObj = jsonResponse.jsonObject

        val code = jsonObj["code"]?.toString()?.trim('"')?.toIntOrNull() ?: 0
        if (code == 204) {
            // Still waiting for user to authorize
            return PollLoginResult(null, serverPublicKey, requestUuid)
        }
        if (code == 404) {
            throw Exception("请求已过期，请重新发起登录")
        }
        if (code != 200) {
            val msg = jsonObj["msg"]?.toString()?.trim('"') ?: "Unknown error"
            throw Exception("Poll login failed: $msg (code=$code)")
        }

        val data = jsonObj["data"]?.jsonObject
        val authorizationData = data?.get("authorization_data")?.toString()?.trim('"')

        return PollLoginResult(authorizationData, serverPublicKey, requestUuid)
    }

    /**
     * Step 3: Decrypt the authorization_data using NaCl (Curve25519-XSalsa20-Poly1305).
     *
     * The authorization_data format (after base64 decode):
     *   [24 bytes nonce][16 bytes Poly1305 MAC tag][encrypted message]
     *
     * Returns the decrypted Authorization token string.
     */
    fun decryptAuthorization(
        authorizationDataB64: String,
        clientPrivateKey: ByteArray,
        serverPublicKeyB64: String
    ): String? {
        return try {
            // authorization_data uses standard Base64 (+/), server public key header uses URL-safe (-_)
            val ciphertext = Base64.decode(authorizationDataB64, Base64.NO_WRAP)
            val serverPublicKey = Base64.decode(serverPublicKeyB64, Base64.URL_SAFE or Base64.NO_WRAP)

            // Compute ECDH shared secret
            val sharedSecret = computeECDH(clientPrivateKey, serverPublicKey)
            Log.d(TAG, "ECDH shared secret computed (${sharedSecret.size} bytes)")

            // Decrypt using NaCl secretbox_open
            val plaintext = cryptoSecretboxOpen(ciphertext, sharedSecret)
            if (plaintext != null) {
                val result = String(plaintext)
                Log.d(TAG, "Decrypted authorization: $result")
                result
            } else {
                Log.e(TAG, "Decryption failed — MAC verification error")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error", e)
            null
        }
    }

    // ── ECDH (X25519) ──────────────────────────────────────────────

    private fun computeECDH(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val privKey = X25519PrivateKeyParameters(privateKey, 0)
        val pubKey = X25519PublicKeyParameters(publicKey, 0)
        val agreement = X25519Agreement()
        agreement.init(privKey)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pubKey, sharedSecret, 0)
        return sharedSecret
    }

    // ── NaCl secretbox_open — manual Salsa20 implementation ─────────
    // Exactly matches Go's golang.org/x/crypto/nacl/secretbox + salsa20/salsa.
    // We can't use Bouncy Castle's XSalsa20Engine because its internal HSalsa20
    // may differ from Go's salsa.HSalsa20 (which doesn't add back the original
    // state and extracts specific words).

    private val SIGMA = byteArrayOf(
        'e'.code.toByte(), 'x'.code.toByte(), 'p'.code.toByte(), 'a'.code.toByte(),
        'n'.code.toByte(), 'd'.code.toByte(), ' '.code.toByte(), '3'.code.toByte(),
        '2'.code.toByte(), '-'.code.toByte(), 'b'.code.toByte(), 'y'.code.toByte(),
        't'.code.toByte(), 'e'.code.toByte(), ' '.code.toByte(), 'k'.code.toByte()
    )

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

    /**
     * HSalsa20: derives a 32-byte subkey from a 32-byte key and 16-byte nonce.
     * Matches Go's salsa.HSalsa20 exactly:
     *   - Runs 20 rounds of Salsa20 permutation
     *   - Does NOT add back the original state
     *   - Extracts words: x0, x5, x10, x15, x6, x7, x8, x9
     */
    private fun hsalsa20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val c = SIGMA
        var x0 = u32(c, 0);  var x1 = u32(key, 0);  var x2 = u32(key, 4);  var x3 = u32(key, 8)
        var x4 = u32(key, 12); var x5 = u32(c, 4);  var x6 = u32(nonce16, 0); var x7 = u32(nonce16, 4)
        var x8 = u32(nonce16, 8); var x9 = u32(nonce16, 12); var x10 = u32(c, 8); var x11 = u32(key, 16)
        var x12 = u32(key, 20); var x13 = u32(key, 24); var x14 = u32(key, 28); var x15 = u32(c, 12)

        for (i in 0 until 20 step 2) {
            // Column round
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
            // Row round
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

        // Output: x0, x5, x10, x15, x6, x7, x8, x9 (NO state addition)
        val out = ByteArray(32)
        putU32(out, 0, x0);  putU32(out, 4, x5);  putU32(out, 8, x10); putU32(out, 12, x15)
        putU32(out, 16, x6); putU32(out, 20, x7); putU32(out, 24, x8); putU32(out, 28, x9)
        return out
    }

    /**
     * Salsa20 core: generates a 64-byte block from 16-byte input (nonce+counter),
     * 32-byte key, and 16-byte constant. Adds back the original state.
     * Matches Go's salsa.core exactly.
     */
    private fun salsa20Core(input: ByteArray, key: ByteArray): ByteArray {
        val c = SIGMA
        val j0 = u32(c, 0);  val j1 = u32(key, 0);  val j2 = u32(key, 4);  val j3 = u32(key, 8)
        val j4 = u32(key, 12); val j5 = u32(c, 4);  val j6 = u32(input, 0); val j7 = u32(input, 4)
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

        // Add back original state
        x0 += j0; x1 += j1; x2 += j2; x3 += j3; x4 += j4; x5 += j5; x6 += j6; x7 += j7
        x8 += j8; x9 += j9; x10 += j10; x11 += j11; x12 += j12; x13 += j13; x14 += j14; x15 += j15

        val out = ByteArray(64)
        putU32(out, 0, x0);  putU32(out, 4, x1);  putU32(out, 8, x2);  putU32(out, 12, x3)
        putU32(out, 16, x4); putU32(out, 20, x5); putU32(out, 24, x6); putU32(out, 28, x7)
        putU32(out, 32, x8); putU32(out, 36, x9); putU32(out, 40, x10); putU32(out, 44, x11)
        putU32(out, 48, x12); putU32(out, 52, x13); putU32(out, 56, x14); putU32(out, 60, x15)
        return out
    }

    /**
     * NaCl crypto_secretbox_open — exactly matches Go's nacl/secretbox.Open.
     *
     * Input format (cloudflared): [24B nonce][16B MAC][ciphertext]
     * Key: 32 bytes (ECDH shared secret)
     */
    private fun cryptoSecretboxOpen(input: ByteArray, key: ByteArray): ByteArray? {
        if (input.size < 24 + 16) return null

        val nonce = input.copyOfRange(0, 24)
        val box = input.copyOfRange(24, input.size) // [16 MAC][ciphertext]

        // 1. setup: subKey = HSalsa20(key, nonce[0:16]), counter = nonce[16:24] || zeros
        val subKey = hsalsa20(key, nonce.copyOfRange(0, 16))
        val counter = ByteArray(16)
        System.arraycopy(nonce, 16, counter, 0, 8) // counter[0:8] = nonce[16:24], rest zeros

        // 2. Generate first 64-byte Salsa20 block (encrypting 64 zero bytes)
        val firstBlock = salsa20Core(counter, subKey)
        val polyKey = firstBlock.copyOfRange(0, 32)

        // 3. Verify Poly1305 MAC: tag = box[0:16], message = box[16:]
        val macTag = box.copyOfRange(0, 16)
        val ciphertext = box.copyOfRange(16, box.size)

        val mac = Poly1305()
        mac.init(KeyParameter(polyKey))
        mac.update(ciphertext, 0, ciphertext.size)
        val expectedMac = ByteArray(16)
        mac.doFinal(expectedMac, 0)

        if (!constantTimeEquals(macTag, expectedMac)) {
            Log.e(TAG, "Poly1305 MAC mismatch:\n  expected=${hex(expectedMac)}\n  got     =${hex(macTag)}")
            Log.d(TAG, "subKey=${hex(subKey)}, polyKey=${hex(polyKey)}")
            Log.d(TAG, "nonce=${hex(nonce)}, ciphertext(${ciphertext.size})=${hex(ciphertext)}")
            return null
        }
        Log.d(TAG, "Poly1305 MAC verified OK")

        // 4. Decrypt: first 32 bytes using firstBlock[32:64], rest using counter+1
        val plaintext = ByteArray(ciphertext.size)
        val firstMsgBlock = minOf(ciphertext.size, 32)
        for (i in 0 until firstMsgBlock) {
            plaintext[i] = (firstBlock[32 + i].toInt() xor ciphertext[i].toInt()).toByte()
        }

        if (ciphertext.size > 32) {
            // Increment counter[8] (block counter low byte) to 1
            counter[8] = 1
            val remaining = ciphertext.size - 32
            // Generate subsequent blocks
            var offset = 0
            val ctrCopy = counter.copyOf()
            while (offset < remaining) {
                val block = salsa20Core(ctrCopy, subKey)
                val len = minOf(64, remaining - offset)
                for (i in 0 until len) {
                    plaintext[32 + offset + i] = (block[i].toInt() xor ciphertext[32 + offset + i].toInt()).toByte()
                }
                offset += 64
                // Increment counter (little-endian at bytes 8-15)
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

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /**
     * Constant-time byte array comparison.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
