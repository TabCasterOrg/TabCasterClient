package com.example.streamclient3

import android.app.AlertDialog
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
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.datasource.DataSpec
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import java.io.IOException

@UnstableApi
class RobustUdpDataSource(private val socketTimeoutMs: Int) : DataSource {
    private val udpDataSource = UdpDataSource(socketTimeoutMs)
    private var lastSuccessfulReadTime = 0L
    private var dataSpec: DataSpec? = null

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        lastSuccessfulReadTime = System.currentTimeMillis()
        return udpDataSource.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        try {
            val bytesRead = udpDataSource.read(buffer, offset, length)
            lastSuccessfulReadTime = System.currentTimeMillis()
            return bytesRead
        } catch (e: IOException) {
            if (System.currentTimeMillis() - lastSuccessfulReadTime > 5000) {
                close()
                dataSpec?.let { open(it) }
            }
            throw e
        }
    }

    override fun getUri() = udpDataSource.uri
    override fun close() = udpDataSource.close()
    override fun addTransferListener(transferListener: TransferListener) {
        udpDataSource.addTransferListener(transferListener)
    }
}

class MainActivity : AppCompatActivity(), ServiceDiscoveryManager.ServiceDiscoveryCallback {

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

    // Service Discovery
    private lateinit var serviceDiscovery: ServiceDiscoveryManager
    private var currentServer: ServiceDiscoveryManager.ServerInfo? = null
    private var useServiceDiscovery = true

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

        // Initialize service discovery
        serviceDiscovery = ServiceDiscoveryManager(this, this)

        // Enable fullscreen button in PlayerView
        binding.playerView.setFullscreenButtonClickListener { toggleFullscreen() }
        binding.playerView.keepScreenOn = true

        setupButtons()

