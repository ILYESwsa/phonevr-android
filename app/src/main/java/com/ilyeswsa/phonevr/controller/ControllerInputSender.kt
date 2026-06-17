package com.ilyeswsa.phonevr.controller

import android.view.MotionEvent
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Virtual VR Controller using the phone touchscreen.
 *
 * The screen is split into LEFT and RIGHT zones, each acting as one controller.
 * Gestures map to Quest controller inputs:
 *
 *   LEFT SIDE:
 *     Single tap        → Left trigger click
 *     Hold              → Left grip
 *     Swipe up/down     → Thumbstick Y axis (locomotion)
 *     Swipe left/right  → Thumbstick X axis (turn)
 *     Two-finger tap    → X button
 *     Two-finger hold   → Y button
 *
 *   RIGHT SIDE:
 *     Single tap        → Right trigger click
 *     Hold              → Right grip
 *     Swipe up/down     → Thumbstick Y axis
 *     Swipe left/right  → Thumbstick X axis
 *     Two-finger tap    → A button
 *     Two-finger hold   → B button
 *
 * Packet format (32 bytes, UDP port 6002):
 *   [0]    byte   magic 0x56
 *   [1]    byte   type 0x03 (controller)
 *   [2]    byte   hand (0=left, 1=right)
 *   [3]    byte   buttons bitmask
 *   [4-7]  float  trigger (0.0 - 1.0)
 *   [8-11] float  grip    (0.0 - 1.0)
 *   [12-15] float thumbstick X (-1.0 - 1.0)
 *   [16-19] float thumbstick Y (-1.0 - 1.0)
 *   [20-27] float[2] position xyz (fake, identity)
 *   [28-31] uint32 timestamp ms
 */
class ControllerInputSender(
    private val pcIp: String,
    private val port: Int = 6002,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    object Buttons {
        const val TRIGGER   = 0x01
        const val GRIP      = 0x02
        const val THUMBSTICK= 0x04
        const val A_OR_X    = 0x08
        const val B_OR_Y    = 0x10
        const val MENU      = 0x20
    }

    data class ControllerState(
        val hand: Int = 0,          // 0=left, 1=right
        var buttons: Int = 0,
        var trigger: Float = 0f,
        var grip: Float = 0f,
        var thumbX: Float = 0f,
        var thumbY: Float = 0f
    )

    private val leftCtrl  = ControllerState(hand = 0)
    private val rightCtrl = ControllerState(hand = 1)

    private var socket: DatagramSocket? = null
    private var sendJob: Job? = null

    // Touch tracking per pointer
    private data class TouchPoint(
        val startX: Float, val startY: Float,
        var lastX: Float, var lastY: Float,
        val startTime: Long = System.currentTimeMillis()
    )
    private val activePointers = HashMap<Int, TouchPoint>()

    fun start(scope: CoroutineScope) {
        socket = DatagramSocket()
        val addr = InetAddress.getByName(pcIp)

        // Send controller state at 60Hz
        sendJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendState(leftCtrl, addr)
                sendState(rightCtrl, addr)
                delay(16)
            }
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val midX = screenWidth / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val x = event.getX(idx); val y = event.getY(idx)
                activePointers[pid] = TouchPoint(x, y, x, y)
                onPointerDown(x, y, midX, countPointersOnSide(event, x < midX, midX))
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val tp = activePointers[pid] ?: continue
                    val x = event.getX(i); val y = event.getY(i)
                    val dx = x - tp.lastX; val dy = y - tp.lastY
                    tp.lastX = x; tp.lastY = y

                    val ctrl = if (tp.startX < midX) leftCtrl else rightCtrl
                    // Map swipe to thumbstick (-1..1 over 200px)
                    ctrl.thumbX = ((x - tp.startX) / 200f).coerceIn(-1f, 1f)
                    ctrl.thumbY = (-(y - tp.startY) / 200f).coerceIn(-1f, 1f)

                    // Drag = grip
                    val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (dist > 5f) ctrl.grip = 1f
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                val tp = activePointers.remove(pid) ?: return true
                val ctrl = if (tp.startX < midX) leftCtrl else rightCtrl

                val holdTime = System.currentTimeMillis() - tp.startTime
                val totalDist = Math.hypot(
                    (tp.lastX - tp.startX).toDouble(),
                    (tp.lastY - tp.startY).toDouble()
                )

                // Tap (< 200ms, < 30px movement) = trigger click
                if (holdTime < 200 && totalDist < 30) {
                    val pointerCount = countPointersOnSide(event, tp.startX < midX, midX)
                    if (pointerCount >= 2) {
                        // Two-finger tap = A/X button
                        ctrl.buttons = ctrl.buttons or Buttons.A_OR_X
                        // Clear after short delay
                        GlobalScope.launch { delay(100); ctrl.buttons = ctrl.buttons and Buttons.A_OR_X.inv() }
                    } else {
                        ctrl.trigger = 1f
                        ctrl.buttons = ctrl.buttons or Buttons.TRIGGER
                        GlobalScope.launch { delay(100); ctrl.trigger = 0f; ctrl.buttons = ctrl.buttons and Buttons.TRIGGER.inv() }
                    }
                }

                // Reset thumbstick and grip when finger lifts
                if (activePointers.none { if (tp.startX < midX) it.value.startX < midX else it.value.startX >= midX }) {
                    ctrl.thumbX = 0f; ctrl.thumbY = 0f; ctrl.grip = 0f
                }
            }
        }
        return true
    }

    private fun onPointerDown(x: Float, y: Float, midX: Float, fingerCount: Int) {
        val ctrl = if (x < midX) leftCtrl else rightCtrl
        if (fingerCount >= 2) {
            // Two fingers held = B/Y button
            ctrl.buttons = ctrl.buttons or Buttons.B_OR_Y
        }
    }

    private fun countPointersOnSide(event: MotionEvent, leftSide: Boolean, midX: Float): Int {
        var count = 0
        for (i in 0 until event.pointerCount) {
            val px = event.getX(i)
            if (leftSide && px < midX) count++
            else if (!leftSide && px >= midX) count++
        }
        return count
    }

    private fun sendState(ctrl: ControllerState, addr: InetAddress) {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x56)
        buf.put(0x03)
        buf.put(ctrl.hand.toByte())
        buf.put(ctrl.buttons.toByte())
        buf.putFloat(ctrl.trigger)
        buf.putFloat(ctrl.grip)
        buf.putFloat(ctrl.thumbX)
        buf.putFloat(ctrl.thumbY)
        buf.putFloat(0f); buf.putFloat(0f) // position placeholder
        buf.putInt((System.currentTimeMillis() % Int.MAX_VALUE).toInt())

        val data = buf.array()
        socket?.send(DatagramPacket(data, data.size, addr, port))
    }

    fun stop() {
        sendJob?.cancel()
        socket?.close()
        socket = null
    }
}
