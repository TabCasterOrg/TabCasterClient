package com.example.streamclient3


import android.os.Bundle
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
import com.example.streamclient3.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var currentItem = 0
    private var playbackPosition = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectButton.setOnClickListener {
            releasePlayer()
            initializePlayer()
        }
    }

    private fun initializePlayer() {
        val serverAddress = binding.serverIpEditText.text.toString().trim()
        if (serverAddress.isEmpty()) {
            Toast.makeText(this, "Please enter server IP and port", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            releasePlayer()

            // 1. Custom zero-buffer load control
            val loadControl = createZeroBufferLoadControl()

            // 2. Custom UDP data source
            val udpDataSourceFactory = DataSource.Factory {
                ZeroLatencyUdpDataSource(50) // 50ms timeout
            }

            // 3. Build player with minimal latency settings
            player = ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .setRenderersFactory(
                    DefaultRenderersFactory(this)
                        .setAllowedVideoJoiningTimeMs(0)
                        .setEnableDecoderFallback(true)
                )
                .setClock(Clock.DEFAULT) // Use system clock for timing
                .setPriorityTaskManager(null) // No task prioritization
                .setUseLazyPreparation(false) // Prepare immediately
                .setSeekParameters(SeekParameters.EXACT) // No seek buffering
                .build()
                .also { exoPlayer ->
                    binding.playerView.player = exoPlayer

                    val port = extractPort(serverAddress)
                    val uri = "udp://0.0.0.0:$port?pkt_size=1316"
                    val mediaItem = MediaItem.fromUri(uri)

                    val dataSourceFactory = DefaultDataSource.Factory(
                        this,
                        udpDataSourceFactory
                    )

                    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy() {
                            override fun getRetryDelayMsFor(
                                loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
                            ): Long {
                                return 0 // No retry delay
                            }
                        })
                        .createMediaSource(mediaItem)

                    exoPlayer.playWhenReady = true
                    exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
                    exoPlayer.setMediaSource(mediaSource, true) // Reset position
                    exoPlayer.prepare()

                    // Error handling
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
                    })
                }

            Toast.makeText(
                this,
                "Listening on UDP port ${extractPort(serverAddress)} (zero-buffer mode)",
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
}