        // Start service discovery automatically
        serviceDiscovery.start()
        updateStatus("Starting service discovery...")
    }

    private fun setupButtons() {
        // Enhanced connect button - now supports both modes
        binding.connectButton.setOnClickListener {
            val serverInput = binding.serverIpEditText.text.toString().trim()

            if (serverInput.isEmpty()) {
                // Try auto-discovery if no input
                if (serviceDiscovery.hasServers()) {
                    val newestServer = serviceDiscovery.getNewestServer()
                    if (newestServer != null) {
                        connectToDiscoveredServer(newestServer)
                        return@setOnClickListener
                    }
                }
                showServerSelectionDialog()
                return@setOnClickListener
            }

            // Manual connection mode
            useServiceDiscovery = false
            startConnectionProcess(serverInput)
        }

        // Add long click for server selection dialog
        binding.connectButton.setOnLongClickListener {
            showServerSelectionDialog()
            true
        }
    }

    private fun showServerSelectionDialog() {
        val servers = serviceDiscovery.getDiscoveredServers()

        if (servers.isEmpty()) {
            Toast.makeText(this, "No servers discovered. Enter IP:port manually.", Toast.LENGTH_LONG).show()
            return
        }

        val serverList = servers.values.toList()
        val serverNames = serverList.map { "${it.name}\n${it.address}:${it.port} (${it.width}x${it.height})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Server")
            .setItems(serverNames) { _, which ->
                val selectedServer = serverList[which]
                connectToDiscoveredServer(selectedServer)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectToDiscoveredServer(serverInfo: ServiceDiscoveryManager.ServerInfo) {
        currentServer = serverInfo
        useServiceDiscovery = true
        updateStatus("Connecting to ${serverInfo.name}...")

        // For discovered servers, we don't need to send dimensions manually
        // as they were already exchanged via service discovery
        releasePlayer()

        // Small delay then start player
        handler.postDelayed({
            val serverAddress = "${serverInfo.address}:${serverInfo.port}"
            initializePlayer(serverAddress)
        }, 500)
    }

    private fun updateStatus(message: String) {
        Log.d(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // Service Discovery Callbacks
    override fun onServerDiscovered(serverInfo: ServiceDiscoveryManager.ServerInfo) {
        handler.post {
            updateStatus("Found server: ${serverInfo.name}")

            // Auto-connect to first server if none connected and no manual input
            if (currentServer == null && binding.serverIpEditText.text.toString().trim().isEmpty()) {
                connectToDiscoveredServer(serverInfo)
            }
        }
    }

    override fun onServerLost(serverName: String) {
        handler.post {
            updateStatus("Server lost: $serverName")

            if (currentServer?.name == serverName) {
                currentServer = null
                // Try to reconnect to another server if available
                val newestServer = serviceDiscovery.getNewestServer()
                if (newestServer != null && newestServer.name != serverName) {
                    connectToDiscoveredServer(newestServer)
                } else {
                    releasePlayer()
                }
            }
        }
    }

    override fun onServiceRegistered(serviceName: String) {
        handler.post {
            updateStatus("Ready - Service registered as: $serviceName")
        }
    }

    override fun onError(error: String) {
        handler.post {
            updateStatus("Discovery Error: $error")
        }
    }

    override fun onLog(message: String) {
        Log.d(TAG, "Discovery: $message")
    }

    // Rest of your existing methods remain exactly the same...
    private fun startConnectionProcess(serverAddress: String) {
        releasePlayer()
        updateStatus("Connecting to server...")

        thread(start = true) {
            Thread.sleep(500)
            var success = false

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

            if (success) {
                runOnUiThread {
                    updateStatus("Dimensions sent, preparing player...")
                }

                Thread.sleep(PLAYER_INIT_DELAY_MS)

                runOnUiThread {
                    initializePlayer(serverAddress)
                }
            } else {
                runOnUiThread {
                    updateStatus("Failed to connect to server")
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
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        supportActionBar?.hide()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val params = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.MATCH_PARENT
        )
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        binding.playerView.layoutParams = params

        binding.serverIpEditText.visibility = View.GONE
        binding.connectButton.visibility = View.GONE

        isFullscreen = true
    }

    private fun exitFullscreen() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        supportActionBar?.show()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding.playerView.layoutParams = originalPlayerViewParams
        binding.serverIpEditText.visibility = View.VISIBLE
        binding.connectButton.visibility = View.VISIBLE
        isFullscreen = false
    }

    private fun sendScreenDimensions(serverAddress: String): Boolean {
        return try {
            val socket = DatagramSocket()
            val serverIp = extractIp(serverAddress)
            val port = extractPort(serverAddress).toInt()

            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val localIp = getLocalIpAddress()

            val message = "DIMS:$width:$height:$localIp"
            val buffer = message.toByteArray()

            if (serverIp == "0.0.0.0" || serverIp.endsWith(".255")) {
                Log.d(TAG, "Using broadcast address or wildcard, trying direct send to server")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Using broadcast to find server at port $port",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            socket.broadcast = true

            val packet = DatagramPacket(
                buffer,
                buffer.size,
                InetAddress.getByName(serverIp),
                port
            )

            socket.send(packet)

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

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP: ${e.message}")
        }
        return "127.0.0.1"
    }

    private fun initializePlayer(serverAddress: String) {
        try {
            Log.d(TAG, "Initializing player for UDP stream")

            val loadControl: LoadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(1, 1, 1, 1)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(1, false)
                .build()

            val udpDataSourceFactory = DataSource.Factory {
                RobustUdpDataSource(1000)
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
                    val uri = "udp://0.0.0.0:$port"
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
                    exoPlayer.setWakeMode(C.WAKE_MODE_LOCAL)
                    exoPlayer.setWakeMode(C.WAKE_MODE_NETWORK)
                    exoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)

                    exoPlayer.addAnalyticsListener(object : AnalyticsListener {
                        fun onVideoInputFormatChanged(
                            eventTime: EventTime,
                            format: Format,
                            trackType: Int
                        ) {
                            if (trackType == C.TRACK_TYPE_VIDEO) {
                                Log.d("UDPStream", "Bitrate changed: ${format.bitrate}bps")
                            }
                        }

                        override fun onDroppedVideoFrames(
                            eventTime: EventTime,
                            droppedFrames: Int,
                            elapsedMs: Long
                        ) {
                            Log.w("UDPStream", "Dropped $droppedFrames frames in $elapsedMs ms")
                        }

                        override fun onLoadStarted(
                            eventTime: EventTime,
                            loadEventInfo: LoadEventInfo,
                            mediaLoadData: MediaLoadData
                        ) {
                            Log.d("UDPStream", "Buffer refill started")
                        }
                    })

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
                                    val serverInfo = if (useServiceDiscovery) currentServer?.name ?: "auto-discovered server" else "manual server"
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Stream ready from $serverInfo",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    })
                }

            val connectionType = if (useServiceDiscovery) "auto-discovered" else "manual"
            Toast.makeText(
                this,
                "Listening on UDP port ${extractPort(serverAddress)} ($connectionType)",
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
        if (input.contains(":")) {
            return input.split(":")[1].takeIf { it.isNotEmpty() } ?: "5001"
        }
        return input.takeIf { it.isNotEmpty() } ?: "5001"
    }

    private fun extractIp(serverAddress: String): String {
        val ip = if (serverAddress.contains(":")) {
            serverAddress.split(":")[0].takeIf { it.isNotEmpty() } ?: "0.0.0.0"
        } else {
            "0.0.0.0"
        }

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

    override fun onDestroy() {
        super.onDestroy()
        serviceDiscovery.cleanup()
        releasePlayer()
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