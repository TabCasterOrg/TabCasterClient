package com.example.streamclient3

import java.nio.ByteBuffer
import android.media.MediaCodecInfo
import android.content.Context

import android.content.pm.ActivityInfo
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.streamclient3.databinding.ActivityMainBinding
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.IllegalStateException

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "ZeroLatencyUdpPlayer"

    // Network
    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    // Media decoding
    private var decoder: MediaCodec? = null
    private var codecThread: HandlerThread? = null
    private var codecHandler: Handler? = null
    private var surface: Surface? = null
    private val packetParser = H264PacketParser()

    // Codec initialization state
    private var isCodecConfigured = false
    private var pendingIDRFrames = mutableListOf<ByteArray>()
    private var waitingForSPSPPS = true

    // Power management
    private var wakeLock: PowerManager.WakeLock? = null

    // Statistics
    private var packetCount = 0
    private var frameCount = 0
    private var lastStatsTime = System.currentTimeMillis()
    private var decodingErrors = 0

    // Video parameters - don't hardcode, will detect from stream
    private val VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    private var frameWidth = 1280  // Initial guess, will be updated
    private var frameHeight = 720  // Initial guess, will be updated

    // UI state
    private var isFullscreen = false
    private val originalSurfaceViewParams by lazy {
        binding.surfaceView.layoutParams as ConstraintLayout.LayoutParams
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up surface view for video display
        binding.surfaceView.holder.addCallback(this)

        // Acquire wake lock to prevent sleep during streaming
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "ZeroLatencyUdpPlayer::StreamingWakeLock"
        )

        binding.connectButton.setOnClickListener {
            if (isRunning.get()) {
                stopStreaming()
                binding.connectButton.text = "Connect"
            } else {
                startStreaming()
                binding.connectButton.text = "Disconnect"
            }
        }

        binding.fullscreenButton.setOnClickListener {
            toggleFullscreen()
        }
    }

    private fun toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            enterFullscreen()
        }
    }

    private fun enterFullscreen() {
        // Hide system UI
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        // Hide action bar
        supportActionBar?.hide()

        // Set landscape orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Make SurfaceView fill the screen
        val params = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.MATCH_PARENT
        )
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        binding.surfaceView.layoutParams = params

        // Hide other UI elements
        binding.serverIpEditText.visibility = View.GONE
        binding.connectButton.visibility = View.GONE
        binding.fullscreenButton.visibility = View.GONE

        isFullscreen = true
    }

    private fun exitFullscreen() {
        // Show system UI
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        // Show action bar
        supportActionBar?.show()

        // Set portrait orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Restore original SurfaceView layout
        binding.surfaceView.layoutParams = originalSurfaceViewParams

        // Show other UI elements
        binding.serverIpEditText.visibility = View.VISIBLE
        binding.connectButton.visibility = View.VISIBLE
        binding.fullscreenButton.visibility = View.VISIBLE

        isFullscreen = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        // Do not initialize decoder here - wait for SPS/PPS
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // We might adjust decoder settings here if needed
        Log.d(TAG, "Surface changed: $width x $height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
        releaseDecoder()
    }

    private fun startStreaming() {
        val serverAddress = binding.serverIpEditText.text.toString().trim()

        if (serverAddress.isEmpty()) {
            Toast.makeText(this, "Please enter server port", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val port = extractPort(serverAddress).toInt()

            // Initialize socket
            socket = DatagramSocket(null)
            socket?.reuseAddress = true
            socket?.bind(InetSocketAddress("0.0.0.0", port))
            socket?.soTimeout = 1000  // 1 second timeout for detecting connection issues

            // Reset statistics and state
            packetCount = 0
            frameCount = 0
            decodingErrors = 0
            lastStatsTime = System.currentTimeMillis()
            isCodecConfigured = false
            waitingForSPSPPS = true
            pendingIDRFrames.clear()

            // Acquire wake lock to prevent device from sleeping
            wakeLock?.acquire(10*60*1000L) // 10 minutes timeout

            isRunning.set(true)

            // Start receiving thread
            receiveThread = Thread {
                receiveAndDecodeLoop()
            }
            receiveThread?.priority = Thread.MAX_PRIORITY
            receiveThread?.start()

            Toast.makeText(this, "Listening on UDP port $port", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting stream: ${e.message}", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopStreaming() {
        isRunning.set(false)

        try {
            // Release wake lock
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

            socket?.close()
            socket = null

            receiveThread?.interrupt()
            receiveThread = null

            // Clear packet parser
            packetParser.clear()
            pendingIDRFrames.clear()

            // Print final statistics
            logStatistics(true)

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping stream: ${e.message}", e)
        }
    }

    /**
     * Attempt to detect video width and height from SPS data
     * Basic implementation - could be expanded for better parsing
     */
    private fun detectResolutionFromSPS(spsData: ByteArray): Pair<Int, Int>? {
        try {
            // Skip the NAL header and start code (usually first 5 bytes)
            // This is simplified - real SPS parsing is more complex
            if (spsData.size < 20) return null // Too small for valid SPS

            // Very basic SPS resolution extraction - would need a proper H264 SPS parser for production
            // Note: This is a simplified placeholder - real SPS parsing requires bitstream parsing
            // Real implementation would read profile_idc, level_idc, seq_parameter_set_id, etc.
            // and properly decode the Exp-Golomb coded values

            // For example purposes only - not fully correct!
            val width = ((spsData[7].toInt() and 0xFF) shl 8) or (spsData[8].toInt() and 0xFF)
            val height = ((spsData[9].toInt() and 0xFF) shl 8) or (spsData[10].toInt() and 0xFF)

            if (width > 0 && height > 0 && width < 8192 && height < 8192) {
                return Pair(width, height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SPS for resolution: ${e.message}")
        }
        return null
    }

    private fun initializeDecoder() {
        try {
            // Check for codec-specific data (SPS and PPS)
            val codecData = packetParser.getCodecSpecificData()
            if (codecData == null || codecData.first == null || codecData.second == null) {
                Log.e(TAG, "Cannot initialize decoder: Missing SPS/PPS data")
                return
            }

            // Try to detect resolution from SPS
            val detectedResolution = detectResolutionFromSPS(codecData.first!!)
            if (detectedResolution != null) {
                frameWidth = detectedResolution.first
                frameHeight = detectedResolution.second
                Log.d(TAG, "Detected video resolution from SPS: $frameWidth x $frameHeight")
            }

            // Create decoder for H.264 stream
            decoder = MediaCodec.createDecoderByType(VIDEO_MIME_TYPE)

            // Configure format with the detected or default dimensions
            val format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, frameWidth, frameHeight)

            // Set codec-specific data (SPS and PPS)
            val spsBuffer = ByteBuffer.wrap(codecData.first!!)
            val ppsBuffer = ByteBuffer.wrap(codecData.second!!)
            format.setByteBuffer("csd-0", spsBuffer)
            format.setByteBuffer("csd-1", ppsBuffer)

            // Critical for low latency
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority

            // Set operating rate to high value for faster processing
            val highPerformance = 120  // Use a reasonable value instead of MAX_VALUE
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, highPerformance)

            // Try to use common color format first
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            // Configure with surface for direct rendering
            decoder?.configure(format, surface, null, 0)
            decoder?.start()

            // Create handler thread for codec operations
            codecThread = HandlerThread("CodecThread").apply {
                start()
                setPriority(Thread.MAX_PRIORITY)
            }
            codecHandler = Handler(codecThread!!.looper)

            isCodecConfigured = true
            waitingForSPSPPS = false

            Log.d(TAG, "Decoder initialized successfully with format: $format")

            // Process any pending IDR frames
            processPendingIDRFrames()

        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize decoder: ${e.message}", e)
            Toast.makeText(this, "Decoder initialization failed: ${e.message}", Toast.LENGTH_SHORT).show()

            // Try again with different color format if this failed
            try {
                if (e.message?.contains("color format", ignoreCase = true) == true) {
                    retryDecoderWithDifferentColorFormat()
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Retry also failed: ${e2.message}")
            }
        }
    }

    private fun retryDecoderWithDifferentColorFormat() {
        val codecData = packetParser.getCodecSpecificData() ?: return

        try {
            // Create a new decoder
            decoder = MediaCodec.createDecoderByType(VIDEO_MIME_TYPE)

            // Try with a different color format
            val format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, frameWidth, frameHeight)

            val spsBuffer = ByteBuffer.wrap(codecData.first!!)
            val ppsBuffer = ByteBuffer.wrap(codecData.second!!)
            format.setByteBuffer("csd-0", spsBuffer)
            format.setByteBuffer("csd-1", ppsBuffer)

            // Use a different color format this time
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)

            decoder?.configure(format, surface, null, 0)
            decoder?.start()

            isCodecConfigured = true
            waitingForSPSPPS = false

            Log.d(TAG, "Decoder initialized with alternate color format")

            // Process any pending frames
            processPendingIDRFrames()
        } catch (e: Exception) {
            Log.e(TAG, "Alternative decoder initialization also failed: ${e.message}")
        }
    }

    private fun processPendingIDRFrames() {
        if (pendingIDRFrames.isNotEmpty()) {
            Log.d(TAG, "Processing ${pendingIDRFrames.size} pending IDR frames")
            pendingIDRFrames.forEach { frame ->
                feedDecoderDirectly(frame)
            }
            pendingIDRFrames.clear()
        }
    }

    private fun releaseDecoder() {
        try {
            decoder?.stop()
            decoder?.release()
            decoder = null

            codecThread?.quit()
            codecThread = null
            codecHandler = null

            isCodecConfigured = false
            waitingForSPSPPS = true

        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder: ${e.message}", e)
        }
    }

    private fun receiveAndDecodeLoop() {
        val buffer = ByteArray(65536)  // Large enough for typical UDP packets
        val packet = DatagramPacket(buffer, buffer.size)
        var consecutiveTimeouts = 0

        while (isRunning.get()) {
            try {
                // Clear packet before reuse
                packet.setLength(buffer.size)

                // Blocking receive with timeout
                socket?.receive(packet)
                consecutiveTimeouts = 0

                // Process received packet
                if (packet.length > 0) {
                    packetCount++

                    // Extract H.264 NAL unit from packet
                    val nalData = packet.data.copyOfRange(0, packet.length)

                    // Process the packet through our H.264 parser
                    if (packetParser.processPacket(nalData)) {
                        // Process all available NAL units
                        while (packetParser.hasNalUnits() && isRunning.get()) {
                            val nalUnit = packetParser.getNextNalUnit()
                            if (nalUnit != null) {
                                processNalUnit(nalUnit)
                            }
                        }
                    }
                }

            } catch (e: SocketTimeoutException) {
                consecutiveTimeouts++

                // If we've had several timeouts in a row, show message
                if (consecutiveTimeouts >= 3) {
                    runOnUiThread {
                        Toast.makeText(this, "No stream data - check connection", Toast.LENGTH_SHORT).show()
                    }
                    // Reset counter to avoid spamming messages
                    consecutiveTimeouts = 0
                }
            } catch (e: SocketException) {
                // Socket was likely closed deliberately
                if (!isRunning.get()) {
                    break
                }
                Log.e(TAG, "Socket error: ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error in receive loop: ${e.message}", e)
            }
        }

        Log.d(TAG, "Receive loop ended")
    }

    private fun processNalUnit(nalUnit: ByteArray) {
        if (nalUnit.size < 5) return // Too small to be a valid NAL unit

        // Check for NAL start code
        if (nalUnit[0] == 0x00.toByte() &&
            nalUnit[1] == 0x00.toByte() &&
            nalUnit[2] == 0x00.toByte() &&
            nalUnit[3] == 0x01.toByte()) {

            // Get NAL type from the fifth byte (after start code)
            val nalType = nalUnit[4].toInt() and 0x1F

            when (nalType) {
                7 -> { // SPS
                    Log.d(TAG, "Received SPS")
                    // Do we need to initialize/reinitialize the decoder?
                    if (!isCodecConfigured || waitingForSPSPPS) {
                        // Wait for both SPS and PPS before initializing
                        if (packetParser.getCodecSpecificData()?.second != null) {
                            releaseDecoder() // Clean up existing decoder if any
                            initializeDecoder() // Initialize with new SPS/PPS
                        } else {
                            waitingForSPSPPS = true
                        }
                    }
                }
                8 -> { // PPS
                    Log.d(TAG, "Received PPS")
                    // Do we need to initialize/reinitialize the decoder?
                    if (!isCodecConfigured || waitingForSPSPPS) {
                        // Wait for both SPS and PPS before initializing
                        if (packetParser.getCodecSpecificData()?.first != null) {
                            releaseDecoder() // Clean up existing decoder if any
                            initializeDecoder() // Initialize with new SPS/PPS
                        } else {
                            waitingForSPSPPS = true
                        }
                    }
                }
                5 -> { // IDR frame (keyframe)
                    Log.d(TAG, "Received IDR frame")
                    frameCount++

                    if (isCodecConfigured && !waitingForSPSPPS) {
                        // We have a configured decoder, feed the frame
                        feedDecoderDirectly(nalUnit)
                    } else {
                        // Store IDR frames until decoder is ready
                        Log.d(TAG, "Storing IDR frame for later (codec not ready)")
                        pendingIDRFrames.add(nalUnit.clone())

                        // Check if we have both SPS and PPS to initialize decoder
                        val codecData = packetParser.getCodecSpecificData()
                        if (codecData?.first != null && codecData.second != null) {
                            initializeDecoder()
                        }
                    }
                }
                else -> {
                    // Regular frame
                    frameCount++
                    if (isCodecConfigured && !waitingForSPSPPS) {
                        feedDecoderDirectly(nalUnit)
                    }
                    // Drop frames if decoder not ready - no need to buffer these
                }
            }

            // Log statistics periodically
            logStatistics(false)
        }
    }

    private fun feedDecoderDirectly(data: ByteArray) {
        if (decoder == null || !isCodecConfigured) return

        try {
            // Get input buffer index with a reasonable timeout
            val inputBufferIndex = decoder!!.dequeueInputBuffer(5000) // 5ms timeout
            if (inputBufferIndex >= 0) {
                // Get the input buffer and fill it with data
                val inputBuffer = decoder!!.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)

                // Queue the buffer to the decoder
                decoder!!.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    data.size,
                    System.nanoTime() / 1000, // Current time as presentation timestamp
                    0
                )
            }

            // Process output buffers with a reasonable timeout
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = decoder!!.dequeueOutputBuffer(bufferInfo, 5000) // 5ms timeout

            while (outputBufferIndex >= 0) {
                // Check for codec errors
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // Codec configuration data - typically handled automatically
                    decoder!!.releaseOutputBuffer(outputBufferIndex, false)
                } else if (bufferInfo.size > 0) {
                    // Regular frame, render it
                    decoder!!.releaseOutputBuffer(outputBufferIndex, true)
                }

                // Check for another output buffer with a short timeout
                outputBufferIndex = decoder!!.dequeueOutputBuffer(bufferInfo, 5000) // 5ms timeout
            }

            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Output format changed: ${decoder!!.outputFormat}")
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // No output available yet
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // Deprecated in API 21+, ignored
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Decoder in wrong state: ${e.message}")
            decodingErrors++

            // If we get too many errors, reset the decoder
            if (decodingErrors > 30) {
                Log.w(TAG, "Too many decoding errors, resetting decoder")
                resetDecoder()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing video data: ${e.message}")
            decodingErrors++
        }
    }

    private fun resetDecoder() {
        runOnUiThread {
            try {
                releaseDecoder()
                waitingForSPSPPS = true
                isCodecConfigured = false
                decodingErrors = 0

                // Request next keyframe to restart properly
                // (In a real application, you might send a request to the streaming server)

                Toast.makeText(this, "Resetting video decoder due to errors", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting decoder: ${e.message}")
            }
        }
    }

    private fun logStatistics(forcePrint: Boolean) {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - lastStatsTime

        // Log statistics every 5 seconds or when forced
        if (forcePrint || elapsedTime > 5000) {
            val packetsPerSecond = (packetCount * 1000.0 / elapsedTime).toInt()
            val framesPerSecond = (frameCount * 1000.0 / elapsedTime).toInt()

            Log.d(TAG, "Statistics: $packetsPerSecond packets/s, $framesPerSecond frames/s, Errors: $decodingErrors")

            // Reset counters
            packetCount = 0
            frameCount = 0
            lastStatsTime = currentTime

            // Only reset error counter on forced print (end of streaming)
            if (forcePrint) {
                decodingErrors = 0
            }
        }
    }

    private fun extractPort(serverAddress: String): String {
        return if (serverAddress.contains(":")) {
            serverAddress.split(":")[1].takeIf { it.isNotEmpty() } ?: "5001"
        } else {
            serverAddress
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        releaseDecoder()

        // Make sure wake lock is released
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }
}