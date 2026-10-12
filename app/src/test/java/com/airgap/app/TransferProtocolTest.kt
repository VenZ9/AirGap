package com.airgap.app

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

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
            source, "sample.zip", "application/zip", "a strong passphrase".toCharArray()
        )
        assertTrue(prepared.frames.size > 1)

        val frames = prepared.frames.reversed().map { TransferProtocol.parse(it) }
        val decoded = TransferProtocol.reconstruct(frames, "a strong passphrase".toCharArray())
        assertEquals("sample.zip", decoded.name)
        assertArrayEquals(source, decoded.data)
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
        val source = ByteArray(1_500) { it.toByte() }
        val prepared = TransferProtocol.prepare(
            source, "file.bin", "application/octet-stream", "a good passphrase".toCharArray()
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
        val source = ByteArray(2_000) { (it % 256).toByte() }
        val prepared = TransferProtocol.prepare(source, "file.bin", "application/octet-stream", null)
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
        val source = ByteArray(1000) { it.toByte() }
        val preparedSmall = TransferProtocol.prepare(source, "data.bin", "application/octet-stream", null, chunkBytes = 150)
        val preparedLarge = TransferProtocol.prepare(source, "data.bin", "application/octet-stream", null, chunkBytes = 600)

        assertTrue(preparedSmall.frames.size > preparedLarge.frames.size)
        val decodedSmall = TransferProtocol.reconstruct(preparedSmall.frames.map { TransferProtocol.parse(it) }, null)
        val decodedLarge = TransferProtocol.reconstruct(preparedLarge.frames.map { TransferProtocol.parse(it) }, null)

        assertArrayEquals(source, decodedSmall.data)
        assertArrayEquals(source, decodedLarge.data)
    }
}
