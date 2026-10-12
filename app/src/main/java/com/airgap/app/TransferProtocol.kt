package com.airgap.app

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** MVP protocol: independently authenticated chunks, repeated metadata, and cyclic QR frames. */
object TransferProtocol {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    private const val CHUNK_BYTES = 360
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    data class Frame(
        val session: String, val index: Int, val total: Int,
        val name: String, val mime: String, val size: Long,
        val salt: ByteArray, val hash: String, val iv: ByteArray, val cipherText: ByteArray
    )

    data class Prepared(val frames: List<String>, val session: String, val fileName: String, val size: Int)

    fun prepare(data: ByteArray, fileName: String, mime: String, passphrase: CharArray): Prepared {
        require(data.isNotEmpty()) { "Empty files are not supported in this MVP." }
        require(data.size <= MAX_FILE_BYTES) { "MVP limit is 10 MiB per file." }
        require(passphrase.size >= 8) { "Use a passphrase with at least 8 characters." }
        val salt = ByteArray(16).also(random::nextBytes)
        val sessionBytes = ByteArray(8).also(random::nextBytes)
        val session = sessionBytes.joinToString("") { "%02x".format(it) }
        val hash = sha256(data).joinToString("") { "%02x".format(it) }
        val key = deriveKey(passphrase, salt)
        val pieces = data.indices.step(CHUNK_BYTES).map { start -> data.copyOfRange(start, minOf(start + CHUNK_BYTES, data.size)) }
        val total = pieces.size
        val frames = pieces.mapIndexed { index, piece ->
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(session, index, total, fileName.take(180), mime.take(120), data.size.toLong(), hash))
            val encrypted = cipher.doFinal(piece)
            JSONObject().apply {
                put("v", 1); put("sid", session); put("i", index); put("n", total)
                put("name", fileName.take(180)); put("mime", mime.take(120)); put("size", data.size)
                put("salt", b64(salt)); put("hash", hash); put("iv", b64(iv)); put("data", b64(encrypted))
            }.toString()
        }
        key.encoded.fill(0)
        return Prepared(frames, session, fileName, data.size)
    }

    fun parse(raw: String): Frame {
        require(raw.length <= 12_000) { "QR frame is too large." }
        val j = JSONObject(raw)
        require(j.optInt("v") == 1) { "Unsupported AirGap frame version." }
        val session = j.getString("sid")
        require(session.matches(Regex("[a-f0-9]{16}"))) { "Invalid session ID." }
        val index = j.getInt("i"); val total = j.getInt("n")
        require(total in 1..30_000 && index in 0 until total) { "Invalid frame index." }
        val size = j.getLong("size")
        require(size in 1..MAX_FILE_BYTES.toLong()) { "File exceeds the 10 MiB MVP limit." }
        val salt = unb64(j.getString("salt")); val iv = unb64(j.getString("iv")); val payload = unb64(j.getString("data"))
        require(salt.size == 16 && iv.size == 12 && payload.size in 16..(CHUNK_BYTES + 16)) { "Invalid encrypted frame." }
        val hash = j.getString("hash")
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid file hash." }
        require(j.getString("name").length <= 180 && j.getString("mime").length <= 120) { "Invalid metadata." }
        return Frame(session, index, total, j.getString("name"), j.getString("mime"), size, salt, hash, iv, payload)
    }

    fun decrypt(frames: Collection<Frame>, passphrase: CharArray): Pair<String, ByteArray> {
        require(frames.isNotEmpty()) { "No frames received." }
        val first = frames.first()
        require(frames.size >= first.total) { "Transfer is incomplete." }
        require(frames.all { it.session == first.session && it.total == first.total && it.salt.contentEquals(first.salt) && it.hash == first.hash && it.size == first.size }) { "Frames belong to inconsistent transfers." }
        val byIndex = frames.associateBy { it.index }
        require(byIndex.size == first.total && (0 until first.total).all(byIndex::containsKey)) { "Missing QR frames." }
        val key = deriveKey(passphrase, first.salt)
        val output = java.io.ByteArrayOutputStream(first.size.toInt())
        try {
            for (i in 0 until first.total) {
                val f = byIndex.getValue(i)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, f.iv))
                cipher.updateAAD(aad(f.session, f.index, f.total, f.name, f.mime, f.size, f.hash))
                output.write(cipher.doFinal(f.cipherText))
            }
        } catch (e: AEADBadTagException) {
            throw IllegalArgumentException("Wrong passphrase or damaged transfer.")
        } finally {
            passphrase.fill('\u0000')
        }
        val bytes = output.toByteArray()
        require(bytes.size.toLong() == first.size) { "Reconstructed file size does not match." }
        val actual = sha256(bytes).joinToString("") { "%02x".format(it) }
        require(MessageDigest.isEqual(actual.toByteArray(), first.hash.toByteArray())) { "SHA-256 verification failed." }
        return first.name to bytes
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword(); passphrase.fill('\u0000') }
    }
    private fun aad(session: String, index: Int, total: Int, name: String, mime: String, size: Long, hash: String) = "$session|$index|$total|$name|$mime|$size|$hash".toByteArray(Charsets.UTF_8)
    private fun b64(bytes: ByteArray) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String) = java.util.Base64.getUrlDecoder().decode(value)
}
