package com.streamcam.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

data class EncoderConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int = 8_000_000,
    val iFrameIntervalSec: Int = 1,
)

class H264Encoder {

    interface Listener {
        fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer)
        fun onEncodedFrame(frame: ByteBuffer, info: MediaCodec.BufferInfo)
        fun onEncoderError(reason: String)
    }

    var listener: Listener? = null

    private val encodedFrames = AtomicLong(0L)
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: HandlerThread? = null
    private var drainHandler: Handler? = null

    @Volatile
    private var running = false

    val emittedFrames: Long
        get() = encodedFrames.get()

    fun prepare(config: EncoderConfig): Surface {
        check(codec == null) { "Encoder already prepared" }
        require(config.width > 0 && config.height > 0) { "invalid encoder size" }
        val codec = openCodec(buildFormat(config, withProfileLevel = true))
            ?: openCodec(buildFormat(config, withProfileLevel = false))
            ?: throw IllegalStateException("H264 encoder configure failed")
        this.codec = codec
        val surface = codec.createInputSurface()
        inputSurface = surface
        return surface
    }

    fun start() {
        val codec = requireNotNull(codec) { "prepare() first" }
        require(inputSurface != null) { "prepare() first" }
        codec.start()
        running = true
        val thread = HandlerThread("encoder-drain").also { it.start() }
        drainThread = thread
        drainHandler = Handler(thread.looper)
        drainHandler!!.post(::drainLoop)
    }

    fun requestSyncFrame() {
        val codec = codec ?: return
        runCatching {
            val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            codec.setParameters(params)
        }
    }

    fun stopAndRelease() {
        running = false
        drainHandler?.removeCallbacksAndMessages(null)
        drainThread?.let { thread ->
            thread.quitSafely()
            runCatching { thread.join(JOIN_TIMEOUT_MS) }
        }
        drainThread = null
        drainHandler = null
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        inputSurface?.let { surface -> runCatching { surface.release() } }
        inputSurface = null
        encodedFrames.set(0L)
    }

    private fun buildFormat(config: EncoderConfig, withProfileLevel: Boolean): MediaFormat {
        val width = config.width - (config.width % 2)
        val height = config.height - (config.height % 2)
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSec)
            if (withProfileLevel) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
        }
    }

    private fun openCodec(format: MediaFormat): MediaCodec? {
        val candidate = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: Exception) {
            Log.e(TAG, "createEncoderByType failed", e)
            return null
        }
        return try {
            candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            candidate
        } catch (e: Exception) {
            Log.w(TAG, "configure failed (profile/level=${format.containsKey(MediaFormat.KEY_PROFILE)}), releasing", e)
            runCatching { candidate.release() }
            null
        }
    }

    private fun drainLoop() {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        var videoInfoSent = false
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            } catch (e: Exception) {
                Log.e(TAG, "dequeueOutputBuffer failed", e)
                if (running) notifyError("Encoder drain failed: ${e.message}")
                return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    val sps = format.getByteBuffer("csd-0")
                    val pps = format.getByteBuffer("csd-1")
                    if (sps != null && pps != null && !videoInfoSent) {
                        videoInfoSent = true
                        listener?.onVideoInfo(sps.duplicate().rewind0(), pps.duplicate().rewind0())
                    }
                }

                index >= 0 -> {
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && info.size > 0 && !isConfig) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            listener?.onEncodedFrame(buffer, info)
                            encodedFrames.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "frame delivery failed", e)
                        if (running) notifyError("Encoder frame failed: ${e.message}")
                    } finally {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
        }
    }

    private fun notifyError(reason: String) {
        listener?.onEncoderError(reason)
    }

    private fun ByteBuffer.rewind0(): ByteBuffer {
        position(0)
        return this
    }

    companion object {
        private const val TAG = "H264Encoder"
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val JOIN_TIMEOUT_MS = 1_000L
    }
}
