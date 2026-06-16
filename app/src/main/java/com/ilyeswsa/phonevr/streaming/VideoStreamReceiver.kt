package com.ilyeswsa.phonevr.streaming

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

/**
 * Receives H.264 encoded video from the PC driver over UDP
 * and decodes it directly to a Surface using Android's MediaCodec (hardware decoder).
 *
 * The PC sends RTP-like packets:
 *   [0]    byte  - magic 0x56
 *   [1]    byte  - type 0x02 (video)
 *   [2-3]  short - sequence
 *   [4-5]  short - total fragments in this frame
 *   [6-7]  short - fragment index
 *   [8-11] int   - frame timestamp (ms)
 *   [12..] bytes - H.264 NAL data fragment
 */
class VideoStreamReceiver(
    private val surface: Surface,
    private val port: Int = 6001,
    private val width: Int = 2160,
    private val height: Int = 1200
) {
    private var codec: MediaCodec? = null
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    // Fragment reassembly buffer: frameTimestamp -> list of fragments
    private val frameBuffer = HashMap<Int, Array<ByteArray?>>()

    fun start(scope: CoroutineScope) {
        // Configure H.264 decoder
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920 * 1080)

        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(format, surface, null, 0)
            it.start()
        }

        socket = DatagramSocket(port)

        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(65535)
            val packet = DatagramPacket(buf, buf.size)

            while (isActive) {
                try {
                    socket?.receive(packet)
                    processPacket(buf, packet.length)
                } catch (e: Exception) {
                    if (isActive) delay(10)
                }
            }
        }
    }

    private fun processPacket(data: ByteArray, length: Int) {
        if (length < 12) return
        if (data[0] != 0x56.toByte() || data[1] != 0x02.toByte()) return

        val totalFrags = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val fragIndex  = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val timestamp  = ByteBuffer.wrap(data, 8, 4).int

        val payload = data.copyOfRange(12, length)

        // Store fragment
        val frags = frameBuffer.getOrPut(timestamp) { arrayOfNulls(totalFrags) }
        if (fragIndex < frags.size) frags[fragIndex] = payload

        // Check if frame is complete
        if (frags.all { it != null }) {
            val frame = frags.flatMap { it!!.toList() }.toByteArray()
            frameBuffer.remove(timestamp)
            decodeFrame(frame, timestamp.toLong())
        }
    }

    private fun decodeFrame(nalData: ByteArray, pts: Long) {
        val c = codec ?: return
        val inputIdx = c.dequeueInputBuffer(10_000)
        if (inputIdx >= 0) {
            val inputBuf = c.getInputBuffer(inputIdx) ?: return
            inputBuf.clear()
            inputBuf.put(nalData)
            c.queueInputBuffer(inputIdx, 0, nalData.size, pts * 1000, 0)
        }

        val info = MediaCodec.BufferInfo()
        val outputIdx = c.dequeueOutputBuffer(info, 0)
        if (outputIdx >= 0) {
            c.releaseOutputBuffer(outputIdx, true) // render to surface
        }
    }

    fun stop() {
        job?.cancel()
        codec?.stop()
        codec?.release()
        codec = null
        socket?.close()
        socket = null
    }
}
