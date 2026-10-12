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

/**
 * AirGap Transfer Protocol:
 * - Supports both optional passphrase-based authenticated encryption (AES-256-GCM) and unencrypted transfers
 * - Adaptive chunk sizing (default 360 bytes, configurable between 120 and 720 bytes for display/camera density)
 * - Cyclic sequential QR frames with robust metadata validation
 * - Frame deduplication, out-of-order frame reassembly, SHA-256 integrity check
 * - Safe input bounds and memory protection for up to 10 MiB files
 */
object TransferProtocol {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    const val DEFAULT_CHUNK_BYTES = 360
    const val MIN_CHUNK_BYTES = 120
    const val MAX_CHUNK_BYTES = 720

    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    data class Frame(
        val session: String,
        val index: Int,
        val total: Int,
        val name: String,
        val mime: String,
        val size: Long,
        val encrypted: Boolean,
        val salt: ByteArray,
        val hash: String,
        val iv: ByteArray,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return session == other.session && index == other.index && total == other.total &&
                    name == other.name && mime == other.mime && size == other.size &&
                    encrypted == other.encrypted && salt.contentEquals(other.salt) &&
                    hash == other.hash && iv.contentEquals(other.iv) && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = session.hashCode()
            result = 31 * result + index
            result = 31 * result + total
            result = 31 * result + name.hashCode()
            result = 31 * result + mime.hashCode()
            result = 31 * result + size.hashCode()
            result = 31 * result + encrypted.hashCode()
            result = 31 * result + salt.contentHashCode()
            result = 31 * result + hash.hashCode()
            result = 31 * result + iv.contentHashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    data class Prepared(
        val frames: List<String>,
        val session: String,
        val fileName: String,
        val size: Int,
        val isEncrypted: Boolean,
        val hash: String
    )

    data class DecodedFile(
        val name: String,
        val mime: String,
        val data: ByteArray,
        val hash: String,
        val isEncrypted: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedFile) return false
            return name == other.name && mime == other.mime &&
                    hash == other.hash && isEncrypted == other.isEncrypted &&
                    data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + mime.hashCode()
            result = 31 * result + hash.hashCode()
            result = 31 * result + isEncrypted.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * Prepares data into sequential JSON QR frames.
     * If [passphrase] is null or empty, plain frames are generated with SHA-256 integrity verification.
     * If [passphrase] is provided, it must be >= 8 chars and data is AES-256-GCM encrypted.
     */
    fun prepare(
        data: ByteArray,
        fileName: String,
        mime: String,
        passphrase: CharArray? = null,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES
    ): Prepared {
        require(data.isNotEmpty()) { "Empty files cannot be transferred." }
        require(data.size <= MAX_FILE_BYTES) { "File exceeds the 10 MiB limit (${data.size} bytes)." }
        val effectiveChunk = chunkBytes.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)

        val cleanName = sanitizeFileName(fileName)
        val cleanMime = sanitizeMimeType(mime)
        val hash = sha256Hex(data)

        val isEncrypted = passphrase != null && passphrase.isNotEmpty()
        if (isEncrypted) {
            require(passphrase!!.size >= 8) { "Use a passphrase with at least 8 characters." }
        }

        val sessionBytes = ByteArray(8).also(random::nextBytes)
        val session = sessionBytes.joinToString("") { "%02x".format(it) }

        val salt = if (isEncrypted) ByteArray(16).also(random::nextBytes) else ByteArray(0)
        val keySpec = if (isEncrypted) deriveKey(passphrase!!, salt) else null

        val pieces = data.indices.step(effectiveChunk).map { start ->
            data.copyOfRange(start, minOf(start + effectiveChunk, data.size))
        }
        val total = pieces.size

        val frames = try {
            pieces.mapIndexed { index, piece ->
                val iv: ByteArray
                val payloadBytes: ByteArray

                if (isEncrypted && keySpec != null) {
                    iv = ByteArray(12).also(random::nextBytes)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
                    cipher.updateAAD(aad(session, index, total, cleanName, cleanMime, data.size.toLong(), hash, true))
                    payloadBytes = cipher.doFinal(piece)
                } else {
                    iv = ByteArray(0)
                    payloadBytes = piece
                }

                JSONObject().apply {
                    put("v", 1)
                    put("sid", session)
                    put("i", index)
                    put("n", total)
                    put("name", cleanName)
                    put("mime", cleanMime)
                    put("size", data.size.toLong())
                    put("enc", if (isEncrypted) 1 else 0)
                    if (isEncrypted) {
                        put("salt", b64(salt))
                        put("iv", b64(iv))
                    }
                    put("hash", hash)
                    put("data", b64(payloadBytes))
                }.toString()
            }
        } finally {
            if (passphrase != null) {
                passphrase.fill('\u0000')
            }
            if (keySpec != null) {
                keySpec.encoded.fill(0)
            }
        }

        return Prepared(frames, session, cleanName, data.size, isEncrypted, hash)
    }

