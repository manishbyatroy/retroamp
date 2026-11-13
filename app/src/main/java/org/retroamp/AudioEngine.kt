package org.retroamp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes one audio file at a time (MediaExtractor + MediaCodec), runs the PCM through
 * the equalizer / volume / balance stage and plays it on an AudioTrack.
 * All public methods are called from the main thread; listener callbacks arrive there too.
 */
class AudioEngine(private val ctx: Context) {

    interface Listener {
        fun onEnded()
        fun onError(msg: String)
        fun onInfo()
    }

    companion object {
        const val VIS_SIZE = 65536 // power of two
    }

    var listener: Listener? = null
    val eq = Equalizer10()

    @Volatile var volume = 0.8f
    @Volatile var balance = 0f

    @Volatile var durationUs = 0L; private set
    @Volatile var positionUs = 0L; private set
    @Volatile var sampleRate = 0; private set
    @Volatile var channels = 0; private set
    @Volatile var bitrateKbps = 0; private set
    @Volatile var paused = false; private set

    /** Mono ring buffer of what was most recently sent to the output, for the visualizer. */
    val vis = FloatArray(VIS_SIZE)
    @Volatile var visPos = 0; private set
    /** Frames queued in the output ahead of what is audible. */
    @Volatile var latencyFrames = 0; private set

    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    val isActive: Boolean get() = session != null

    fun play(uri: Uri) {
        stop()
        val s = Session(uri)
        session = s
        s.start()
    }

    fun pause() { if (session != null) paused = true }
    fun resume() { paused = false }

    fun stop() {
        val s = session
        session = null
        if (s != null) {
            s.stopFlag = true
            try { s.join(2000) } catch (_: InterruptedException) {}
        }
        paused = false
        positionUs = 0
        durationUs = 0
        sampleRate = 0
        channels = 0
        bitrateKbps = 0
        vis.fill(0f)
    }

    fun seekTo(us: Long) {
        val s = session ?: return
        val target = max(0L, if (durationUs > 0) min(us, durationUs) else us)
        s.seekReq.set(target)
        positionUs = target
    }

    private inner class Session(val uri: Uri) : Thread("retroamp-audio") {
        @Volatile var stopFlag = false
        val seekReq = AtomicLong(-1)

        private var track: AudioTrack? = null
        private var trackPlaying = false
        private var inChannels = 2
        private var isFloat = false
        private var fbuf = FloatArray(0)
        private var sbuf = ShortArray(0)

        override fun run() {
            val ex = MediaExtractor()
            var codec: MediaCodec? = null
            var error: String? = null
            try {
                ex.setDataSource(ctx, uri, null)
                var fmt: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        ex.selectTrack(i)
                        fmt = f
                        break
                    }
                }
                if (fmt == null) throw IllegalStateException("No audio track in file")
                durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                bitrateKbps = if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) fmt.getInteger(MediaFormat.KEY_BIT_RATE) / 1000 else 0
                sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                main.post { if (session === this) listener?.onInfo() }

                codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(fmt, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone && !stopFlag) {
                    val seek = seekReq.getAndSet(-1)
                    if (seek >= 0) {
                        ex.seekTo(seek, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        codec.flush()
                        track?.let { it.pause(); it.flush() }
                        trackPlaying = false
                        eq.reset()
                        inputDone = false
                        positionUs = seek
                    }
                    if (paused) {
                        syncTrackState()
                        sleep(20)
                        continue
                    }
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10000)
                        if (i >= 0) {
                            val b = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(b, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10000)
                    if (o >= 0) {
                        if (info.size > 0) {
                            if (track == null) openTrack(codec.outputFormat)
                            val buf = codec.getOutputBuffer(o)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val frames = if (isFloat) {
                                val fb = buf.asFloatBuffer()
                                val n = fb.remaining() / inChannels
                                ensure(n)
                                for (f in 0 until n) {
                                    val l = fb.get(f * inChannels)
                                    val r = if (inChannels > 1) fb.get(f * inChannels + 1) else l
                                    fbuf[f * 2] = l; fbuf[f * 2 + 1] = r
                                }
                                n
                            } else {
                                val sb = buf.asShortBuffer()
                                val n = sb.remaining() / inChannels
                                ensure(n)
                                for (f in 0 until n) {
                                    val l = sb.get(f * inChannels) / 32768f
                                    val r = if (inChannels > 1) sb.get(f * inChannels + 1) / 32768f else l
                                    fbuf[f * 2] = l; fbuf[f * 2 + 1] = r
                                }
                                n
                            }
                            codec.releaseOutputBuffer(o, false)
                            render(frames)
                            if (seekReq.get() < 0) positionUs = info.presentationTimeUs
                        } else {
                            codec.releaseOutputBuffer(o, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        closeTrack()
                    }
                }
                // Let whatever is still queued in the output play out.
                val t = track
                if (!stopFlag && t != null) {
                    try { t.stop() } catch (_: Exception) {}
                    var waitMs = latencyFrames * 1000L / max(1, t.sampleRate)
                    while (waitMs > 0 && !stopFlag) { sleep(20); waitMs -= 20 }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                closeTrack()
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                try { ex.release() } catch (_: Exception) {}
            }
            if (!stopFlag) {
                main.post {
                    if (session === this) {
                        session = null
                        val l = listener
                        if (error != null) l?.onError(error) else l?.onEnded()
                    }
                }
            }
        }

        private fun ensure(frames: Int) {
            if (fbuf.size < frames * 2) {
                fbuf = FloatArray(frames * 2)
                sbuf = ShortArray(frames * 2)
            }
        }

        private fun openTrack(out: MediaFormat) {
            val sr = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            inChannels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            isFloat = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            sampleRate = sr
            channels = inChannels
            eq.setSampleRate(sr)
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) throw IllegalStateException("Unsupported sample rate: $sr Hz")
            val bytes = max(minBuf * 2, sr / 5 * 4) // at least ~200 ms
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                t.release()
                throw IllegalStateException("Could not open audio output")
            }
            latencyFrames = bytes / 4
            track = t
            trackPlaying = false
            main.post { if (session === this) listener?.onInfo() }
        }

        private fun closeTrack() {
            val t = track ?: return
            track = null
            trackPlaying = false
            try { t.pause(); t.flush() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }

        private fun syncTrackState() {
            val t = track ?: return
            if (paused && trackPlaying) { t.pause(); trackPlaying = false }
            else if (!paused && !trackPlaying) { t.play(); trackPlaying = true }
        }

        /** EQ, volume, balance, visualizer tap, then blocking write of [frames] stereo frames. */
        private fun render(frames: Int) {
            val t = track ?: return
            eq.process(fbuf, frames)
            val v = volume
            val g = v * v
            val bal = balance
            val gl = g * (if (bal > 0f) 1f - bal else 1f)
            val gr = g * (if (bal < 0f) 1f + bal else 1f)
            var vp = visPos
            for (f in 0 until frames) {
                val l = fbuf[f * 2]
                val r = fbuf[f * 2 + 1]
                vis[vp] = (l + r) * 0.5f
                vp = (vp + 1) and (VIS_SIZE - 1)
                sbuf[f * 2] = (l * gl * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                sbuf[f * 2 + 1] = (r * gr * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            }
            visPos = vp
            val total = frames * 2
            var off = 0
            while (off < total && !stopFlag && seekReq.get() < 0) {
                syncTrackState()
                if (paused) { sleep(20); continue }
                val w = t.write(sbuf, off, total - off)
                if (w < 0) throw IllegalStateException("Audio output error $w")
                off += w
                if (w == 0) sleep(5)
            }
        }
    }
}
