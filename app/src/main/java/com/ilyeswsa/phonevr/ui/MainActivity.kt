package com.ilyeswsa.phonevr.ui

import android.content.Context
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ilyeswsa.phonevr.controller.ControllerInputSender
import com.ilyeswsa.phonevr.controller.ControllerOverlayView
import com.ilyeswsa.phonevr.databinding.ActivityMainBinding
import com.ilyeswsa.phonevr.renderer.VRRenderer
import com.ilyeswsa.phonevr.streaming.TransportDetector
import com.ilyeswsa.phonevr.streaming.VideoStreamReceiver
import com.ilyeswsa.phonevr.tracking.HeadTrackingSender
import com.ilyeswsa.phonevr.tracking.MadgwickFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private lateinit var madgwick: MadgwickFilter
    private lateinit var renderer: VRRenderer
    private lateinit var controllerOverlay: ControllerOverlayView

    private var trackingSender: HeadTrackingSender? = null
    private var videoReceiver: VideoStreamReceiver? = null
    private var controllerSender: ControllerInputSender? = null
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        madgwick = MadgwickFilter(beta = 0.1f)

        setupGLSurface()
        setupControllerOverlay()
        setupUI()
        showPhoneIp()
    }

    private fun setupGLSurface() {
        renderer = VRRenderer(this)
        binding.glSurfaceView.apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    private fun setupControllerOverlay() {
        controllerOverlay = ControllerOverlayView(this)
        binding.root.addView(controllerOverlay, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            val ip = binding.etPcIp.text.toString().trim()
            if (ip.isEmpty()) { binding.tvStatus.text = "Enter PC IP address"; return@setOnClickListener }
            connect(ip)
        }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnRecenter.setOnClickListener {
            madgwick.reset()
            binding.tvStatus.text = "View recentered"
        }
    }

    /** Shows this phone's WiFi IP so user knows what IP to configure */
    private fun showPhoneIp() {
        val ip = TransportDetector.getWifiIp(this)
        if (ip != null) {
            binding.tvPhoneIp.text = "Phone IP: $ip"
            binding.tvPhoneIp.visibility = View.VISIBLE
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isConnected) {
            controllerOverlay.onTouchEvent(ev)
            controllerSender?.onTouchEvent(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun connect(manualIp: String) {
        binding.tvStatus.text = "Detecting transport…"
        binding.btnConnect.isEnabled = false

        lifecycleScope.launch {
            try {
                // Auto-detect USB vs WiFi
                val transport = TransportDetector.detect(manualIp)
                val ip = transport.ip
                val mode = if (transport.transport == TransportDetector.Transport.USB)
                    "USB (ADB)" else "WiFi"
                binding.tvStatus.text = "Connecting via $mode…"

                // Start head tracking → PC
                madgwick.register(sensorManager)
                trackingSender = HeadTrackingSender(ip, port = transport.trackingPort).also {
                    it.start(madgwick, lifecycleScope)
                }

                // Start controller sender → PC
                val dm = resources.displayMetrics
                controllerSender = ControllerInputSender(
                    pcIp = ip,
                    port = transport.controllerPort,
                    screenWidth  = dm.widthPixels,
                    screenHeight = dm.heightPixels
                ).also { it.start(lifecycleScope) }

                // Wait for GL surface, then start video receiver
                delay(500)
                renderer.decoderSurface?.let { surface ->
                    videoReceiver = VideoStreamReceiver(surface,
                        port = transport.videoPort).also {
                        it.start(lifecycleScope)
                    }
                }

                isConnected = true
                binding.layoutConnect.visibility = View.GONE
                binding.layoutVr.visibility = View.VISIBLE
                binding.tvStatus.text = "● $mode"
                controllerOverlay.showTemporarily()

            } catch (e: Exception) {
                binding.tvStatus.text = "Error: ${e.message}"
                binding.btnConnect.isEnabled = true
                disconnect()
            }
        }
    }

    private fun disconnect() {
        trackingSender?.stop()
        videoReceiver?.stop()
        controllerSender?.stop()
        madgwick.unregister(sensorManager)
        trackingSender = null; videoReceiver = null; controllerSender = null
        isConnected = false

        binding.layoutConnect.visibility = View.VISIBLE
        binding.layoutVr.visibility = View.GONE
        binding.btnConnect.isEnabled = true
        binding.tvStatus.text = "Disconnected"
    }

    override fun onResume() { super.onResume(); binding.glSurfaceView.onResume() }
    override fun onPause()  { super.onPause();  binding.glSurfaceView.onPause(); if (isConnected) disconnect() }
}