    /**
     * Parses and strictly validates an incoming raw QR frame string.
     */
    fun parse(raw: String): Frame {
        require(raw.length <= 16_000) { "QR frame payload exceeds maximum allowed size." }
        val j = JSONObject(raw)
        require(j.optInt("v") == 1) { "Unsupported AirGap protocol version." }

        val session = j.getString("sid")
        require(session.matches(Regex("^[a-f0-9]{16}$"))) { "Invalid session ID format." }

        val index = j.getInt("i")
        val total = j.getInt("n")
        require(total in 1..90_000 && index in 0 until total) { "Invalid frame index ($index of $total)." }

        val size = j.getLong("size")
        require(size in 1..MAX_FILE_BYTES.toLong()) { "File size $size is outside supported bounds." }

        val name = sanitizeFileName(j.getString("name"))
        val mime = sanitizeMimeType(j.getString("mime"))

        val hash = j.getString("hash").lowercase()
        require(hash.matches(Regex("^[a-f0-9]{64}$"))) { "Invalid SHA-256 hash format." }

        // Backwards compatibility: if "enc" flag is missing, presence of non-empty "salt" indicates encrypted
        val isEncrypted = if (j.has("enc")) j.getInt("enc") == 1 else j.optString("salt").isNotBlank()

        val salt = if (isEncrypted) unb64(j.getString("salt")) else ByteArray(0)
        val iv = if (isEncrypted) unb64(j.getString("iv")) else ByteArray(0)
        val payload = unb64(j.getString("data"))

        if (isEncrypted) {
            require(salt.size == 16) { "Invalid encryption salt size." }
            require(iv.size == 12) { "Invalid GCM IV size." }
            // AES-GCM adds a 16-byte authentication tag
            require(payload.size in 16..(MAX_CHUNK_BYTES + 32)) { "Invalid ciphertext length." }
        } else {
            require(payload.isNotEmpty() && payload.size <= MAX_CHUNK_BYTES) { "Invalid plaintext chunk length." }
        }

        return Frame(session, index, total, name, mime, size, isEncrypted, salt, hash, iv, payload)
    }

    /**
     * Reconstructs the original file from the collected frames and verifies integrity.
     * Decrypts if frames are encrypted, or directly assembles if unencrypted.
     */
    fun reconstruct(frames: Collection<Frame>, passphrase: CharArray? = null): DecodedFile {
        require(frames.isNotEmpty()) { "No frames received." }
        val first = frames.first()
        val total = first.total

        require(frames.size >= total) { "Incomplete transfer: received ${frames.size} of $total frames." }
        require(frames.all {
            it.session == first.session &&
                    it.total == total &&
                    it.encrypted == first.encrypted &&
                    it.salt.contentEquals(first.salt) &&
                    it.hash == first.hash &&
                    it.size == first.size
        }) { "Inconsistent frames detected across transfer." }

        val byIndex = frames.associateBy { it.index }
        require(byIndex.size == total && (0 until total).all(byIndex::containsKey)) {
            "Missing frame segments in sequence."
        }

        val isEncrypted = first.encrypted
        val keySpec = if (isEncrypted) {
            require(passphrase != null && passphrase.isNotEmpty()) { "Passphrase required to decrypt this transfer." }
            deriveKey(passphrase, first.salt)
        } else null

        val output = java.io.ByteArrayOutputStream(first.size.toInt().coerceAtLeast(1024))
        try {
            for (i in 0 until total) {
                val f = byIndex.getValue(i)
                if (isEncrypted && keySpec != null) {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, f.iv))
                    cipher.updateAAD(aad(f.session, f.index, f.total, f.name, f.mime, f.size, f.hash, true))
                    val chunk = cipher.doFinal(f.payload)
                    output.write(chunk)
                } else {
                    output.write(f.payload)
                }
            }
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Wrong passphrase or corrupted ciphertext.")
        } finally {
            passphrase?.fill('\u0000')
            keySpec?.encoded?.fill(0)
        }

        val reconstructedBytes = output.toByteArray()
        require(reconstructedBytes.size.toLong() == first.size) {
            "Reconstructed file size mismatch: expected ${first.size}, got ${reconstructedBytes.size}."
        }

        val actualHash = sha256Hex(reconstructedBytes)
        require(MessageDigest.isEqual(actualHash.toByteArray(), first.hash.toByteArray())) {
            "SHA-256 verification failed. File integrity corrupted."
        }

        return DecodedFile(
            name = first.name,
            mime = first.mime,
            data = reconstructedBytes,
            hash = actualHash,
            isEncrypted = isEncrypted
        )
    }

    /**
     * Backwards-compatible convenience decrypt method.
     */
    fun decrypt(frames: Collection<Frame>, passphrase: CharArray): Pair<String, ByteArray> {
        val decoded = reconstruct(frames, passphrase)
        return decoded.name to decoded.data
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String = sha256(data).joinToString("") { "%02x".format(it) }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad(
        session: String,
        index: Int,
        total: Int,
        name: String,
        mime: String,
        size: Long,
        hash: String,
        isEncrypted: Boolean
    ): ByteArray {
        val prefix = if (isEncrypted) "" else "plain|"
        return "$prefix$session|$index|$total|$name|$mime|$size|$hash".toByteArray(Charsets.UTF_8)
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().replace(Regex("[/\\\\?%*:|\"<>]"), "_")
        return trimmed.take(180).ifBlank { "airgap-transfer.bin" }
    }

    private fun sanitizeMimeType(mime: String): String {
        val trimmed = mime.trim().lowercase()
        return if (trimmed.matches(Regex("^[a-z0-9.+_-]+/[a-z0-9.+_-]+$"))) trimmed.take(120) else "application/octet-stream"
    }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(value: String): ByteArray = java.util.Base64.getUrlDecoder().decode(value)
}
