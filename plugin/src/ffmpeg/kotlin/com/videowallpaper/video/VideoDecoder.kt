package com.videowallpaper.video

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer

/**
 * 单文件视频解码器：ffmpeg（JavaCPP）→ BGRA 字节。
 *
 * 只管「打开 → 逐帧解出来 → 关闭」，节奏与循环由 [VideoEngine] 控制。
 * 解码目标按 [maxWidth] 等比缩小（壁纸铺满屏幕，4K 原片没必要全解码）。
 *
 * 注：bytedeco 把 C 的 `T**` 出参映射成「传一个空壳对象进去」，所以
 * `avformat_open_input(fmt, …)` 会就地把 `fmt` 变成真实上下文（bytedeco 惯用法）。
 */
class VideoDecoder(
    private val path: String,
    private val maxWidth: Int = 1600,
) : AutoCloseable {

    private val fmt = AVFormatContext(null)
    private var ctx: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var sws: SwsContext? = null
    private var dstBuf: BytePointer? = null
    private var dstDataPtr: PointerPointer<Pointer>? = null
    private var dstStride: IntPointer? = null

    private var streamIndex = -1
    private var timeBaseNum = 1
    private var timeBaseDen = 1000

    var width = 0
        private set
    var height = 0
        private set
    var durationSec = 0.0
        private set
    var fps = 0.0
        private set

    private var bytes = ByteArray(0)

    fun open() {
        val ret = avformat_open_input(fmt, path, null, null)
        if (ret < 0) throw IllegalStateException("avformat_open_input 失败 ($ret): $path")

        if (avformat_find_stream_info(fmt, null as PointerPointer<*>?) < 0) {
            throw IllegalStateException("avformat_find_stream_info 失败")
        }

        streamIndex = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, null as AVCodec?, 0)
        if (streamIndex < 0) throw IllegalStateException("没有找到视频流")

        val stream = fmt.streams(streamIndex)
        val codecpar = stream.codecpar()
        val codec = avcodec_find_decoder(codecpar.codec_id())
            ?: throw IllegalStateException("找不到解码器 (codec_id=${codecpar.codec_id()})")

        val cc = avcodec_alloc_context3(codec)
            ?: throw IllegalStateException("avcodec_alloc_context3 失败")
        ctx = cc
        if (avcodec_parameters_to_context(cc, codecpar) < 0) {
            throw IllegalStateException("avcodec_parameters_to_context 失败")
        }
        cc.thread_count(0) // 0 = 自动
        if (avcodec_open2(cc, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
            throw IllegalStateException("avcodec_open2 失败")
        }

        val tb = stream.time_base()
        if (tb != null && tb.num() > 0 && tb.den() > 0) {
            timeBaseNum = tb.num()
            timeBaseDen = tb.den()
        }

        val duration = fmt.duration()
        if (duration > 0) durationSec = duration / 1_000_000.0

        val afr = stream.avg_frame_rate()
        if (afr != null && afr.num() > 0 && afr.den() > 0) {
            fps = afr.num().toDouble() / afr.den()
        }

        val srcW = cc.width()
        val srcH = cc.height()
        if (srcW <= 0 || srcH <= 0) throw IllegalStateException("视频尺寸异常: ${srcW}x${srcH}")

        val scale = if (srcW > maxWidth) maxWidth.toDouble() / srcW else 1.0
        val outW = ((srcW * scale).toInt().coerceAtLeast(2)) and 0x7FFFFFFE // 偶数宽
        val outH = ((srcH * scale).toInt().coerceAtLeast(2)) and 0x7FFFFFFE
        width = outW
        height = outH

        sws = sws_getContext(
            srcW, srcH, cc.pix_fmt(),
            outW, outH, AV_PIX_FMT_BGRA,
            SWS_BILINEAR, null, null, null as DoublePointer?,
        ) ?: throw IllegalStateException("sws_getContext 失败")

        packet = av_packet_alloc()
        frame = av_frame_alloc()
        dstBuf = BytePointer((outW.toLong() * outH * 4).coerceAtLeast(16))
        // swscale 会按平面读 dst[] / dstStride[]；即使 BGRA 只有 1 个平面也要给足 4 项，
        // 否则它读到的 dst[1] 是野指针，直接报 "bad dst image pointers"（实测踩过）。
        dstDataPtr = PointerPointer(4L)
        dstStride = IntPointer(4L)
        for (i in 0 until 4) {
            dstDataPtr?.put(i.toLong(), dstBuf as Pointer)
            dstStride?.put(i.toLong(), outW * 4)
        }
        bytes = ByteArray(outW * outH * 4)

        if (durationSec <= 0 && fps > 0) {
            // 少数容器没有顶层 duration，用流时长兜底
            val d = stream.duration()
            if (d > 0 && timeBaseDen > 0) durationSec = d.toDouble() * timeBaseNum / timeBaseDen
        }
    }

    /**
     * 解下一帧。返回 false = 读到文件尾（调用方决定是否 seek 回 0）。
     * 成功时 [onFrame] 收到 (BGRA 字节, 宽, 高, 时间戳秒)。
     */
    fun nextFrame(onFrame: (ByteArray, Int, Int, Double) -> Unit, shouldAbort: () -> Boolean): Boolean {
        val cc = ctx ?: return false
        val pkt = packet ?: return false
        val frm = frame ?: return false

        while (true) {
            if (shouldAbort()) return false
            if (av_read_frame(fmt, pkt) < 0) return false // EOF

            try {
                if (pkt.stream_index() != streamIndex) continue

                val sendRet = avcodec_send_packet(cc, pkt)
                if (sendRet < 0 && sendRet != -11) continue // 坏包直接丢

                while (true) {
                    val recvRet = avcodec_receive_frame(cc, frm)
                    if (recvRet < 0) {
                        if (recvRet != -11) return false
                        break
                    }
                    val rawPts = frm.best_effort_timestamp()
                    val pts = if (rawPts == AV_NOPTS_VALUE) frm.pts() else rawPts
                    val ptsSec = if (pts == AV_NOPTS_VALUE) -1.0
                    else pts.toDouble() * timeBaseNum / timeBaseDen
                    val ok = decodeToBytes(frm)
                    if (ok) {
                        onFrame(bytes, width, height, ptsSec)
                        av_frame_unref(frm)
                        return true
                    }
                    av_frame_unref(frm)
                }
            } finally {
                av_packet_unref(pkt)
            }
        }
    }

    private fun decodeToBytes(frm: AVFrame): Boolean {
        val s = sws ?: return false
        val dst = dstDataPtr ?: return false
        val stride = dstStride ?: return false
        val h = sws_scale(s, frm.data(), frm.linesize(), 0, frm.height(), dst, stride)
        if (h <= 0) return false
        val buf = dstBuf ?: return false
        buf.position(0)
        buf.get(bytes)
        return true
    }

    /** 跳回开头（循环播放用） */
    fun rewind() {
        ctx?.let { avcodec_flush_buffers(it) }
        // -1 = 默认流，时间戳单位是 AV_TIME_BASE（微秒）
        av_seek_frame(fmt, -1, 0, AVSEEK_FLAG_BACKWARD)
    }

    override fun close() {
        runCatching { frame?.let { av_frame_free(it) } }
        runCatching { packet?.let { av_packet_free(it) } }
        runCatching { sws?.let { sws_freeContext(it) } }
        runCatching { ctx?.let { avcodec_free_context(it) } }
        runCatching { if (!fmt.isNull) avformat_close_input(fmt) }
        runCatching { dstBuf?.deallocate() }
        runCatching { dstDataPtr?.deallocate() }
        runCatching { dstStride?.deallocate() }
        frame = null
        packet = null
        sws = null
        ctx = null
        dstBuf = null
        dstDataPtr = null
        dstStride = null
    }

    companion object {
        private const val AV_NOPTS_VALUE = Long.MIN_VALUE
    }
}
