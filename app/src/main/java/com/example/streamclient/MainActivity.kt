package com.example.streamclient3

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.example.streamclient3.databinding.ActivityMainBinding
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var currentItem = 0
    private var playbackPosition = 0L
    private var isFullscreen = false
    private val originalPlayerViewParams by lazy {
        binding.playerView.layoutParams as ConstraintLayout.LayoutParams
    }
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "StreamClient"
        private const val DIMENSION_SEND_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val PLAYER_INIT_DELAY_MS = 1500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable fullscreen button in PlayerView
        binding.playerView.setFullscreenButtonClickListener { toggleFullscreen() }

        binding.connectButton.setOnClickListener {
            val serverAddress = binding.serverIpEditText.text.toString().trim()
            if (serverAddress.isEmpty()) {
                Toast.makeText(this, "Please enter server IP and port", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // First release any existing player
            releasePlayer()

            // Show connecting status
            Toast.makeText(this, "Connecting to server...", Toast.LENGTH_SHORT).show()

            // Start connection process
            startConnectionProcess(serverAddress)
        }
    }

    private fun startConnectionProcess(serverAddress: String) {
        // Run in background thread to avoid blocking UI
        thread(start = true) {
            Thread.sleep(500) //slight delay before sending dims
            var success = false

            // Try sending dimensions multiple times in case of failure
            for (attempt in 1..DIMENSION_SEND_RETRIES) {
                Log.d(TAG, "Sending dimensions, attempt $attempt")
                success = sendScreenDimensions(serverAddress)

                if (success) {
                    Log.d(TAG, "Successfully sent dimensions to server")
                    break
                } else {
                    if (attempt < DIMENSION_SEND_RETRIES) {
                        Log.d(TAG, "Failed to send dimensions, retrying in ${RETRY_DELAY_MS}ms")
                        Thread.sleep(RETRY_DELAY_MS)
                    }
                }
            }

            // Now initialize the player after a short delay to give server time to start streaming
            if (success) {
                runOnUiThread {
                    Toast.makeText(this, "Dimensions sent, preparing player...", Toast.LENGTH_SHORT).show()
                }

                // Wait a moment for server to start streaming
                Thread.sleep(PLAYER_INIT_DELAY_MS)

                // Initialize player on UI thread
                runOnUiThread {
                    initializePlayer(serverAddress)
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Failed to connect to server", Toast.LENGTH_LONG).show()
                }
            }
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

        // Make PlayerView fill the screen
        val params = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.MATCH_PARENT
        )
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        binding.playerView.layoutParams = params

        // Hide other UI elements
        binding.serverIpEditText.visibility = View.GONE
        binding.connectButton.visibility = View.GONE

        isFullscreen = true
    }

    private fun exitFullscreen() {
        // Show system UI
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        // Show action bar
        supportActionBar?.show()

        // Set portrait orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Restore original PlayerView layout
        binding.playerView.layoutParams = originalPlayerViewParams

        // Show other UI elements
        binding.serverIpEditText.visibility = View.VISIBLE
        binding.connectButton.visibility = View.VISIBLE

        isFullscreen = false
    }

    // Send screen dimensions for xrandr
    private fun sendScreenDimensions(serverAddress: String): Boolean {
        return try {
            val socket = DatagramSocket()
            val serverIp = extractIp(serverAddress)
            val port = extractPort(serverAddress).toInt()

            // Get screen dimensions
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels

            // Get client's IP address (to help server send back to right address)
            val localIp = getLocalIpAddress()

            // Create dimension packet: "DIMS:width:height:clientIP"
            val message = "DIMS:$width:$height:$localIp"
            val buffer = message.toByteArray()

            // Check if we're using a proper IP or broadcast
            if (serverIp == "0.0.0.0" || serverIp.endsWith(".255")) {
                Log.d(TAG, "Using broadcast address or wildcard, trying direct send to server")
                // Try to find a better server address if available
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Using broadcast to find server at port $port",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // Enable broadcast if needed
            socket.broadcast = true

            val packet = DatagramPacket(
                buffer,
                buffer.size,
                InetAddress.getByName(serverIp),
                port
            )

            socket.send(packet)

            // Send a second packet to 255.255.255.255 to ensure broadcast works
            if (serverIp != "255.255.255.255") {
                val broadcastPacket = DatagramPacket(
                    buffer,
                    buffer.size,
                    InetAddress.getByName("255.255.255.255"),
                    port
                )
                socket.send(broadcastPacket)
                Log.d(TAG, "Also sent broadcast dimensions to 255.255.255.255:$port")
            }

            socket.close()

            Log.d(TAG, "Sent dimensions: $width x $height to $serverIp:$port from $localIp")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send dimensions: ${e.message}")
            false
        }
    }

    // Helper method to get device's local IP
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    // Filter for IPv4 addresses that aren't loopback
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP: ${e.message}")
        }

        return "127.0.0.1" // Fallback to localhost
    }

    private fun initializePlayer(serverAddress: String) {
        try {
            Log.d(TAG, "Initializing player for UDP stream")

            val loadControl: LoadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(5, 5, 5, 5)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)
                .setTargetBufferBytes(-1)
                .build()

            val udpDataSourceFactory = DataSource.Factory {
                UdpDataSource(50)
            }

            player = ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .setRenderersFactory(
                    DefaultRenderersFactory(this)
                        .setAllowedVideoJoiningTimeMs(0)
                )
                .build()
                .also { exoPlayer ->
                    binding.playerView.player = exoPlayer
                    binding.playerView.setShowNextButton(false)
                    binding.playerView.setShowPreviousButton(false)
                    binding.playerView.setShowFastForwardButton(false)
                    binding.playerView.setShowRewindButton(false)

                    val port = extractPort(serverAddress)
                    val uri = "udp://0.0.0.0:$port?pkt_size=1316"
                    Log.d(TAG, "Setting up player with URI: $uri")

                    val mediaItem = MediaItem.fromUri(uri)

                    val dataSourceFactory = DefaultDataSource.Factory(
                        this,
                        udpDataSourceFactory
                    )

                    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(10))
                        .createMediaSource(mediaItem)

                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.playWhenReady = playWhenReady
                    exoPlayer.seekTo(currentItem, playbackPosition)
                    exoPlayer.prepare()

                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "Player error: ${error.errorCodeName} - ${error.message}")
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Error: ${error.errorCodeName}\n${error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            val stateStr = when(state) {
                                Player.STATE_IDLE -> "IDLE"
                                Player.STATE_BUFFERING -> "BUFFERING"
                                Player.STATE_READY -> "READY"
                                Player.STATE_ENDED -> "ENDED"
                                else -> "UNKNOWN"
                            }
                            Log.d(TAG, "Player state changed to: $stateStr")

                            if (state == Player.STATE_BUFFERING) {
                                runOnUiThread {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Buffering...",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else if (state == Player.STATE_READY) {
                                runOnUiThread {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Stream ready",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    })
                }

            Toast.makeText(
                this,
                "Listening on UDP port ${extractPort(serverAddress)}",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Log.e(TAG, "Player initialization failed", e)
            Toast.makeText(
                this,
                "Initialization failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun extractPort(input: String): String {
        // If input contains a colon, it's in IP:port format (legacy support)
        if (input.contains(":")) {
            return input.split(":")[1].takeIf { it.isNotEmpty() } ?: "5001"
        }
        // Otherwise, just use the input as port
        return input.takeIf { it.isNotEmpty() } ?: "5001"
    }

    private fun extractIp(serverAddress: String): String {
        val ip = if (serverAddress.contains(":")) {
            serverAddress.split(":")[0].takeIf { it.isNotEmpty() } ?: "0.0.0.0"
        } else {
            "0.0.0.0"
        }

        // Log the extracted IP for debugging
        Log.d(TAG, "Extracted server IP: $ip from input: $serverAddress")
        return ip
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            Log.d(TAG, "Releasing player")
            playbackPosition = exoPlayer.currentPosition
            currentItem = exoPlayer.currentMediaItemIndex
            playWhenReady = exoPlayer.playWhenReady
            exoPlayer.release()
        }
        player = null
    }

    override fun onStart() {
        super.onStart()
        // Don't auto-initialize on start, let user press connect button
    }

    override fun onResume() {
        super.onResume()
        // Don't auto-initialize on resume, let user press connect button
    }

    override fun onPause() {
        super.onPause()
        if (Util.SDK_INT <= 23) {
            releasePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Util.SDK_INT > 23) {
            releasePlayer()
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