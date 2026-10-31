package com.phonecodex.app.domain.promise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PromiseAudioRecorderTest {

    @Test
    fun encodesPcmAsMonoSixteenBitSixteenKhzWav() {
        val pcm = byteArrayOf(0x11, 0x22, 0x33, 0x44)

        val wav = PromiseAudioRecorder.encodePcm16MonoWav(pcm)

        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(wav.size - 8, header.getInt(4))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(pcm.size, header.getInt(40))
        assertTrue(pcm.contentEquals(wav.copyOfRange(44, wav.size)))
    }

    @Test
    fun dropsIncompleteTrailingPcmByteAndRejectsEmptySamples() {
        val wav = PromiseAudioRecorder.encodePcm16MonoWav(byteArrayOf(0x01, 0x02, 0x03))

        assertEquals(46, wav.size)
        assertEquals(2, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        assertThrows(IllegalArgumentException::class.java) {
            PromiseAudioRecorder.encodePcm16MonoWav(byteArrayOf(0x01))
        }
    }
}
