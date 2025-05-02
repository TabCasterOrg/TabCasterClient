package com.example.streamclient3

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import androidx.media3.ui.PlayerView
import com.example.streamclient3.databinding.ActivityMainBinding
import androidx.constraintlayout.widget.ConstraintLayout

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable fullscreen button in PlayerView
        binding.playerView.setFullscreenButtonClickListener { toggleFullscreen() }

        binding.connectButton.setOnClickListener {
            releasePlayer()
            initializePlayer()
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

    private fun initializePlayer() {
        val serverAddress = binding.serverIpEditText.text.toString().trim()

        if (serverAddress.isEmpty()) {
            Toast.makeText(this, "Please enter server IP and port", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            releasePlayer()

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
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Error: ${error.errorCodeName}\n${error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_BUFFERING) {
                                runOnUiThread {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Buffering...",
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
            Toast.makeText(
                this,
                "Initialization failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun extractPort(serverAddress: String): String {
        return if (serverAddress.contains(":")) {
            serverAddress.split(":")[1].takeIf { it.isNotEmpty() } ?: "5001"
        } else {
            "5001"
        }
    }

    private fun extractIp(serverAddress: String): String {
        return if (serverAddress.contains(":")) {
            serverAddress.split(":")[0].takeIf { it.isNotEmpty() } ?: "0.0.0.0"
        } else {
            "0.0.0.0"
        }
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            playbackPosition = exoPlayer.currentPosition
            currentItem = exoPlayer.currentMediaItemIndex
            playWhenReady = exoPlayer.playWhenReady
            exoPlayer.release()
        }
        player = null
    }

    override fun onStart() {
        super.onStart()
        if (Util.SDK_INT > 23) {
            initializePlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Util.SDK_INT <= 23 || player == null) {
            initializePlayer()
        }
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