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

        val port = parsePort(serverAddress)
        val uri = "udp://0.0.0.0:$port"

        Log.d(TAG, "Connecting to UDP stream on port $port")

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

    private fun parsePort(serverAddress: String): String {
        // Handle formats: "192.168.1.100:5001", "5001", "ServerName@192.168.1.100:5001"
        return when {
            serverAddress.contains("@") -> {
                // Format: "ServerName@192.168.1.100:5001"
                val addressPart = serverAddress.split("@")[1]
                if (addressPart.contains(":")) {
                    addressPart.split(":")[1]
                } else "5001"
            }
            serverAddress.contains(":") -> {
                // Format: "192.168.1.100:5001"
                serverAddress.split(":")[1]
            }
            serverAddress.all { it.isDigit() } -> {
                // Format: "5001"
                serverAddress
            }
            else -> "5001" // Default fallback
        }
    }
}