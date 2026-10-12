package com.airgap.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class TransferProtocolTest {

    @Test
    fun roundTripOneChunkEncrypted() {
        val source = "AirGap offline test payload".toByteArray()
        val prepared = TransferProtocol.prepare(
            source, "test.txt", "text/plain", "correct horse".toCharArray()
        )
        assertTrue(prepared.isEncrypted)
        assertEquals(1, prepared.frames.size)

        val frames = prepared.frames.map { TransferProtocol.parse(it) }
        val (name, output) = TransferProtocol.decrypt(frames, "correct horse".toCharArray())
        assertEquals("test.txt", name)
        assertArrayEquals(source, output)
    }

    @Test
    fun roundTripOneChunkUnencrypted() {
        val source = "Offline plaintext transfer without encryption".toByteArray()
        val prepared = TransferProtocol.prepare(
            source, "notes.txt", "text/plain", null
        )
        assertFalse(prepared.isEncrypted)

        val frames = prepared.frames.map { TransferProtocol.parse(it) }
        val decoded = TransferProtocol.reconstruct(frames, null)
        assertEquals("notes.txt", decoded.name)
        assertEquals("text/plain", decoded.mime)
        assertArrayEquals(source, decoded.data)
        assertEquals(prepared.hash, decoded.hash)
        assertFalse(decoded.isEncrypted)
    }

    @Test
    fun roundTripMultiChunkAndOutOfOrderFrames() {
        val source = ByteArray(8_192) { (it * 31 % 251).toByte() }
        val prepared = TransferProtocol.prepare(
            source, "sample.zip", "application/zip", "a strong passphrase".toCharArray(), chunkBytes = 1280
        )
        assertTrue(prepared.frames.size > 1)

        val frames = prepared.frames.reversed().map { TransferProtocol.parse(it) }
        val decoded = TransferProtocol.reconstruct(frames, "a strong passphrase".toCharArray())
        assertEquals("sample.zip", decoded.name)
        assertArrayEquals(source, decoded.data)
    }

    @Test
    fun roundTripStreamingPrepareAndReconstruct() {
        val source = ByteArray(12_000) { (it * 17 % 256).toByte() }
        val inStream = ByteArrayInputStream(source)
        val prepared = TransferProtocol.prepareFromStream(
            stream = inStream,
            totalSize = source.size.toLong(),
            fileName = "stream_test.zip",
            mime = "application/zip",
            passphrase = "password123".toCharArray(),
            chunkBytes = 400
        )
        assertTrue(prepared.frames.size > 20)

        val frames = prepared.frames.map { TransferProtocol.parse(it) }
        val outStream = ByteArrayOutputStream()
        val meta = TransferProtocol.reconstructToStream(
            frames = frames,
            outStream = outStream,
            passphrase = "password123".toCharArray()
        )

        assertEquals("stream_test.zip", meta.name)
        assertEquals(source.size.toLong(), meta.size)
        assertEquals(prepared.hash, meta.hash)
        assertArrayEquals(source, outStream.toByteArray())
    }

    @Test
    fun wrongPassphraseIsRejected() {
        val prepared = TransferProtocol.prepare(
            "secret".toByteArray(), "secret.txt", "text/plain", "right passphrase".toCharArray()
        )
        val frames = prepared.frames.map { TransferProtocol.parse(it) }
        try {
            TransferProtocol.decrypt(frames, "wrong passphrase".toCharArray())
            fail("Wrong passphrase must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Wrong passphrase"))
        }
    }

    @Test
    fun duplicateFramesDoNotChangeReconstruction() {
        val source = ByteArray(4_500) { it.toByte() }
        val prepared = TransferProtocol.prepare(
            source, "file.bin", "application/octet-stream", "a good passphrase".toCharArray(), chunkBytes = 1280
        )
        val unique = prepared.frames.map { TransferProtocol.parse(it) }
        val duplicated = unique + unique.first() + unique[unique.size / 2]
        val decoded = TransferProtocol.reconstruct(duplicated, "a good passphrase".toCharArray())
        assertArrayEquals(source, decoded.data)
    }

    @Test
    fun corruptedPayloadIsDetected() {
        val source = "Important tamper test data".toByteArray()
        val prepared = TransferProtocol.prepare(source, "data.bin", "application/octet-stream", "passphrase123".toCharArray())
        val frame = TransferProtocol.parse(prepared.frames.first())
        val tamperedPayload = frame.payload.copyOf()
        tamperedPayload[0] = (tamperedPayload[0].toInt() xor 0xFF).toByte()

        val tamperedFrame = frame.copy(payload = tamperedPayload)
        try {
            TransferProtocol.reconstruct(listOf(tamperedFrame), "passphrase123".toCharArray())
            fail("Corrupted payload must fail decryption/integrity check")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Wrong passphrase") || expected.message.orEmpty().contains("corrupted"))
        }
    }

    @Test
    fun corruptedUnencryptedPayloadFailsSha256Verification() {
        val source = "Plaintext tamper check".toByteArray()
        val prepared = TransferProtocol.prepare(source, "plain.txt", "text/plain", null)
        val frame = TransferProtocol.parse(prepared.frames.first())
        val tamperedPayload = frame.payload.copyOf()
        tamperedPayload[0] = (tamperedPayload[0].toInt() xor 0xFF).toByte()

        val tamperedFrame = frame.copy(payload = tamperedPayload)
        try {
            TransferProtocol.reconstruct(listOf(tamperedFrame), null)
            fail("Corrupted plaintext must fail SHA-256 verification")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("SHA-256 verification failed"))
        }
    }

    @Test
    fun incompleteFramesAreRejected() {
        val source = ByteArray(5_000) { (it % 256).toByte() }
        val prepared = TransferProtocol.prepare(source, "file.zip", "application/zip", null, chunkBytes = 1000)
        assertTrue(prepared.frames.size > 1)
        val partialFrames = prepared.frames.drop(1).map { TransferProtocol.parse(it) }

        try {
            TransferProtocol.reconstruct(partialFrames, null)
            fail("Incomplete frame set must fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Incomplete transfer") || expected.message.orEmpty().contains("Missing frame"))
        }
    }

    @Test
    fun shortPassphraseThrowsException() {
        try {
            TransferProtocol.prepare("data".toByteArray(), "file.txt", "text/plain", "short".toCharArray())
            fail("Passphrase < 8 chars must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("at least 8 characters"))
        }
    }

    @Test
    fun emptyDataThrowsException() {
        try {
            TransferProtocol.prepare(ByteArray(0), "empty.txt", "text/plain", null)
            fail("Empty file must throw exception")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Empty files"))
        }
    }

    @Test
    fun fileNameSanitization() {
        val source = "test".toByteArray()
        val prepared = TransferProtocol.prepare(source, "../../../etc/passwd", "text/plain", null)
        val frame = TransferProtocol.parse(prepared.frames.first())
        assertFalse(frame.name.contains("/"))
        assertFalse(frame.name.contains("\\"))
    }

    @Test
    fun customChunkSizeAdaptive() {
        val source = ByteArray(3000) { it.toByte() }
        val preparedSmall = TransferProtocol.prepare(source, "data.zip", "application/zip", null, chunkBytes = 500)
        val preparedLarge = TransferProtocol.prepare(source, "data.zip", "application/zip", null, chunkBytes = 1500)

        assertTrue(preparedSmall.frames.size > preparedLarge.frames.size)
        val decodedSmall = TransferProtocol.reconstruct(preparedSmall.frames.map { TransferProtocol.parse(it) }, null)
        val decodedLarge = TransferProtocol.reconstruct(preparedLarge.frames.map { TransferProtocol.parse(it) }, null)

        assertArrayEquals(source, decodedSmall.data)
        assertArrayEquals(source, decodedLarge.data)
    }

    @Test
    fun boundarySizeVerificationAroundLimit() {
        assertEquals(50 * 1024 * 1024, TransferProtocol.MAX_FILE_BYTES)

        try {
            val oversized = TransferProtocol.MAX_FILE_BYTES.toLong() + 1L
            TransferProtocol.prepareFromStream(
                stream = ByteArrayInputStream(ByteArray(10)),
                totalSize = oversized,
                fileName = "too_big.iso",
                mime = "application/x-iso9660-image"
            )
            fail("Files exceeding 50 MiB must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("50 MiB"))
        }
    }

    @Test
    fun binaryProtocolV2MagicAndParsing() {
        val source = "Binary Protocol v2 Verification Payload".toByteArray()
        val prepared = TransferProtocol.prepare(source, "v2test.bin", "application/octet-stream", null)
        assertEquals(2, prepared.version)
        val frameStr = prepared.frames.first()

        // Check magic bytes: 'A', 'G', 0x02
        val bytes = frameStr.toByteArray(Charsets.ISO_8859_1)
        assertEquals(0x41.toByte(), bytes[0])
        assertEquals(0x47.toByte(), bytes[1])
        assertEquals(0x02.toByte(), bytes[2])

        // Parse from ByteArray
        val frameFromBytes = TransferProtocol.parse(bytes)
        assertEquals(2, frameFromBytes.version)
        assertEquals("v2test.bin", frameFromBytes.name)

        // Parse from String
        val frameFromStr = TransferProtocol.parse(frameStr)
        assertEquals(frameFromBytes, frameFromStr)
    }

    @Test
    fun dynamicCompressionSkipsZipFiles() {
        // Create simulated ZIP header PK\x03\x04
        val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val pseudoZipData = zipHeader + ByteArray(5000) { (it * 73 % 256).toByte() }

        val prepared = TransferProtocol.prepare(pseudoZipData, "archive.zip", "application/zip", null)
        assertFalse("ZIP files must not be re-compressed", prepared.isCompressed)

        val frame = TransferProtocol.parse(prepared.frames.first())
        assertFalse(frame.compressed)

        val decoded = TransferProtocol.reconstruct(prepared.frames.map { TransferProtocol.parse(it) }, null)
        assertArrayEquals(pseudoZipData, decoded.data)
    }

    @Test
    fun dynamicCompressionCompressesRepetitiveText() {
        val repetitiveText = "AIRGAP_OFFLINE_HIGH_EFFICIENCY_TRANSFER_PROTOCOL_V2\n".repeat(200).toByteArray()
        val prepared = TransferProtocol.prepare(repetitiveText, "log.txt", "text/plain", null)
        assertTrue("Repetitive text must be compressed", prepared.isCompressed)

        val frame = TransferProtocol.parse(prepared.frames.first())
        assertTrue(frame.compressed)

        val decoded = TransferProtocol.reconstruct(prepared.frames.map { TransferProtocol.parse(it) }, null)
        assertArrayEquals(repetitiveText, decoded.data)
        assertEquals(repetitiveText.size.toLong(), decoded.data.size.toLong())
    }

    @Test
    fun backwardCompatibilityV1JsonFrames() {
        val session = "0123456789abcdef"
        val payload = "Hello from v1 legacy sender".toByteArray()
        val b64Payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val hash = TransferProtocol.sha256Hex(payload)

        val v1Json = JSONObject().apply {
            put("v", 1)
            put("sid", session)
            put("i", 0)
            put("n", 1)
            put("name", "legacy.txt")
            put("mime", "text/plain")
            put("size", payload.size.toLong())
            put("enc", 0)
            put("hash", hash)
            put("data", b64Payload)
        }.toString()

        val parsed = TransferProtocol.parse(v1Json)
        assertEquals(1, parsed.version)
        assertEquals("legacy.txt", parsed.name)
        assertEquals(session, parsed.session)
        assertArrayEquals(payload, parsed.payload)

        val decoded = TransferProtocol.reconstruct(listOf(parsed), null)
        assertEquals("legacy.txt", decoded.name)
        assertArrayEquals(payload, decoded.data)
    }

    @Test
    fun encryptionOverheadMeasurement() {
        val source = ByteArray(1280) { (it % 256).toByte() }
        val plainPrepared = TransferProtocol.prepare(source, "plain.zip", "application/zip", null, chunkBytes = 1280)
        val encPrepared = TransferProtocol.prepare(source, "enc.zip", "application/zip", "strongpassphrase".toCharArray(), chunkBytes = 1280)

        val plainFrameBytes = plainPrepared.frames.first().toByteArray(Charsets.ISO_8859_1)
        val encFrameBytes = encPrepared.frames.first().toByteArray(Charsets.ISO_8859_1)

        val plainFrame = TransferProtocol.parse(plainFrameBytes)
        val encFrame = TransferProtocol.parse(encFrameBytes)

        // Plain frame overhead: Header without Salt/IV is ~97 bytes
        val plainOverhead = plainFrameBytes.size - plainFrame.payload.size
        // Encrypted frame overhead: Plain overhead + 16 bytes Salt + 12 bytes IV + 16 bytes GCM tag
        val encOverhead = encFrameBytes.size - source.size

        // Total encryption addition: 16 (salt) + 12 (IV) + 16 (tag) = 44 bytes total per single frame
        assertEquals(44, encOverhead - plainOverhead)
        // Payload efficiency for 1,280 byte chunk is > 90%
        val efficiency = (source.size.toDouble() / encFrameBytes.size.toDouble()) * 100.0
        assertTrue("Payload efficiency should be > 90%, but was $efficiency%", efficiency > 90.0)
    }

    @Test
    fun benchmarkCalculationsFor27MBAnd50MiB() {
        val size27MB = 28_311_552L // 27 MiB
        val size50MB = 52_428_800L // 50 MiB

        // 1. Calculate old v1 frame count at 360 bytes/chunk
        val oldV1Chunk = 360
        val oldFrames27MB = (size27MB + oldV1Chunk - 1) / oldV1Chunk
        val oldFrames50MB = (size50MB + oldV1Chunk - 1) / oldV1Chunk
        assertEquals(78644L, oldFrames27MB)
        assertEquals(145636L, oldFrames50MB)

        // 2. Calculate new v2 frame counts
        val compactChunk = 768
        val standardChunk = 1280
        val denseChunk = 1800
        val ultraChunk = 2300

        val v2Compact27MB = (size27MB + compactChunk - 1) / compactChunk
        val v2Standard27MB = (size27MB + standardChunk - 1) / standardChunk
        val v2Dense27MB = (size27MB + denseChunk - 1) / denseChunk
        val v2Ultra27MB = (size27MB + ultraChunk - 1) / ultraChunk

        assertEquals(36864L, v2Compact27MB)
        assertEquals(22119L, v2Standard27MB)
        assertEquals(15729L, v2Dense27MB)
        assertEquals(12310L, v2Ultra27MB)

        val v2Standard50MB = (size50MB + standardChunk - 1) / standardChunk
        val v2Dense50MB = (size50MB + denseChunk - 1) / denseChunk
        val v2Ultra50MB = (size50MB + ultraChunk - 1) / ultraChunk

        assertEquals(40960L, v2Standard50MB)
        assertEquals(29128L, v2Dense50MB)
        assertEquals(22796L, v2Ultra50MB)

        // Assert frame reductions are between 71.8% and 84.3%
        val standardReduction27MB = (oldFrames27MB - v2Standard27MB).toDouble() / oldFrames27MB * 100.0
        val denseReduction27MB = (oldFrames27MB - v2Dense27MB).toDouble() / oldFrames27MB * 100.0
        val ultraReduction27MB = (oldFrames27MB - v2Ultra27MB).toDouble() / oldFrames27MB * 100.0

        assertTrue("Standard reduction should be > 70%", standardReduction27MB > 70.0)
        assertTrue("Dense reduction should be > 79%", denseReduction27MB > 79.0)
        assertTrue("Ultra reduction should be > 84%", ultraReduction27MB > 84.0)

        // Estimated durations for 27 MB ZIP file
        // At 5 fps (200ms per frame):
        val durationOldAt5fpsSeconds = oldFrames27MB / 5.0
        val durationStandardAt5fpsSeconds = v2Standard27MB / 5.0
        val durationDenseAt5fpsSeconds = v2Dense27MB / 5.0

        assertTrue(durationOldAt5fpsSeconds > 15000) // ~4.3 hours
        assertTrue(durationStandardAt5fpsSeconds < 4500) // ~1.2 hours
        assertTrue(durationDenseAt5fpsSeconds < 3200) // ~52 minutes
    }
}
