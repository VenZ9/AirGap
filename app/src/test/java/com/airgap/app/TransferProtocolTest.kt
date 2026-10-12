package com.airgap.app

import org.junit.Assert.*
import org.junit.Test

class TransferProtocolTest {
    @Test fun roundTripOneChunk() {
        val source = "AirGap offline test payload".toByteArray()
        val prepared = TransferProtocol.prepare(source, "test.txt", "text/plain", "correct horse".toCharArray())
        val frames = prepared.frames.map { TransferProtocol.parse(it) }
        val (name, output) = TransferProtocol.decrypt(frames, "correct horse".toCharArray())
        assertEquals("test.txt", name)
        assertArrayEquals(source, output)
    }

    @Test fun roundTripMultiChunkAndOutOfOrderFrames() {
        val source = ByteArray(8_192) { (it * 31 % 251).toByte() }
        val prepared = TransferProtocol.prepare(source, "sample.zip", "application/zip", "a strong passphrase".toCharArray())
        assertTrue(prepared.frames.size > 1)
        val frames = prepared.frames.reversed().map { TransferProtocol.parse(it) }
        val (_, output) = TransferProtocol.decrypt(frames, "a strong passphrase".toCharArray())
        assertArrayEquals(source, output)
    }

    @Test fun wrongPassphraseIsRejected() {
        val prepared = TransferProtocol.prepare("secret".toByteArray(), "secret.txt", "text/plain", "right passphrase".toCharArray())
        val frames = prepared.frames.map { TransferProtocol.parse(it) }
        try {
            TransferProtocol.decrypt(frames, "wrong passphrase".toCharArray())
            fail("Wrong passphrase must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Wrong passphrase"))
        }
    }

    @Test fun duplicateFramesDoNotChangeReconstruction() {
        val source = ByteArray(1_500) { it.toByte() }
        val prepared = TransferProtocol.prepare(source, "file.bin", "application/octet-stream", "a good passphrase".toCharArray())
        val unique = prepared.frames.map { TransferProtocol.parse(it) }
        val (_, output) = TransferProtocol.decrypt(unique + unique.first(), "a good passphrase".toCharArray())
        assertArrayEquals(source, output)
    }
}
