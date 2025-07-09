package com.example.streamclient3

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

@UnstableApi
class StreamPlayer(private val context: Context) {

    companion object {
        private const val TAG = "StreamPlayer"

        // Ultra low latency settings - near zero buffering
        private const val MIN_BUFFER_MS = 50           // Absolute minimum buffer
        private const val MAX_BUFFER_MS = 100          // Keep buffer tiny
        private const val BUFFER_FOR_PLAYBACK_MS = 50  // Start playback immediately
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 50
        private const val TARGET_BUFFER_BYTES = -1     // No byte-based buffering
        private const val UDP_SOCKET_TIMEOUT_MS = 8000 // Socket timeout

        // Connection settings
        private const val CONNECTION_TIMEOUT_MS = 10000 // 10 seconds to wait for server response
        private const val MAX_RETRIES = 3
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var callback: StreamCallback? = null

    interface StreamCallback {
        fun onStreamReady()
        fun onStreamError(error: String)
        fun onStreamStateChanged(isPlaying: Boolean)
    }

    fun setCallback(callback: StreamCallback) {
        this.callback = callback
    }

    fun initializePlayerView(): PlayerView {
        if (playerView == null) {
            playerView = PlayerView(context).apply {
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowFastForwardButton(false)
                setShowRewindButton(false)
                keepScreenOn = true
            }
        }
        return playerView!!
    }

    fun connect(serverAddress: String) {
        release()

        val (serverIp, port) = parseServerAddress(serverAddress)

        Log.d(TAG, "Connecting to server: $serverIp:$port")

        // Send connection message to server in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val success = sendConnectionMessage(serverIp, port)

                withContext(Dispatchers.Main) {
                    if (success) {
                        // Server acknowledged connection, start streaming
                        startStreaming(port)
                    } else {
                        callback?.onStreamError("Failed to connect to server")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to server", e)
                withContext(Dispatchers.Main) {
                    callback?.onStreamError("Connection failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun sendConnectionMessage(serverIp: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.soTimeout = CONNECTION_TIMEOUT_MS

                val serverAddress = InetAddress.getByName(serverIp)

                // Get screen dimensions (or use default)
                val displayMetrics = context.resources.displayMetrics
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels
                val framerate = 30 // Default framerate

                // Create connection message
                val message = "CONNECT:$width:$height:$framerate"
                val messageBytes = message.toByteArray()

                Log.d(TAG, "Sending connection message: $message")

                // Send connection message
                val packet = DatagramPacket(
                    messageBytes,
                    messageBytes.size,
                    serverAddress,
                    port
                )
                socket.send(packet)

                // Wait for acknowledgment
                val buffer = ByteArray(1024)
                val responsePacket = DatagramPacket(buffer, buffer.size)

                var retries = 0
                while (retries < MAX_RETRIES) {
                    try {
                        socket.receive(responsePacket)
                        val response = String(responsePacket.data, 0, responsePacket.length)
                        Log.d(TAG, "Server response: $response")

                        if (response.contains("ACK") || response.contains("SERVER_READY")) {
                            Log.d(TAG, "Server acknowledged connection")
                            return@withContext true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Retry $retries failed: ${e.message}")
                        retries++
                        if (retries >= MAX_RETRIES) {
                            Log.e(TAG, "Max retries exceeded")
                            return@withContext false
                        }
                    }
                }

                false
            } catch (e: Exception) {
                Log.e(TAG, "Error sending connection message", e)
                false
            } finally {
                socket?.close()
            }
        }
    }

    private fun startStreaming(port: Int) {
        val uri = "udp://0.0.0.0:$port"

        Log.d(TAG, "Starting UDP stream on port $port")

        try {
            // Ultra low latency load control - aggressive settings for minimal buffering
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,                              // Min buffer
                    MAX_BUFFER_MS,                              // Max buffer
                    BUFFER_FOR_PLAYBACK_MS,                     // Buffer for playback
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS       // Buffer after rebuffer
                )
                .setTargetBufferBytes(TARGET_BUFFER_BYTES)      // No byte limits
                .setPrioritizeTimeOverSizeThresholds(true)      // Prioritize time
                .setBackBuffer(0, false)                        // No back buffer
                .build()

            // Ultra low latency UDP data source
            val udpDataSourceFactory = DataSource.Factory {
                UdpDataSource(UDP_SOCKET_TIMEOUT_MS)
            }

            // Renderer factory optimized for low latency
            val renderersFactory = DefaultRenderersFactory(context)
                .setAllowedVideoJoiningTimeMs(0)              // No joining delay
                .setEnableDecoderFallback(false)              // No fallback delays

            player = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setRenderersFactory(renderersFactory)
                .build()
                .apply {
                    // Ultra low latency playback settings
                    setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                    setSkipSilenceEnabled(false)
                    setWakeMode(C.WAKE_MODE_NETWORK)

                    // Aggressive playback settings
                    playWhenReady = true

                    // Set up media source with UDP
                    val dataSourceFactory = DefaultDataSource.Factory(context, udpDataSourceFactory)
                    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri))

                    setMediaSource(mediaSource)
                    prepare()

                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    Log.d(TAG, "Stream ready - ultra low latency mode")
                                    callback?.onStreamReady()
                                }
                                Player.STATE_BUFFERING -> {
                                    Log.d(TAG, "Buffering (minimal)")
                                }
                                Player.STATE_ENDED -> {
                                    Log.d(TAG, "Stream ended")
                                    callback?.onStreamError("Stream ended")
                                }
                            }

                            val isPlaying = playbackState == Player.STATE_READY && playWhenReady
                            callback?.onStreamStateChanged(isPlaying)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "Player error: ${error.errorCodeName}")
                            callback?.onStreamError("Stream error: ${error.errorCodeName}")
                        }
                    })
                }

            playerView?.player = player

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize player", e)
            callback?.onStreamError("Connection failed: ${e.message}")
        }
    }

    fun release() {
        player?.release()
        player = null
    }

    fun isPlaying(): Boolean {
        return player?.isPlaying == true
    }

    private fun parseServerAddress(serverAddress: String): Pair<String, Int> {
        // Handle formats: "192.168.1.100:5001", "5001", "ServerName@192.168.1.100:5001"
        return when {
            serverAddress.contains("@") -> {
                // Format: "ServerName@192.168.1.100:5001"
                val addressPart = serverAddress.split("@")[1]
                if (addressPart.contains(":")) {
                    val parts = addressPart.split(":")
                    Pair(parts[0], parts[1].toInt())
                } else {
                    Pair(addressPart, 5001)
                }
            }
            serverAddress.contains(":") -> {
                // Format: "192.168.1.100:5001"
                val parts = serverAddress.split(":")
                Pair(parts[0], parts[1].toInt())
            }
            serverAddress.all { it.isDigit() } -> {
                // Format: "5001" - assume localhost
                Pair("127.0.0.1", serverAddress.toInt())
            }
            else -> {
                // Assume it's an IP address without port
                Pair(serverAddress, 5001)
            }
        }
    }
}