package org.retroamp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 10-band graphic equalizer with preamp, operating on interleaved stereo float PCM.
 * Each band is a peaking biquad (Audio EQ Cookbook formulas).
 */
class Equalizer10 {
    companion object {
        /** Band centre frequencies of the classic 10-band layout. */
        val FREQS = floatArrayOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)
        const val BANDS = 10
        const val MAX_DB = 12f
        private const val Q = 1.4f
    }

    @Volatile var enabled = true
    @Volatile var preampDb = 0f
    val gains = FloatArray(BANDS)

    @Volatile private var dirty = true
    private var sampleRate = 44100
    private val b0 = FloatArray(BANDS)
    private val b1 = FloatArray(BANDS)
    private val b2 = FloatArray(BANDS)
    private val a1 = FloatArray(BANDS)
    private val a2 = FloatArray(BANDS)
    private val active = BooleanArray(BANDS)
    // Per channel, per band: x1, x2, y1, y2
    private val state = Array(2) { FloatArray(BANDS * 4) }

    fun setGain(band: Int, db: Float) {
        gains[band] = db.coerceIn(-MAX_DB, MAX_DB)
        dirty = true
    }

    fun setSampleRate(sr: Int) {
        if (sr != sampleRate) {
            sampleRate = sr
            dirty = true
            reset()
        }
    }

    fun reset() {
        state[0].fill(0f)
        state[1].fill(0f)
    }

    private fun update() {
        dirty = false
        for (i in 0 until BANDS) {
            val f = FREQS[i]
            val g = gains[i]
            if (f >= sampleRate * 0.45f || abs(g) < 0.05f) {
                if (active[i]) {
                    active[i] = false
                    for (ch in 0..1) state[ch].fill(0f, i * 4, i * 4 + 4)
                }
                continue
            }
            val a = 10.0.pow(g / 40.0)
            val w0 = 2.0 * PI * f / sampleRate
            val alpha = sin(w0) / (2.0 * Q)
            val a0 = 1.0 + alpha / a
            b0[i] = ((1.0 + alpha * a) / a0).toFloat()
            b1[i] = ((-2.0 * cos(w0)) / a0).toFloat()
            b2[i] = ((1.0 - alpha * a) / a0).toFloat()
            a1[i] = b1[i]
            a2[i] = ((1.0 - alpha / a) / a0).toFloat()
            active[i] = true
        }
    }

    /** Processes [frames] interleaved stereo frames in place. */
    fun process(buf: FloatArray, frames: Int) {
        if (!enabled) return
        if (dirty) update()
        val pre = 10.0.pow(preampDb / 20.0).toFloat()
        if (pre != 1f) {
            for (i in 0 until frames * 2) buf[i] *= pre
        }
        for (ch in 0..1) {
            val st = state[ch]
            for (band in 0 until BANDS) {
                if (!active[band]) continue
                val c0 = b0[band]; val c1 = b1[band]; val c2 = b2[band]
                val d1 = a1[band]; val d2 = a2[band]
                val o = band * 4
                var x1 = st[o]; var x2 = st[o + 1]; var y1 = st[o + 2]; var y2 = st[o + 3]
                var idx = ch
                for (n in 0 until frames) {
                    val x = buf[idx]
                    val y = c0 * x + c1 * x1 + c2 * x2 - d1 * y1 - d2 * y2
                    x2 = x1; x1 = x; y2 = y1; y1 = y
                    buf[idx] = y
                    idx += 2
                }
                // Flush denormals so silent passages stay cheap.
                if (abs(y1) < 1e-20f) y1 = 0f
                if (abs(y2) < 1e-20f) y2 = 0f
                st[o] = x1; st[o + 1] = x2; st[o + 2] = y1; st[o + 3] = y2
            }
        }
    }
}
