package com.ilyeswsa.phonevr.tracking

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sends head tracking quaternion data to the PC driver via UDP.
 *
 * Packet format (20 bytes):
 *   [0]    byte   - magic (0xVR = 0x56)
 *   [1]    byte   - packet type (0x01 = head tracking)
 *   [2-3]  short  - sequence number
 *   [4-7]  float  - q0 (w)
 *   [8-11] float  - q1 (x)
 *   [12-15] float - q2 (y)
 *   [16-19] float - q3 (z)
 */
class HeadTrackingSender(
    private val pcIp: String,
    private val port: Int = 6000
) {
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private var seqNum: Short = 0

    fun start(filter: MadgwickFilter, scope: CoroutineScope) {
        socket = DatagramSocket()
        val addr = InetAddress.getByName(pcIp)

        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val q = filter.getQuaternion()
                    val buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                    buf.put(0x56.toByte())          // magic
                    buf.put(0x01.toByte())          // type: head tracking
                    buf.putShort(seqNum++)          // sequence
                    buf.putFloat(q[0])              // w
                    buf.putFloat(q[1])              // x
                    buf.putFloat(q[2])              // y
                    buf.putFloat(q[3])              // z

                    val data = buf.array()
                    val packet = DatagramPacket(data, data.size, addr, port)
                    socket?.send(packet)

                    delay(4) // ~250Hz send rate
                } catch (e: Exception) {
                    if (isActive) delay(100) // back off on error
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        socket?.close()
        socket = null
    }
}
