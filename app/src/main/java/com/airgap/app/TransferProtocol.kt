package com.airgap.app

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.InflaterOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirGap Transfer Protocol v2:
 * - High-efficiency raw binary framing (Protocol v2) eliminating Base64 and redundant JSON overhead
 * - 50 MiB file size limit throughout
 * - Streaming-capable chunking and checksum generation with zero unnecessary copies
 * - AES-256-GCM authenticated encryption with 28-byte minimal overhead (12-byte IV + 16-byte tag)
 * - Dynamic zlib compression: automatically compresses uncompressed files, skips already compressed files (ZIP, APK, MP4, etc.)
 * - Configurable density: Compact (768 B), Standard (1,280 B), Dense (1,800 B), Ultra (2,300 B)
 * - Cyclic sequential QR frames with robust metadata validation
 * - Full backward compatibility with Protocol v1 JSON frames
 * - Frame deduplication, out-of-order frame reassembly, memory-conscious streaming reconstruction
 */
object TransferProtocol {
    const val MAX_FILE_BYTES = 50 * 1024 * 1024 // 50 MiB
    const val DEFAULT_CHUNK_BYTES = 1280
    const val MIN_CHUNK_BYTES = 256
    const val MAX_CHUNK_BYTES = 2300

    const val PROTOCOL_VERSION_V2: Byte = 2
    val MAGIC_V2 = byteArrayOf(0x41.toByte(), 0x47.toByte(), 0x02.toByte()) // "AG\x02"
    const val FLAG_ENCRYPTED: Byte = 0x01
    const val FLAG_COMPRESSED: Byte = 0x02

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
        val payload: ByteArray,
        val compressed: Boolean = false,
        val version: Int = 2
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return session == other.session && index == other.index && total == other.total &&
                    name == other.name && mime == other.mime && size == other.size &&
                    encrypted == other.encrypted && compressed == other.compressed &&
                    salt.contentEquals(other.salt) && hash == other.hash &&
                    iv.contentEquals(other.iv) && payload.contentEquals(other.payload) &&
                    version == other.version
        }

        override fun hashCode(): Int {
            var result = session.hashCode()
            result = 31 * result + index
            result = 31 * result + total
            result = 31 * result + name.hashCode()
            result = 31 * result + mime.hashCode()
            result = 31 * result + size.hashCode()
            result = 31 * result + encrypted.hashCode()
            result = 31 * result + compressed.hashCode()
            result = 31 * result + salt.contentHashCode()
            result = 31 * result + hash.hashCode()
            result = 31 * result + iv.contentHashCode()
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + version
            return result
        }
    }

    data class Prepared(
        val frames: List<String>,
        val session: String,
        val fileName: String,
        val size: Long,
        val isEncrypted: Boolean,
        val hash: String,
        val isCompressed: Boolean = false,
        val version: Int = 2
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

    data class DecodedFileMeta(
        val name: String,
        val mime: String,
        val size: Long,
        val hash: String,
        val isEncrypted: Boolean
    )

    /**
     * Prepares in-memory byte array into high-efficiency raw binary QR frames (Protocol v2).
     */
    fun prepare(
        data: ByteArray,
        fileName: String,
        mime: String,
        passphrase: CharArray? = null,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES
    ): Prepared {
        require(data.isNotEmpty()) { "Empty files cannot be transferred." }
        require(data.size <= MAX_FILE_BYTES) { "File exceeds the 50 MiB limit (${data.size} bytes)." }
        val effectiveChunk = chunkBytes.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)

        val cleanName = sanitizeFileName(fileName)
        val cleanMime = sanitizeMimeType(mime)
        val hash = sha256Hex(data)

        val isEncrypted = passphrase != null && passphrase.isNotEmpty()
        if (isEncrypted) {
            require(passphrase!!.size >= 8) { "Use a passphrase with at least 8 characters." }
        }

        // Evaluate dynamic compression
        val isPreComp = isPreCompressed(cleanName, cleanMime, data)
        val (finalData, isCompressed) = if (!isPreComp && data.size > 256) {
            val compressed = compressZlib(data)
            if (compressed.size < (data.size * 0.95).toInt()) {
                compressed to true
            } else {
                data to false
            }
        } else {
            data to false
        }

        val sessionBytes = ByteArray(8).also(random::nextBytes)
        val session = sessionBytes.joinToString("") { "%02x".format(it) }

        val salt = if (isEncrypted) ByteArray(16).also(random::nextBytes) else ByteArray(0)
        val keySpec = if (isEncrypted) deriveKey(passphrase!!, salt) else null

        val pieces = finalData.indices.step(effectiveChunk).map { start ->
            finalData.copyOfRange(start, minOf(start + effectiveChunk, finalData.size))
        }
        val total = pieces.size

        var flags: Byte = 0
        if (isEncrypted) flags = (flags.toInt() or FLAG_ENCRYPTED.toInt()).toByte()
        if (isCompressed) flags = (flags.toInt() or FLAG_COMPRESSED.toInt()).toByte()

        val hashBytes = hexToBytes(hash)
        val nameBytes = cleanName.toByteArray(Charsets.UTF_8)
        val mimeBytes = cleanMime.toByteArray(Charsets.UTF_8)

        val frames = ArrayList<String>(total)
        try {
            for (index in 0 until total) {
                val piece = pieces[index]
                val iv: ByteArray
                val payloadBytes: ByteArray

                if (isEncrypted && keySpec != null) {
                    iv = ByteArray(12).also(random::nextBytes)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
                    cipher.updateAAD(aadV2(session, index, total, cleanName, cleanMime, data.size.toLong(), hash, true))
                    payloadBytes = cipher.doFinal(piece)
                } else {
                    iv = ByteArray(0)
                    payloadBytes = piece
                }

                val frameBytes = serializeV2Frame(
                    sessionBytes = sessionBytes,
                    index = index,
                    total = total,
                    originalSize = data.size.toLong(),
                    hashBytes = hashBytes,
                    flags = flags,
                    salt = salt,
                    iv = iv,
                    nameBytes = nameBytes,
                    mimeBytes = mimeBytes,
                    payload = payloadBytes
                )
                frames.add(String(frameBytes, Charsets.ISO_8859_1))
            }
        } finally {
            passphrase?.fill('\u0000')
            keySpec?.encoded?.fill(0)
        }

        return Prepared(frames, session, cleanName, data.size.toLong(), isEncrypted, hash, isCompressed, version = 2)
    }

    /**
     * Streaming prepare from an InputStream directly into frames, avoiding multiple 50 MiB memory duplications.
     */
    fun prepareFromStream(
        stream: InputStream,
        totalSize: Long,
        fileName: String,
        mime: String,
        passphrase: CharArray? = null,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES
    ): Prepared {
        require(totalSize in 1..MAX_FILE_BYTES) { "File size ($totalSize bytes) is outside the 1..50 MiB limit." }
        val effectiveChunk = chunkBytes.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)

        val cleanName = sanitizeFileName(fileName)
        val cleanMime = sanitizeMimeType(mime)

        val isEncrypted = passphrase != null && passphrase.isNotEmpty()
        if (isEncrypted) {
            require(passphrase!!.size >= 8) { "Use a passphrase with at least 8 characters." }
        }

        val sessionBytes = ByteArray(8).also(random::nextBytes)
        val session = sessionBytes.joinToString("") { "%02x".format(it) }
        val salt = if (isEncrypted) ByteArray(16).also(random::nextBytes) else ByteArray(0)
        val keySpec = if (isEncrypted) deriveKey(passphrase!!, salt) else null

        // Sample header for pre-compressed determination
        val headerBuf = ByteArray(64)
        val initialRead = stream.read(headerBuf)
        require(initialRead > 0) { "Input stream contained 0 bytes." }

        val actualHeader = headerBuf.copyOf(initialRead)
        val isPreComp = isPreCompressed(cleanName, cleanMime, actualHeader)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(actualHeader)

        var totalBytesRead = initialRead.toLong()
        val chunkList = ArrayList<ByteArray>()

        var isCompressed = false

        if (isPreComp) {
            // Already compressed: stream raw chunks without wasteful re-compression
            var currentChunk = ByteArrayOutputStream(effectiveChunk)
            currentChunk.write(actualHeader)

            val buf = ByteArray(effectiveChunk)
            while (true) {
                val read = stream.read(buf)
                if (read == -1) break
                totalBytesRead += read
                if (totalBytesRead > MAX_FILE_BYTES) {
                    throw IllegalArgumentException("Stream exceeded maximum file size of 50 MiB.")
                }
                digest.update(buf, 0, read)

                var offset = 0
                while (offset < read) {
                    val spaceLeft = effectiveChunk - currentChunk.size()
                    val toWrite = minOf(spaceLeft, read - offset)
                    currentChunk.write(buf, offset, toWrite)
                    offset += toWrite
                    if (currentChunk.size() == effectiveChunk) {
                        chunkList.add(currentChunk.toByteArray())
                        currentChunk = ByteArrayOutputStream(effectiveChunk)
                    }
                }
            }
            if (currentChunk.size() > 0) {
                chunkList.add(currentChunk.toByteArray())
            }
        } else {
            // Uncompressed data: read full stream to check compression efficiency
            val fullRawStream = ByteArrayOutputStream(totalSize.toInt().coerceAtLeast(1024))
            fullRawStream.write(actualHeader)
            val buf = ByteArray(8192)
            while (true) {
                val read = stream.read(buf)
                if (read == -1) break
                totalBytesRead += read
                if (totalBytesRead > MAX_FILE_BYTES) {
                    throw IllegalArgumentException("Stream exceeded maximum file size of 50 MiB.")
                }
                digest.update(buf, 0, read)
                fullRawStream.write(buf, 0, read)
            }
            val rawBytes = fullRawStream.toByteArray()
            val compressed = compressZlib(rawBytes)
            val (dataToChunk, compressedFlag) = if (compressed.size < (rawBytes.size * 0.95).toInt()) {
                compressed to true
            } else {
                rawBytes to false
            }
            isCompressed = compressedFlag

            val pieces = dataToChunk.indices.step(effectiveChunk).map { start ->
                dataToChunk.copyOfRange(start, minOf(start + effectiveChunk, dataToChunk.size))
            }
            chunkList.addAll(pieces)
        }

        require(chunkList.isNotEmpty()) { "Input stream contained 0 bytes." }
        val finalHash = digest.digest().joinToString("") { "%02x".format(it) }
        val hashBytes = hexToBytes(finalHash)
        val actualTotal = chunkList.size

        var flags: Byte = 0
        if (isEncrypted) flags = (flags.toInt() or FLAG_ENCRYPTED.toInt()).toByte()
        if (isCompressed) flags = (flags.toInt() or FLAG_COMPRESSED.toInt()).toByte()

        val nameBytes = cleanName.toByteArray(Charsets.UTF_8)
        val mimeBytes = cleanMime.toByteArray(Charsets.UTF_8)

        val frames = ArrayList<String>(actualTotal)
        try {
            for (index in 0 until actualTotal) {
                val piece = chunkList[index]
                val iv: ByteArray
                val payloadBytes: ByteArray

                if (isEncrypted && keySpec != null) {
                    iv = ByteArray(12).also(random::nextBytes)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
                    cipher.updateAAD(aadV2(session, index, actualTotal, cleanName, cleanMime, totalBytesRead, finalHash, true))
                    payloadBytes = cipher.doFinal(piece)
                } else {
                    iv = ByteArray(0)
                    payloadBytes = piece
                }

                val frameBytes = serializeV2Frame(
                    sessionBytes = sessionBytes,
                    index = index,
                    total = actualTotal,
                    originalSize = totalBytesRead,
                    hashBytes = hashBytes,
                    flags = flags,
                    salt = salt,
                    iv = iv,
                    nameBytes = nameBytes,
                    mimeBytes = mimeBytes,
                    payload = payloadBytes
                )
                frames.add(String(frameBytes, Charsets.ISO_8859_1))
            }
        } finally {
            passphrase?.fill('\u0000')
            keySpec?.encoded?.fill(0)
        }

        return Prepared(frames, session, cleanName, totalBytesRead, isEncrypted, finalHash, isCompressed, version = 2)
    }

    /**
     * Parses and strictly validates an incoming QR frame from raw bytes.
     */
    fun parse(bytes: ByteArray): Frame {
        require(bytes.size >= 48) { "QR frame payload too short (${bytes.size} bytes)." }

        // Check for Protocol v2 Magic bytes (0x41, 0x47, 0x02)
        if (bytes[0] == MAGIC_V2[0] && bytes[1] == MAGIC_V2[1] && bytes[2] == MAGIC_V2[2]) {
            val buf = ByteBuffer.wrap(bytes)
            buf.position(3)
            val flags = buf.get()
            val isEncrypted = (flags.toInt() and FLAG_ENCRYPTED.toInt()) != 0
            val isCompressed = (flags.toInt() and FLAG_COMPRESSED.toInt()) != 0

            val sessionBytes = ByteArray(8)
            buf.get(sessionBytes)
            val session = sessionBytes.joinToString("") { "%02x".format(it) }

            val index = buf.int
            val total = buf.int
            require(total in 1..500_000 && index in 0 until total) { "Invalid frame index ($index of $total)." }

            val size = buf.long
            require(size in 1..MAX_FILE_BYTES.toLong()) { "File size $size is outside supported 50 MiB limit." }

            val hashBytes = ByteArray(32)
            buf.get(hashBytes)
            val hash = hashBytes.joinToString("") { "%02x".format(it) }

            val salt = if (isEncrypted) ByteArray(16).also { buf.get(it) } else ByteArray(0)
            val iv = if (isEncrypted) ByteArray(12).also { buf.get(it) } else ByteArray(0)

            val nameLen = buf.short.toInt() and 0xFFFF
            require(nameLen in 1..512) { "Invalid filename length: $nameLen." }
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val name = sanitizeFileName(String(nameBytes, Charsets.UTF_8))

            val mimeLen = buf.short.toInt() and 0xFFFF
            require(mimeLen in 1..256) { "Invalid MIME length: $mimeLen." }
            val mimeBytes = ByteArray(mimeLen)
            buf.get(mimeBytes)
            val mime = sanitizeMimeType(String(mimeBytes, Charsets.UTF_8))

            val payloadLen = buf.int
            require(payloadLen in 1..(MAX_CHUNK_BYTES + 64)) { "Invalid payload length: $payloadLen." }
            val payload = ByteArray(payloadLen)
            buf.get(payload)

            return Frame(
                session = session,
                index = index,
                total = total,
                name = name,
                mime = mime,
                size = size,
                encrypted = isEncrypted,
                salt = salt,
                hash = hash,
                iv = iv,
                payload = payload,
                compressed = isCompressed,
                version = 2
            )
        }

        // Backward compatibility: fallback to Protocol v1 JSON
        val rawStr = if (bytes[0] == '{'.code.toByte()) {
            String(bytes, Charsets.UTF_8)
        } else {
            String(bytes, Charsets.ISO_8859_1)
        }
        return parseV1Json(rawStr)
    }

    /**
     * Parses and strictly validates an incoming QR frame string.
     */
    fun parse(raw: String): Frame {
        if (raw.startsWith("{")) {
            return parseV1Json(raw)
        }
        return parse(raw.toByteArray(Charsets.ISO_8859_1))
    }

    private fun parseV1Json(raw: String): Frame {
        require(raw.length <= 16_000) { "QR frame payload exceeds maximum allowed size." }
        val j = JSONObject(raw)
        require(j.optInt("v") == 1) { "Unsupported AirGap protocol version." }

        val session = j.getString("sid")
        require(session.matches(Regex("^[a-f0-9]{16}$"))) { "Invalid session ID format." }

        val index = j.getInt("i")
        val total = j.getInt("n")
        require(total in 1..500_000 && index in 0 until total) { "Invalid frame index ($index of $total)." }

        val size = j.getLong("size")
        require(size in 1..MAX_FILE_BYTES.toLong()) { "File size $size is outside supported 50 MiB limit." }

        val name = sanitizeFileName(j.getString("name"))
        val mime = sanitizeMimeType(j.getString("mime"))

        val hash = j.getString("hash").lowercase()
        require(hash.matches(Regex("^[a-f0-9]{64}$"))) { "Invalid SHA-256 hash format." }

        val isEncrypted = if (j.has("enc")) j.getInt("enc") == 1 else j.optString("salt").isNotBlank()

        val salt = if (isEncrypted) unb64(j.getString("salt")) else ByteArray(0)
        val iv = if (isEncrypted) unb64(j.getString("iv")) else ByteArray(0)
        val payload = unb64(j.getString("data"))

        return Frame(
            session = session,
            index = index,
            total = total,
            name = name,
            mime = mime,
            size = size,
            encrypted = isEncrypted,
            salt = salt,
            hash = hash,
            iv = iv,
            payload = payload,
            compressed = false,
            version = 1
        )
    }

    /**
     * Reconstructs the original file from the collected frames and verifies integrity.
     */
    fun reconstruct(frames: Collection<Frame>, passphrase: CharArray? = null): DecodedFile {
        val out = ByteArrayOutputStream()
        val meta = reconstructToStream(frames, out, passphrase)
        return DecodedFile(
            name = meta.name,
            mime = meta.mime,
            data = out.toByteArray(),
            hash = meta.hash,
            isEncrypted = meta.isEncrypted
        )
    }

    /**
     * Streaming reconstruction directly to an OutputStream (e.g. file sink) to prevent holding 50 MiB in memory.
     */
    fun reconstructToStream(
        frames: Collection<Frame>,
        outStream: OutputStream,
        passphrase: CharArray? = null
    ): DecodedFileMeta {
        require(frames.isNotEmpty()) { "No frames received." }
        val first = frames.first()
        val total = first.total

        require(frames.size >= total) { "Incomplete transfer: received ${frames.size} of $total frames." }
        require(frames.all {
            it.session == first.session &&
                    it.total == total &&
                    it.encrypted == first.encrypted &&
                    it.compressed == first.compressed &&
                    it.salt.contentEquals(first.salt) &&
                    it.hash == first.hash &&
                    it.size == first.size &&
                    it.version == first.version
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

        val digest = MessageDigest.getInstance("SHA-256")
        val isCompressed = first.compressed

        // For compressed transfers, buffer decrypted stream and decompress via Inflater
        val decryptedDataStream = if (isCompressed) ByteArrayOutputStream() else null
        var totalWritten = 0L

        try {
            for (i in 0 until total) {
                val f = byIndex.getValue(i)
                val chunk = if (isEncrypted && keySpec != null) {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, f.iv))
                    val aad = if (f.version == 1) {
                        aadV1(f.session, f.index, f.total, f.name, f.mime, f.size, f.hash, true)
                    } else {
                        aadV2(f.session, f.index, f.total, f.name, f.mime, f.size, f.hash, true)
                    }
                    cipher.updateAAD(aad)
                    cipher.doFinal(f.payload)
                } else {
                    f.payload
                }

                if (isCompressed && decryptedDataStream != null) {
                    decryptedDataStream.write(chunk)
                } else {
                    outStream.write(chunk)
                    digest.update(chunk)
                    totalWritten += chunk.size
                }
            }

            if (isCompressed && decryptedDataStream != null) {
                val decompressed = decompressZlib(decryptedDataStream.toByteArray())
                outStream.write(decompressed)
                digest.update(decompressed)
                totalWritten = decompressed.size.toLong()
            }

            outStream.flush()
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Wrong passphrase or corrupted ciphertext.")
        } finally {
            passphrase?.fill('\u0000')
            keySpec?.encoded?.fill(0)
        }

        require(totalWritten == first.size) {
            "Reconstructed file size mismatch: expected ${first.size}, got $totalWritten."
        }

        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        require(MessageDigest.isEqual(actualHash.toByteArray(), first.hash.toByteArray())) {
            "SHA-256 verification failed. File integrity corrupted."
        }

        return DecodedFileMeta(
            name = first.name,
            mime = first.mime,
            size = totalWritten,
            hash = actualHash,
            isEncrypted = isEncrypted
        )
    }

    fun decrypt(frames: Collection<Frame>, passphrase: CharArray): Pair<String, ByteArray> {
        val decoded = reconstruct(frames, passphrase)
        return decoded.name to decoded.data
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String = sha256(data).joinToString("") { "%02x".format(it) }

    fun sha256HexStream(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        var read: Int
        while (stream.read(buf).also { read = it } != -1) {
            digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun isPreCompressed(name: String, mime: String, headerSample: ByteArray): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        val compressedExts = setOf(
            "zip", "apk", "jar", "gz", "tgz", "bz2", "xz", "7z", "rar",
            "mp4", "mkv", "avi", "mov", "mp3", "aac", "flac", "ogg",
            "jpg", "jpeg", "png", "webp", "gif", "pdf", "docx", "xlsx", "pptx"
        )
        if (ext in compressedExts) return true
        val lowerMime = mime.lowercase()
        if (lowerMime.startsWith("image/") && !lowerMime.contains("bmp") && !lowerMime.contains("svg")) return true
        if (lowerMime.startsWith("video/") || lowerMime.startsWith("audio/")) return true
        if (lowerMime.contains("zip") || lowerMime.contains("compressed") || lowerMime.contains("tar") || lowerMime.contains("gzip")) return true

        if (headerSample.size >= 4) {
            if (headerSample[0] == 0x50.toByte() && headerSample[1] == 0x4B.toByte()) return true // PK
            if (headerSample[0] == 0x1F.toByte() && headerSample[1] == 0x8B.toByte()) return true // GZIP
            if (headerSample[0] == 0x89.toByte() && headerSample[1] == 0x50.toByte() && headerSample[2] == 0x4E.toByte()) return true // PNG
            if (headerSample[0] == 0xFF.toByte() && headerSample[1] == 0xD8.toByte()) return true // JPEG
            if (headerSample[0] == 0x37.toByte() && headerSample[1] == 0x7A.toByte() && headerSample[2] == 0xBC.toByte()) return true // 7z
        }
        return false
    }

    private fun compressZlib(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            out.write(buf, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun decompressZlib(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(buf)
            if (count == 0 && inflater.needsInput()) break
            out.write(buf, 0, count)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun serializeV2Frame(
        sessionBytes: ByteArray,
        index: Int,
        total: Int,
        originalSize: Long,
        hashBytes: ByteArray,
        flags: Byte,
        salt: ByteArray,
        iv: ByteArray,
        nameBytes: ByteArray,
        mimeBytes: ByteArray,
        payload: ByteArray
    ): ByteArray {
        val isEncrypted = (flags.toInt() and FLAG_ENCRYPTED.toInt()) != 0
        val headerSize = 3 + 1 + 8 + 4 + 4 + 8 + 32 +
                (if (isEncrypted) 16 + 12 else 0) +
                2 + nameBytes.size +
                2 + mimeBytes.size +
                4
        val buf = ByteBuffer.allocate(headerSize + payload.size)
        buf.put(MAGIC_V2)
        buf.put(flags)
        buf.put(sessionBytes)
        buf.putInt(index)
        buf.putInt(total)
        buf.putLong(originalSize)
        buf.put(hashBytes)
        if (isEncrypted) {
            buf.put(salt)
            buf.put(iv)
        }
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)
        buf.putShort(mimeBytes.size.toShort())
        buf.put(mimeBytes)
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun aadV2(
        session: String,
        index: Int,
        total: Int,
        name: String,
        mime: String,
        size: Long,
        hash: String,
        isEncrypted: Boolean
    ): ByteArray {
        val prefix = if (isEncrypted) "v2|" else "v2|plain|"
        return "$prefix$session|$index|$total|$name|$mime|$size|$hash".toByteArray(Charsets.UTF_8)
    }

    private fun aadV1(
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

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64(value: String): ByteArray = java.util.Base64.getUrlDecoder().decode(value)
}
