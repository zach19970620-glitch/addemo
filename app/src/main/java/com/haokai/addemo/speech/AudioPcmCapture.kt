package com.haokai.addemo.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 16kHz / 16bit / mono PCM，按 1280 字节一块回调（约 40ms）。
 */
class AudioPcmCapture {

    companion object {
        private const val TAG = "AudioPcmCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME_BYTES = 1280
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(onFrame: (ByteArray) -> Unit): Boolean {
        // 已在采麦：视为成功（避免 onSessionReady 重复回调误报「无法打开麦克风」）
        if (running.get()) {
            Log.w(TAG, "start ignored: already running")
            return true
        }
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "start ignored: race already running")
            return true
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            running.set(false)
            Log.e(TAG, "invalid minBufferSize=$minBuf")
            return false
        }
        val bufferSize = maxOf(minBuf, FRAME_BYTES * 4)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferSize,
            )
        } catch (e: Exception) {
            running.set(false)
            Log.e(TAG, "AudioRecord create failed", e)
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            running.set(false)
            Log.e(TAG, "AudioRecord not initialized")
            return false
        }
        record = recorder
        attachNoiseSuppressor(recorder.audioSessionId)
        recorder.startRecording()
        worker = Thread({
            val buf = ByteArray(FRAME_BYTES)
            while (running.get()) {
                val n = recorder.read(buf, 0, FRAME_BYTES)
                if (n > 0 && running.get()) {
                    val frame = if (n == FRAME_BYTES) buf.copyOf() else buf.copyOf(n)
                    try {
                        onFrame(frame)
                    } catch (e: Exception) {
                        Log.w(TAG, "onFrame error", e)
                    }
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord read error: $n")
                    break
                }
            }
        }, "pcm-capture").also { it.start() }
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        releaseNoiseSuppressor()
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        try {
            worker?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        worker = null
    }

    private fun attachNoiseSuppressor(sessionId: Int) {
        releaseNoiseSuppressor()
        if (!NoiseSuppressor.isAvailable()) {
            Log.i(TAG, "NoiseSuppressor unsupported")
            return
        }
        runCatching {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.also {
                it.enabled = true
                Log.i(TAG, "NoiseSuppressor enabled")
            }
        }.onFailure {
            Log.w(TAG, "NoiseSuppressor failed: ${it.message}")
        }
    }

    private fun releaseNoiseSuppressor() {
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
    }
}
