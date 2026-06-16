package com.ilyeswsa.phonevr.ui

import android.content.Context
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ilyeswsa.phonevr.databinding.ActivityMainBinding
import com.ilyeswsa.phonevr.renderer.VRRenderer
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
    private var trackingSender: HeadTrackingSender? = null
    private var videoReceiver: VideoStreamReceiver? = null

    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on, fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        madgwick = MadgwickFilter(beta = 0.1f)

        setupGLSurface()
        setupUI()
    }

    private fun setupGLSurface() {
        renderer = VRRenderer(this)
        binding.glSurfaceView.apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            val ip = binding.etPcIp.text.toString().trim()
            if (ip.isEmpty()) {
                binding.tvStatus.text = "Enter PC IP address"
                return@setOnClickListener
            }
            connect(ip)
        }

        binding.btnDisconnect.setOnClickListener {
            disconnect()
        }

        binding.btnRecenter.setOnClickListener {
            madgwick.reset()
            binding.tvStatus.text = "View recentered"
        }
    }

    private fun connect(pcIp: String) {
        binding.tvStatus.text = "Connecting to $pcIp..."
        binding.btnConnect.isEnabled = false

        lifecycleScope.launch {
            try {
                // Start head tracking
                madgwick.register(sensorManager)
                trackingSender = HeadTrackingSender(pcIp, port = 6000).also {
                    it.start(madgwick, lifecycleScope)
                }

                // Wait for decoder surface to be ready
                delay(500)
                val surface = renderer.decoderSurface
                if (surface != null) {
                    videoReceiver = VideoStreamReceiver(surface, port = 6001).also {
                        it.start(lifecycleScope)
                    }
                }

                isConnected = true
                binding.layoutConnect.visibility = View.GONE
                binding.layoutVr.visibility = View.VISIBLE
                binding.tvStatus.text = "Connected — put on headset"

            } catch (e: Exception) {
                binding.tvStatus.text = "Failed: ${e.message}"
                binding.btnConnect.isEnabled = true
                disconnect()
            }
        }
    }

    private fun disconnect() {
        trackingSender?.stop()
        videoReceiver?.stop()
        madgwick.unregister(sensorManager)
        trackingSender = null
        videoReceiver = null
        isConnected = false

        binding.layoutConnect.visibility = View.VISIBLE
        binding.layoutVr.visibility = View.GONE
        binding.btnConnect.isEnabled = true
        binding.tvStatus.text = "Disconnected"
    }

    override fun onResume() {
        super.onResume()
        binding.glSurfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.glSurfaceView.onPause()
        if (isConnected) disconnect()
    }
}
