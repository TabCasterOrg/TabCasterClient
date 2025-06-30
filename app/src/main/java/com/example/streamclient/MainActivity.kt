package com.example.streamclient3

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.streamclient3.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(),
    NetworkDiscovery.DiscoveryCallback,
    StreamPlayer.StreamCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var networkDiscovery: NetworkDiscovery
    private lateinit var streamPlayer: StreamPlayer

    private var isStreaming = false
    private var isFullscreen = false

    companion object {
        private const val TAG = "StreamClient"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize components
        networkDiscovery = NetworkDiscovery(this, this)
        streamPlayer = StreamPlayer(this).apply {
            setCallback(this@MainActivity)
        }

        setupUI()
        setupPlayerView()

        // Start discovery
        networkDiscovery.start()
    }

    private fun setupUI() {
        binding.connectButton.setOnClickListener {
            handleConnection()
        }

        binding.serversButton.setOnClickListener {
            showServerSelectionDialog()
        }

        binding.disconnectButton.setOnClickListener {
            disconnect()
        }

        // Initially hide disconnect button
        binding.disconnectButton.visibility = View.GONE
    }

    private fun setupPlayerView() {
        // Replace the existing player view with our optimized one
        val playerView = streamPlayer.initializePlayerView()

        // Add player view to the container
        binding.playerContainer.removeAllViews()
        binding.playerContainer.addView(playerView,
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun handleConnection() {
        val manualInput = binding.serverInput.text.toString().trim()

        if (manualInput.isNotEmpty()) {
            // Manual connection
            connect(manualInput)
        } else {
            // Show discovered servers
            showServerSelectionDialog()
        }
    }

    private fun showServerSelectionDialog() {
        val servers = networkDiscovery.getServers()

        if (servers.isEmpty()) {
            Toast.makeText(this, "No servers found. Enter IP:port manually.", Toast.LENGTH_SHORT).show()
            return
        }

        val serverNames = servers.map { server ->
            // Format: "ServerName@192.168.1.100:5001" -> "ServerName (192.168.1.100:5001)"
            if (server.contains("@")) {
                val parts = server.split("@")
                "${parts[0]} (${parts[1]})"
            } else {
                server
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Server (${servers.size} found)")
            .setItems(serverNames) { _, which ->
                connect(servers[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connect(serverAddress: String) {
        Log.d(TAG, "Connecting to: $serverAddress")
        binding.statusText.text = "Connecting..."

        streamPlayer.connect(serverAddress)
    }

    private fun disconnect() {
        Log.d(TAG, "Disconnecting from stream")
        streamPlayer.release()
        exitFullscreen()
        updateUIForDisconnected()
    }

    private fun enterFullscreen() {
        if (isFullscreen) return

        // Hide system UI
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        // Force landscape orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Hide action bar
        supportActionBar?.hide()

        // Hide UI controls
        binding.controlsLayout.visibility = View.GONE

        isFullscreen = true
        Log.d(TAG, "Entered fullscreen landscape mode")
    }

    private fun exitFullscreen() {
        if (!isFullscreen) return

        // Restore system UI
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        // Return to portrait
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Show action bar
        supportActionBar?.show()

        // Show UI controls
        binding.controlsLayout.visibility = View.VISIBLE

        isFullscreen = false
        isStreaming = false
        Log.d(TAG, "Exited fullscreen mode")
    }

    private fun updateUIForConnected() {
        binding.statusText.text = "Connected - Ultra Low Latency"
        binding.connectButton.visibility = View.GONE
        binding.serversButton.visibility = View.GONE
        binding.disconnectButton.visibility = View.VISIBLE
        isStreaming = true
    }

    private fun updateUIForDisconnected() {
        binding.statusText.text = "Ready to connect"
        binding.connectButton.visibility = View.VISIBLE
        binding.serversButton.visibility = View.VISIBLE
        binding.disconnectButton.visibility = View.GONE
        isStreaming = false
    }

    // NetworkDiscovery.DiscoveryCallback
    override fun onServerFound(serverAddress: String) {
        runOnUiThread {
            val serverName = if (serverAddress.contains("@")) {
                serverAddress.split("@")[0]
            } else {
                "Server"
            }
            binding.statusText.text = "Found: $serverName"
        }
    }

    override fun onServerLost(serverName: String) {
        runOnUiThread {
            binding.statusText.text = "Lost: $serverName"
        }
    }

    // StreamPlayer.StreamCallback
    override fun onStreamReady() {
        runOnUiThread {
            updateUIForConnected()
            enterFullscreen() // Automatically enter fullscreen when stream starts
        }
    }

    override fun onStreamError(error: String) {
        runOnUiThread {
            binding.statusText.text = error
            updateUIForDisconnected()
        }
    }

    override fun onStreamStateChanged(isPlaying: Boolean) {
        // Handle any additional state changes if needed
    }

    override fun onResume() {
        super.onResume()
        networkDiscovery.start()
    }

    override fun onPause() {
        super.onPause()
        if (!isStreaming) {
            networkDiscovery.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkDiscovery.stop()
        streamPlayer.release()
    }

    override fun onBackPressed() {
        if (isFullscreen) {
            disconnect()
        } else {
            super.onBackPressed()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) {
            // Restore fullscreen mode if focus returns
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }
}