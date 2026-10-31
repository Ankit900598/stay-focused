package com.phonecodex.app.domain.promise

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal class PromiseAudioRecorder(private val context: Context) {
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var activeRecorder: AudioRecord? = null

    fun requestStop() {
        stopRequested.set(true)
        val recorder = activeRecorder ?: return
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { recorder.stop() }
        }
    }

    fun prepareForCapture() {
        stopRequested.set(false)
    }

    suspend fun recordWav(): ByteArray = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission is unavailable")
        }
        val minBufferBytes = AudioRecord.getMinBufferSize(
            PromiseSpeechClient.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferBytes > 0) { "Microphone format is unavailable" }

        val maxPcmBytes = PromiseSpeechClient.SAMPLE_RATE_HZ * 2 *
            PromiseSpeechClient.MAX_RECORDING_SECONDS
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            PromiseSpeechClient.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferBytes, READ_BUFFER_BYTES)
        )
        activeRecorder = recorder
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Microphone could not be initialized"
            }
            recorder.startRecording()
            val pcm = ByteArrayOutputStream(maxPcmBytes)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            val deadline = SystemClock.elapsedRealtime() +
                PromiseSpeechClient.MAX_RECORDING_SECONDS * 1_000L

            while (!stopRequested.get() && SystemClock.elapsedRealtime() < deadline) {
                val bytesRead = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (bytesRead > 0) {
                    val remaining = maxPcmBytes - pcm.size()
                    if (remaining <= 0) break
                    pcm.write(buffer, 0, minOf(bytesRead, remaining))
                } else if (!stopRequested.get()) {
                    error("Microphone capture failed")
                }
            }

            val capturedPcm = pcm.toByteArray()
            check(capturedPcm.isNotEmpty()) { "No speech audio was captured" }
            encodePcm16MonoWav(capturedPcm)
        } finally {
            activeRecorder = null
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
            }
            recorder.release()
        }
    }

    companion object {
        private const val READ_BUFFER_BYTES = 3_200

        internal fun encodePcm16MonoWav(pcmBytes: ByteArray): ByteArray {
            val evenDataLength = pcmBytes.size - (pcmBytes.size % 2)
            require(evenDataLength > 0) { "PCM audio must contain at least one sample" }
            val sampleRate = PromiseSpeechClient.SAMPLE_RATE_HZ
            val output = ByteBuffer.allocate(WAV_HEADER_BYTES + evenDataLength)
                .order(ByteOrder.LITTLE_ENDIAN)
            output.put("RIFF".toByteArray(Charsets.US_ASCII))
            output.putInt(36 + evenDataLength)
            output.put("WAVE".toByteArray(Charsets.US_ASCII))
            output.put("fmt ".toByteArray(Charsets.US_ASCII))
            output.putInt(16)
            output.putShort(1.toShort())
            output.putShort(1.toShort())
            output.putInt(sampleRate)
            output.putInt(sampleRate * 2)
            output.putShort(2.toShort())
            output.putShort(16.toShort())
            output.put("data".toByteArray(Charsets.US_ASCII))
            output.putInt(evenDataLength)
            output.put(pcmBytes, 0, evenDataLength)
            return output.array()
        }

        private const val WAV_HEADER_BYTES = 44
    }
}
