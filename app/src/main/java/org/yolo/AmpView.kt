package org.yolo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole UI: three classic-style "windows" (main, equalizer, playlist) drawn in a
 * 275-unit-wide logical coordinate space and scaled to whatever screen we are given.
 * Tall screens stack the windows; wide screens put the playlist beside the player.
 */
class AmpView(private val host: MainActivity) : View(host) {

    var svc: PlayerService? = null

    private companion object {
        const val WIN_W = 275f
        const val WIN_H = 116f
        const val ROW_H = 12f

        const val BODY = 0xFF30323E.toInt()
        const val BTN = 0xFF3C3F4E.toInt()
        const val HI = 0xFF6A6E86.toInt()
        const val LO = 0xFF14151C.toInt()
        const val TITLE = 0xFF1C1D26.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val LED = 0xFF00E000.toInt()
        const val LED_DIM = 0xFF0B3A0B.toInt()
        const val AMBER = 0xFFE8B030.toInt()
        const val TXT = 0xFFC8CCDC.toInt()
        const val SEL = 0xFF1E2A8A.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        // Control ids
        const val NONE = 0
        const val PREV = 1; const val PLAY = 2; const val PAUSE = 3; const val STOP = 4
        const val NEXT = 5; const val EJECT = 6; const val SHUF = 7; const val REP = 8
        const val EQBTN = 9; const val PLBTN = 10; const val VOL = 11; const val BAL = 12
        const val SEEK = 13; const val TIME = 14; const val VIS = 15
        const val EQON = 20; const val PRESET = 21; const val PREAMP = 22
        const val BAND0 = 100
        const val PL_LIST = 30; const val PL_ADDF = 31; const val PL_ADDD = 32
        const val PL_REM = 33; const val PL_CLR = 34

        const val FFT_N = 512
        const val BARS = 19

        // 7-segment map: bits a,b,c,d,e,f,g
        val SEG = intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)
        val BAND_LABELS = arrayOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")
    }

    private val prefs = host.getSharedPreferences("retroamp", Context.MODE_PRIVATE)
    private var showEq = prefs.getBoolean("showEq", true)
    private var showPl = prefs.getBoolean("showPl", true)
    private var timeRemaining = prefs.getBoolean("timeRemaining", false)
    private var visMode = prefs.getInt("visMode", 0) // 0 spectrum, 1 scope, 2 off

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val path = Path()

    private var scale = 1f
    private val mainR = RectF()
    private val eqR = RectF()
    private val plR = RectF()

    private var active = NONE
    private var activeWin = 0
    private var inside = false
    private var dragSeek = -1f

    private var plScroll = 0f
    private var selected = -1
    private var lastTapRow = -1
    private var lastTapTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var moved = false

    private val re = FloatArray(FFT_N)
    private val im = FloatArray(FFT_N)
    private val window = FloatArray(FFT_N) { (0.5 - 0.5 * cos(2.0 * PI * it / (FFT_N - 1))).toFloat() }
    private val bars = FloatArray(BARS)
    private val peaks = FloatArray(BARS)

    private fun savePrefs() {
        prefs.edit().putBoolean("showEq", showEq).putBoolean("showPl", showPl)
            .putBoolean("timeRemaining", timeRemaining).putInt("visMode", visMode).apply()
    }

    // ------------------------------------------------------------------ layout

    private fun layoutWindows() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val stackH = WIN_H + (if (showEq) WIN_H else 0f)
        if (!showPl) {
            scale = min(w / WIN_W, h / stackH)
            val lx = (w / scale - WIN_W) / 2f
            val ly = (h / scale - stackH) / 2f
            mainR.set(lx, ly, lx + WIN_W, ly + WIN_H)
            eqR.set(lx, ly + WIN_H, lx + WIN_W, ly + WIN_H * 2)
            plR.setEmpty()
            return
        }
        val stacked = min(w / WIN_W, h / (stackH + 80f))
        val side = min(h / stackH, w / (WIN_W + 200f))
        if (stacked >= side) {
            scale = stacked
            val lx = (w / scale - WIN_W) / 2f
            mainR.set(lx, 0f, lx + WIN_W, WIN_H)
            eqR.set(lx, WIN_H, lx + WIN_W, WIN_H * 2)
            plR.set(lx, stackH, lx + WIN_W, h / scale)
        } else {
            scale = side
            mainR.set(0f, 0f, WIN_W, WIN_H)
            eqR.set(0f, WIN_H, WIN_W, WIN_H * 2)
            plR.set(WIN_W, 0f, w / scale, h / scale)
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    private fun fill(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawRect(l, t, r, b, paint)
    }

    private fun bevel(c: Canvas, l: Float, t: Float, r: Float, b: Float, up: Boolean, face: Int) {
        fill(c, l, t, r, b, face)
        val a = if (up) HI else LO
        val z = if (up) LO else HI
        fill(c, l, t, r, t + 1, a)
        fill(c, l, t, l + 1, b, a)
        fill(c, l, b - 1, r, b, z)
        fill(c, r - 1, t, r, b, z)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
                     align: Paint.Align = Paint.Align.LEFT) {
        paint.style = Paint.Style.FILL
        paint.typeface = mono
        paint.textSize = size
        paint.color = color
        paint.textAlign = align
        c.drawText(s, x, y, paint)
    }

    private fun button(c: Canvas, l: Float, t: Float, r: Float, b: Float, id: Int,
                       label: String? = null, lit: Boolean = false, size: Float = 7f): Boolean {
        val down = active == id && inside
        bevel(c, l, t, r, b, !down, BTN)
        if (label != null) {
            text(c, label, (l + r) / 2f, (t + b) / 2f + size * 0.36f, size, if (lit) LED else TXT, Paint.Align.CENTER)
        }
        return down
    }

    private fun frame(c: Canvas, w: Float, h: Float, title: String) {
        bevel(c, 0f, 0f, w, h, true, BODY)
        fill(c, 3f, 3f, w - 3f, 13f, TITLE)
        paint.typeface = mono
        paint.textSize = 7.5f
        val tw = paint.measureText(title)
        val cx = w / 2f
        var y = 5f
        while (y < 12f) {
            fill(c, 6f, y, cx - tw / 2f - 5f, y + 1f, AMBER)
            fill(c, cx + tw / 2f + 5f, y, w - 6f, y + 1f, AMBER)
            y += 2.5f
        }
        text(c, title, cx, 10.7f, 7.5f, TXT, Paint.Align.CENTER)
    }

    private fun tri(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int) {
        path.rewind()
        path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawPath(path, paint)
    }

    private fun digit(c: Canvas, x: Float, y: Float, d: Int, on: Boolean) {
        val m = if (d in 0..9) SEG[d] else 0
        val w = 9f; val h = 13f; val t = 2f; val mid = y + (h - t) / 2f
        fun seg(bit: Int, l: Float, tp: Float, r: Float, b: Float) {
            val lit = on && (m shr bit and 1) == 1
            fill(c, l, tp, r, b, if (lit) LED else LED_DIM)
        }
        seg(0, x + 1, y, x + w - 1, y + t)
        seg(1, x + w - t, y + 1, x + w, mid + 1)
        seg(2, x + w - t, mid + 1, x + w, y + h - 1)
        seg(3, x + 1, y + h - t, x + w - 1, y + h)
        seg(4, x, mid + 1, x + t, y + h - 1)
        seg(5, x, y + 1, x + t, mid + 1)
        seg(6, x + 1, mid, x + w - 1, mid + t)
    }

    private fun fmt(ms: Long): String {
        val s = max(0L, ms) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    // ------------------------------------------------------------------ onDraw

    override fun onDraw(c: Canvas) {
        layoutWindows()
        c.drawColor(BLACK)
        val s = svc
        c.save()
        c.scale(scale, scale)
        c.save(); c.translate(mainR.left, mainR.top); drawMain(c, s); c.restore()
        if (showEq) { c.save(); c.translate(eqR.left, eqR.top); drawEq(c, s); c.restore() }
        if (showPl) { c.save(); c.translate(plR.left, plR.top); drawPlaylist(c, s, plR.width(), plR.height()); c.restore() }
        c.restore()
        postInvalidateDelayed(if (s != null && s.state == PlayerService.PLAYING) 33L else 250L)
    }

    private fun drawMain(c: Canvas, s: PlayerService?) {
        frame(c, WIN_W, WIN_H, "RETROAMP")
        val state = s?.state ?: PlayerService.STOPPED
        val now = SystemClock.uptimeMillis()

        // Left display: status icon, time, visualizer
        bevel(c, 9f, 20f, 97f, 66f, false, BLACK)
        when (state) {
            PlayerService.PLAYING -> tri(c, 15f, 26f, 15f, 36f, 22f, 31f, LED)
            PlayerService.PAUSED -> { fill(c, 15f, 26f, 18f, 36f, LED); fill(c, 20f, 26f, 23f, 36f, LED) }
            else -> fill(c, 15f, 27f, 23f, 35f, LED)
        }
        val pos = s?.positionMs ?: 0L
        val dur = s?.durationMs ?: 0L
        val showDigits = state == PlayerService.PLAYING || (state == PlayerService.PAUSED && (now / 500) % 2 == 0L)
        val neg = timeRemaining && dur > 0
        val shown = (if (neg) dur - pos else pos).coerceAtLeast(0L) / 1000
        val mm = ((shown / 60) % 100).toInt()
        val ss = (shown % 60).toInt()
        if (neg && showDigits) fill(c, 29f, 30f, 34f, 32f, LED)
        digit(c, 37f, 24f, mm / 10, showDigits)
        digit(c, 49f, 24f, mm % 10, showDigits)
        val colon = if (showDigits) LED else LED_DIM
        fill(c, 61.5f, 28f, 63.5f, 30f, colon)
        fill(c, 61.5f, 33f, 63.5f, 35f, colon)
        digit(c, 67f, 24f, ss / 10, showDigits)
        digit(c, 79f, 24f, ss % 10, showDigits)
        drawVis(c, s, state)

        // Song title marquee
        bevel(c, 103f, 20f, 267f, 34f, false, BLACK)
        val t = s?.current
        val title = if (t == null) "RETROAMP  -  ADD FILES TO BEGIN"
        else "${(s.index + 1)}. ${t.title} (${fmt(if (dur > 0) dur else t.durationMs)})"
        c.save()
        c.clipRect(105f, 21f, 265f, 33f)
        paint.typeface = mono
        paint.textSize = 8.5f
        val tw = paint.measureText(title)
        if (tw <= 160f) {
            text(c, title, 106f, 30.2f, 8.5f, LED)
        } else {
            val loop = "$title  ***  "
            val lw = paint.measureText(loop)
            val off = (now / 1000f * 28f) % lw
            text(c, loop, 106f - off, 30.2f, 8.5f, LED)
            text(c, loop, 106f - off + lw, 30.2f, 8.5f, LED)
        }
        c.restore()

        // Bitrate / sample rate / channels
        val e = s?.engine
        bevel(c, 103f, 37f, 123f, 48f, false, BLACK)
        bevel(c, 147f, 37f, 163f, 48f, false, BLACK)
        if (e != null && state != PlayerService.STOPPED) {
            if (e.bitrateKbps > 0) text(c, e.bitrateKbps.toString(), 121f, 45.6f, 7.5f, LED, Paint.Align.RIGHT)
            if (e.sampleRate > 0) text(c, (e.sampleRate / 1000).toString(), 161f, 45.6f, 7.5f, LED, Paint.Align.RIGHT)
        }
        text(c, "kbps", 125f, 45.6f, 6.5f, TXT)
        text(c, "kHz", 165f, 45.6f, 6.5f, TXT)
        val ch = if (state != PlayerService.STOPPED) e?.channels ?: 0 else 0
        text(c, "MONO", 205f, 45.6f, 6.5f, if (ch == 1) LED else LED_DIM)
        text(c, "STEREO", 232f, 45.6f, 6.5f, if (ch >= 2) LED else LED_DIM)

        // Volume and balance
        val vol = e?.volume ?: 0.8f
        bevel(c, 104f, 55f, 172f, 61f, false, BLACK)
        fill(c, 105f, 56f, 105f + 66f * vol, 60f, blend(LED, AMBER, vol))
        val vx = 107f + 62f * vol
        bevel(c, vx - 4f, 52f, vx + 4f, 64f, active != VOL, BTN)
        val bal = e?.balance ?: 0f
        bevel(c, 181f, 55f, 221f, 61f, false, BLACK)
        fill(c, 200.5f, 56f, 201.5f, 60f, LED_DIM)
        val bx = 201f + 16f * bal
        bevel(c, bx - 4f, 52f, bx + 4f, 64f, active != BAL, BTN)
        button(c, 227f, 52f, 246f, 64f, EQBTN, "EQ", showEq)
        button(c, 248f, 52f, 267f, 64f, PLBTN, "PL", showPl)

        // Seek bar
        bevel(c, 12f, 72f, 263f, 82f, false, BLACK)
        if (state != PlayerService.STOPPED && dur > 0) {
            val f = if (dragSeek >= 0f) dragSeek else (pos.toFloat() / dur).coerceIn(0f, 1f)
            val sx = 14f + f * (261f - 14f - 26f)
            bevel(c, sx, 73f, sx + 26f, 81f, active != SEEK, BTN)
            fill(c, sx + 12.5f, 75f, sx + 13.5f, 79f, AMBER)
        }

        // Transport
        var d = button(c, 12f, 88f, 35f, 108f, PREV); var o = if (d) 1f else 0f
        fill(c, 18f + o, 93f + o, 20f + o, 103f + o, TXT); tri(c, 29f + o, 93f + o, 29f + o, 103f + o, 21f + o, 98f + o, TXT)
        d = button(c, 36f, 88f, 59f, 108f, PLAY); o = if (d) 1f else 0f
        tri(c, 43f + o, 93f + o, 43f + o, 103f + o, 53f + o, 98f + o, TXT)
        d = button(c, 60f, 88f, 83f, 108f, PAUSE); o = if (d) 1f else 0f
        fill(c, 67f + o, 93f + o, 70f + o, 103f + o, TXT); fill(c, 73f + o, 93f + o, 76f + o, 103f + o, TXT)
        d = button(c, 84f, 88f, 107f, 108f, STOP); o = if (d) 1f else 0f
        fill(c, 91f + o, 93.5f + o, 100f + o, 102.5f + o, TXT)
        d = button(c, 108f, 88f, 131f, 108f, NEXT); o = if (d) 1f else 0f
        tri(c, 114f + o, 93f + o, 114f + o, 103f + o, 122f + o, 98f + o, TXT); fill(c, 123f + o, 93f + o, 125f + o, 103f + o, TXT)
        d = button(c, 137f, 88f, 160f, 108f, EJECT); o = if (d) 1f else 0f
        tri(c, 143f + o, 99f + o, 154f + o, 99f + o, 148.5f + o, 92.5f + o, TXT); fill(c, 143f + o, 101f + o, 154f + o, 103.5f + o, TXT)
        button(c, 168f, 90f, 215f, 106f, SHUF, "SHUFFLE", s?.shuffle == true)
        button(c, 218f, 90f, 263f, 106f, REP, "REPEAT", s?.repeat == true)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int): Int {
            val x = a shr shift and 0xFF
            val y = b shr shift and 0xFF
            return (x + (y - x) * f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun drawVis(c: Canvas, s: PlayerService?, state: Int) {
        val x0 = 14f; val base = 63f; val maxH = 17f
        if (visMode == 2 || s == null) return
        val e = s.engine
        val playing = state == PlayerService.PLAYING
        if (playing) {
            val start = e.visPos - e.latencyFrames - FFT_N
            val mask = AudioEngine.VIS_SIZE - 1
            for (i in 0 until FFT_N) {
                re[i] = e.vis[(start + i) and mask]
                im[i] = 0f
            }
        }
        if (visMode == 1) {
            if (!playing) return
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = LED
            path.rewind()
            for (i in 0 until 76) {
                val v = re[i * 4].coerceIn(-1f, 1f)
                val y = base - maxH / 2f - v * maxH / 2f
                if (i == 0) path.moveTo(x0 + i, y) else path.lineTo(x0 + i, y)
            }
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            return
        }
        if (playing) {
            for (i in 0 until FFT_N) re[i] *= window[i]
            fft()
        }
        for (b in 0 until BARS) {
            var v = 0f
            if (playing) {
                val lo = 200.0.pow(b / BARS.toDouble()).toInt()
                val hi = max(lo + 1, 200.0.pow((b + 1) / BARS.toDouble()).toInt())
                var m = 0f
                for (k in lo until hi) {
                    val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                    if (mag > m) m = mag
                }
                val db = 20f * log10(m / (FFT_N / 4f) + 1e-9f)
                v = ((db + 60f) / 55f).coerceIn(0f, 1f)
            }
            bars[b] = max(v, bars[b] - 0.07f)
            peaks[b] = max(bars[b], peaks[b] - 0.012f)
            val h = bars[b] * maxH
            val x = x0 + b * 4f
            var y = 0f
            while (y < h) {
                val f = y / maxH
                val col = if (f < 0.6f) blend(LED, AMBER, f / 0.6f) else blend(AMBER, 0xFFE03020.toInt(), (f - 0.6f) / 0.4f)
                fill(c, x, base - min(h, y + 1f), x + 3f, base - y, col)
                y += 1f
            }
            if (peaks[b] > 0.02f) {
                val py = base - peaks[b] * maxH
                fill(c, x, py - 1f, x + 3f, py, TXT)
            }
        }
    }

    private fun fft() {
        val n = FFT_N
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun bandX(i: Int) = 70f + 20f * i

    private fun drawSlider(c: Canvas, x: Float, db: Float, id: Int) {
        bevel(c, x + 4f, 38f, x + 10f, 100f, false, BLACK)
        val f = (db + Equalizer10.MAX_DB) / (2f * Equalizer10.MAX_DB) // 0 bottom .. 1 top
        val ty = 96f - f * 54f
        fill(c, x + 5f, ty, x + 9f, 99f, blend(LED, AMBER, f))
        bevel(c, x, ty - 3.5f, x + 14f, ty + 3.5f, active != id, BTN)
        fill(c, x + 3f, ty - 0.5f, x + 11f, ty + 0.5f, AMBER)
    }

    private fun drawEq(c: Canvas, s: PlayerService?) {
        frame(c, WIN_W, WIN_H, "EQUALIZER")
        val eq = s?.engine?.eq
        button(c, 12f, 18f, 40f, 31f, EQON, "ON", eq?.enabled == true)
        button(c, 208f, 18f, 263f, 31f, PRESET, "PRESETS")
        // Response curve
        bevel(c, 80f, 17f, 190f, 32f, false, BLACK)
        fill(c, 82f, 24f, 188f, 24.5f, LED_DIM)
        if (eq != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = if (eq.enabled) LED else LED_DIM
            path.rewind()
            for (i in 0 until Equalizer10.BANDS) {
                val x = 85f + i * 11f
                val y = 24.25f - eq.gains[i] / Equalizer10.MAX_DB * 5.5f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }
        text(c, "+12", 60f, 44f, 6f, TXT, Paint.Align.RIGHT)
        text(c, "0", 60f, 71f, 6f, TXT, Paint.Align.RIGHT)
        text(c, "-12", 60f, 98f, 6f, TXT, Paint.Align.RIGHT)
        drawSlider(c, 18f, eq?.preampDb ?: 0f, PREAMP)
        text(c, "PRE", 25f, 109f, 6f, TXT, Paint.Align.CENTER)
        for (i in 0 until Equalizer10.BANDS) {
            drawSlider(c, bandX(i), eq?.gains?.get(i) ?: 0f, BAND0 + i)
            text(c, BAND_LABELS[i], bandX(i) + 7f, 109f, 6f, TXT, Paint.Align.CENTER)
        }
    }

    private fun listBottom(h: Float) = h - 25f

    private fun clampScroll(s: PlayerService?, h: Float) {
        val content = (s?.playlist?.size ?: 0) * ROW_H
        val area = listBottom(h) - 17f
        plScroll = plScroll.coerceIn(0f, max(0f, content - area))
    }

    private fun drawPlaylist(c: Canvas, s: PlayerService?, w: Float, h: Float) {
        frame(c, w, h, "PLAYLIST")
        val lb = listBottom(h)
        bevel(c, 5f, 16f, w - 5f, lb + 1f, false, BLACK)
        clampScroll(s, h)
        val list = s?.playlist
        c.save()
        c.clipRect(6f, 17f, w - 6f, lb)
        var total = 0L
        if (list != null) {
            val first = (plScroll / ROW_H).toInt()
            for (t in list) total += t.durationMs
            var i = first
            while (i < list.size) {
                val y = 17f + i * ROW_H - plScroll
                if (y > lb) break
                val t = list[i]
                if (i == selected) fill(c, 6f, y, w - 6f, y + ROW_H, SEL)
                val col = if (i == s.index) WHITE else LED
                val durTxt = if (t.durationMs > 0) fmt(t.durationMs) else ""
                paint.typeface = mono
                paint.textSize = 8f
                val dw = if (durTxt.isEmpty()) 0f else paint.measureText(durTxt) + 4f
                c.save()
                c.clipRect(6f, y, w - 8f - dw, y + ROW_H)
                text(c, "${i + 1}. ${t.title}", 8f, y + 9f, 8f, col)
                c.restore()
                if (durTxt.isNotEmpty()) text(c, durTxt, w - 8f, y + 9f, 8f, col, Paint.Align.RIGHT)
                i++
            }
            if (list.isEmpty()) text(c, "Tap +FILE or +DIR to add music", w / 2f, 34f, 7.5f, LED_DIM, Paint.Align.CENTER)
        }
        c.restore()
        val by = h - 20f
        button(c, 6f, by, 44f, by + 14f, PL_ADDF, "+FILE")
        button(c, 47f, by, 82f, by + 14f, PL_ADDD, "+DIR")
        button(c, 85f, by, 115f, by + 14f, PL_REM, "REM")
        button(c, 118f, by, 148f, by + 14f, PL_CLR, "CLR")
        if (list != null && list.isNotEmpty()) {
            text(c, "${list.size} / ${fmt(total)}", w - 8f, by + 10f, 7f, TXT, Paint.Align.RIGHT)
        }
    }

    // ------------------------------------------------------------------ touch

    private fun hit(win: Int, x: Float, y: Float): Int = when (win) {
        1 -> when {
            y in 86f..110f -> when {
                x in 10f..35.5f -> PREV
                x in 35.5f..59.5f -> PLAY
                x in 59.5f..83.5f -> PAUSE
                x in 83.5f..107.5f -> STOP
                x in 107.5f..133f -> NEXT
                x in 135f..162f -> EJECT
                x in 166f..216.5f -> SHUF
                x in 216.5f..265f -> REP
                else -> NONE
            }
            y in 67f..86f && x in 6f..269f -> SEEK
            y in 49f..67f -> when {
                x in 99f..177f -> VOL
                x in 177f..225f -> BAL
                x in 225f..247f -> EQBTN
                x in 247f..270f -> PLBTN
                else -> NONE
            }
            y in 20f..42f && x in 26f..97f -> TIME
            y in 42f..66f && x in 9f..97f -> VIS
            else -> NONE
        }
        2 -> when {
            y in 15f..34f && x in 8f..44f -> EQON
            y in 15f..34f && x in 204f..267f -> PRESET
            y in 34f..104f && x in 12f..38f -> PREAMP
            y in 34f..104f && x in 67f..267f ->
                BAND0 + ((x - 67f) / 20f).toInt().coerceIn(0, Equalizer10.BANDS - 1)
            else -> NONE
        }
        3 -> {
            val h = plR.height()
            val w = plR.width()
            when {
                y in 16f..listBottom(h) && x in 4f..(w - 4f) -> PL_LIST
                y > h - 24f -> when {
                    x in 4f..45.5f -> PL_ADDF
                    x in 45.5f..83.5f -> PL_ADDD
                    x in 83.5f..116.5f -> PL_REM
                    x in 116.5f..150f -> PL_CLR
                    else -> NONE
                }
                else -> NONE
            }
        }
        else -> NONE
    }

    private fun isSlider(id: Int) = id == VOL || id == BAL || id == SEEK || id == PREAMP || id >= BAND0

    private fun drag(s: PlayerService, x: Float, y: Float) {
        val e = s.engine
        when {
            active == VOL -> e.volume = ((x - 107f) / 62f).coerceIn(0f, 1f)
            active == BAL -> {
                val b = ((x - 201f) / 16f).coerceIn(-1f, 1f)
                e.balance = if (abs(b) < 0.15f) 0f else b
            }
            active == SEEK -> dragSeek = ((x - 14f - 13f) / (261f - 14f - 26f)).coerceIn(0f, 1f)
            active == PREAMP || active >= BAND0 -> {
                var db = (((96f - y) / 54f) * 2f - 1f) * Equalizer10.MAX_DB
                db = db.coerceIn(-Equalizer10.MAX_DB, Equalizer10.MAX_DB)
                if (abs(db) < 0.9f) db = 0f
                if (active == PREAMP) e.eq.preampDb = db else e.eq.setGain(active - BAND0, db)
            }
        }
    }

    private fun click(s: PlayerService, id: Int) {
        when (id) {
            PREV -> s.prev()
            PLAY -> if (s.playlist.isEmpty()) host.pickFiles(true) else s.play()
            PAUSE -> s.pause()
            STOP -> s.stop()
            NEXT -> s.next()
            EJECT -> host.pickFiles(true)
            SHUF -> { s.shuffle = !s.shuffle; s.save() }
            REP -> { s.repeat = !s.repeat; s.save() }
            EQBTN -> { showEq = !showEq; savePrefs() }
            PLBTN -> { showPl = !showPl; savePrefs() }
            TIME -> { timeRemaining = !timeRemaining; savePrefs() }
            VIS -> { visMode = (visMode + 1) % 3; bars.fill(0f); peaks.fill(0f); savePrefs() }
            EQON -> { s.engine.eq.enabled = !s.engine.eq.enabled; s.save() }
            PRESET -> host.showPresets()
            PL_ADDF -> host.pickFiles(false)
            PL_ADDD -> host.pickFolder()
            PL_REM -> if (selected in s.playlist.indices) {
                s.removeAt(selected)
                if (selected >= s.playlist.size) selected = s.playlist.size - 1
            }
            PL_CLR -> { s.clear(); selected = -1; plScroll = 0f }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val s = svc ?: return true
        layoutWindows()
        val gx = ev.x / scale
        val gy = ev.y / scale
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            activeWin = when {
                mainR.contains(gx, gy) -> 1
                showEq && eqR.contains(gx, gy) -> 2
                showPl && plR.contains(gx, gy) -> 3
                else -> 0
            }
        }
        val r = when (activeWin) { 1 -> mainR; 2 -> eqR; 3 -> plR; else -> null }
        val x = gx - (r?.left ?: 0f)
        val y = gy - (r?.top ?: 0f)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = hit(activeWin, x, y)
                inside = true
                if (active == SEEK && (s.state == PlayerService.STOPPED || s.durationMs <= 0)) active = NONE
                if (isSlider(active)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    drag(s, x, y)
                } else if (active == PL_LIST) {
                    downX = x; downY = y; downScroll = plScroll; moved = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSlider(active)) drag(s, x, y)
                else if (active == PL_LIST) {
                    if (!moved && (abs(y - downY) > 4f || abs(x - downX) > 8f)) moved = true
                    if (moved) { plScroll = downScroll - (y - downY); clampScroll(s, plR.height()) }
                } else if (active != NONE) inside = hit(activeWin, x, y) == active
            }
            MotionEvent.ACTION_UP -> {
                if (active == SEEK) {
                    if (dragSeek >= 0f) s.seekTo((dragSeek * s.durationMs).toLong())
                    dragSeek = -1f
                } else if (isSlider(active)) {
                    s.save()
                } else if (active == PL_LIST) {
                    if (!moved) {
                        val row = ((y - 17f + plScroll) / ROW_H).toInt()
                        val now = SystemClock.uptimeMillis()
                        if (row in s.playlist.indices) {
                            if (row == lastTapRow && now - lastTapTime < 400) {
                                s.playIndex(row)
                                lastTapRow = -1
                            } else {
                                selected = row
                                lastTapRow = row
                                lastTapTime = now
                            }
                        } else selected = -1
                    }
                } else if (active != NONE && inside) click(s, active)
                active = NONE
            }
            MotionEvent.ACTION_CANCEL -> { active = NONE; dragSeek = -1f }
        }
        invalidate()
        return true
    }
}
