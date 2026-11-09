package com.haokai.addemo.speech

import android.util.Log
import com.algorithm.audio.AudioProcessor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 可选 AEC：mic + ref → out（段长 240 sample / 480 bytes）。
 * 海报态播片已暂停时收益有限；无可靠 ref 时勿强开。
 */
class AecAudioProcessor {

    companion object {
        private const val TAG = "AecAudioProcessor"
        const val SEG_SAMPLES = 240
        const val SEG_BYTES = SEG_SAMPLES * 2
    }

    private val ready = AtomicBoolean(false)
    private val micCarry = ArrayList<Byte>(SEG_BYTES * 2)
    private val refCarry = ArrayList<Byte>(SEG_BYTES * 2)
    private val outSeg = ByteArray(SEG_BYTES)
    private val micSeg = ByteArray(SEG_BYTES)
    private val refSeg = ByteArray(SEG_BYTES)

    fun init(): Boolean {
        return runCatching {
            AudioProcessor.getInstance().init()
            ready.set(true)
            Log.i(TAG, "AudioProcessor init ok")
            true
        }.getOrElse {
            Log.w(TAG, "AudioProcessor init failed: ${it.message}")
            ready.set(false)
            false
        }
    }

    fun isReady(): Boolean = ready.get()

    /**
     * 处理 mic 帧；ref 不足时用静音填充（弱 AEC，仅保证链路可跑）。
     * 输出可能一次回调多段。
     */
    fun process(micPcm: ByteArray, refPcm: ByteArray?, onClean: (ByteArray) -> Unit) {
        if (!ready.get()) {
            onClean(micPcm)
            return
        }
        append(micCarry, micPcm)
        if (refPcm != null) {
            append(refCarry, refPcm)
        }
        while (micCarry.size >= SEG_BYTES) {
            drain(micCarry, micSeg)
            if (refCarry.size >= SEG_BYTES) {
                drain(refCarry, refSeg)
            } else {
                refSeg.fill(0)
            }
            runCatching {
                AudioProcessor.getInstance().process(micSeg, refSeg, outSeg)
                onClean(outSeg.copyOf())
            }.onFailure {
                Log.w(TAG, "process failed, passthrough: ${it.message}")
                onClean(micSeg.copyOf())
            }
        }
    }

    fun release() {
        if (!ready.getAndSet(false)) return
        runCatching { AudioProcessor.getInstance().release() }
        micCarry.clear()
        refCarry.clear()
    }

    private fun append(buf: ArrayList<Byte>, data: ByteArray) {
        for (b in data) buf.add(b)
    }

    private fun drain(buf: ArrayList<Byte>, out: ByteArray) {
        for (i in out.indices) {
            out[i] = buf.removeAt(0)
        }
    }
}
