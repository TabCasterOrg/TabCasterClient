package com.example.streamclient3

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ServiceDiscoveryManager(
    private val context: Context,
    private val callback: ServiceDiscoveryCallback
) {
    companion object {
        private const val TAG = "ServiceDiscovery"
        // Simplified service type - standard format without .local suffix
        private const val SERVICE_TYPE = "_screenstream._tcp."
        private const val SERVICE_NAME = "AndroidStreamClient"
        private const val DEFAULT_PORT = 5001
    }

    interface ServiceDiscoveryCallback {
        fun onServerDiscovered(serverInfo: ServerInfo)
        fun onServerLost(serverName: String)
        fun onServiceRegistered(serviceName: String)
        fun onError(error: String)
        fun onLog(message: String)
    }

    data class ServerInfo(
        val name: String,
        val address: String,
        val port: Int,
        val width: Int = 1920,
        val height: Int = 1080,
        val framerate: Int = 30,
        val version: String = "unknown",
        val type: String = "unknown",
        val discoveredAt: Long = System.currentTimeMillis()
    ) {
        override fun toString(): String {
            return "$name ($address:$port) [${width}x${height}@${framerate}fps]"
        }
    }

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serviceInfo: NsdServiceInfo? = null
    private val discoveredServers = mutableMapOf<String, ServerInfo>()

    private var isRegistered = false
    private var isDiscovering = false

    fun start() {
        try {
            callback.onLog("Initializing NSD service...")
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

            // Start both registration and discovery
            registerService()
            startDiscovery()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD", e)
            callback.onError("Failed to start NSD: ${e.message}")
        }
    }

    fun stop() {
        try {
            callback.onLog("Stopping NSD service...")
            stopDiscovery()
            unregisterService()
            discoveredServers.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NSD", e)
        }
    }

    private fun registerService() {
        if (isRegistered) return

        try {
            val metrics = getScreenMetrics()
            val deviceName = android.os.Build.MODEL.replace("\\s+".toRegex(), "_")
            val serviceName = "${SERVICE_NAME}_$deviceName"

            serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                this.serviceType = SERVICE_TYPE
                this.port = DEFAULT_PORT

                // Add basic device info as attributes
                setAttribute("width", metrics.widthPixels.toString())
                setAttribute("height", metrics.heightPixels.toString())
                setAttribute("type", "android_client")
                setAttribute("version", "1.0")
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    val actualName = serviceInfo.serviceName
                    Log.i(TAG, "Service registered as: $actualName")
                    callback.onLog("Service registered as: $actualName")
                    callback.onServiceRegistered(actualName)
                    isRegistered = true
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    val errorMsg = getErrorMessage(errorCode, "registration")
                    Log.e(TAG, "Registration failed: $errorMsg")
                    callback.onError("Registration failed: $errorMsg")
                    isRegistered = false
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "Service unregistered")
                    callback.onLog("Service unregistered")
                    isRegistered = false
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    val errorMsg = getErrorMessage(errorCode, "unregistration")
                    Log.e(TAG, "Unregistration failed: $errorMsg")
                    callback.onError("Unregistration failed: $errorMsg")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            Log.d(TAG, "Registering service: $serviceName on port $DEFAULT_PORT")

        } catch (e: Exception) {
            Log.e(TAG, "Error registering service", e)
            callback.onError("Registration error: ${e.message}")
        }
    }

    private fun unregisterService() {
        if (isRegistered && registrationListener != null) {
            try {
                nsdManager?.unregisterService(registrationListener)
                isRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering service", e)
            }
        }
    }

    private fun startDiscovery() {
        if (isDiscovering) return

        try {
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.i(TAG, "Discovery started for: $serviceType")
                    callback.onLog("Started discovering services")
                    isDiscovering = true
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service found: ${serviceInfo.serviceName}")

                    // Filter out our own service and non-matching service types
                    when {
                        serviceInfo.serviceType != SERVICE_TYPE -> {
                            Log.d(TAG, "Ignoring service with different type: ${serviceInfo.serviceType}")
                        }
                        serviceInfo.serviceName.contains("AndroidStreamClient") -> {
                            Log.d(TAG, "Ignoring our own service or another Android client")
                        }
                        else -> {
                            callback.onLog("Found potential server: ${serviceInfo.serviceName}")
                            resolveService(serviceInfo)
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val serviceName = serviceInfo.serviceName
                    Log.d(TAG, "Service lost: $serviceName")

                    discoveredServers.remove(serviceName)
                    callback.onLog("Server lost: $serviceName")
                    callback.onServerLost(serviceName)
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.i(TAG, "Discovery stopped")
                    callback.onLog("Stopped discovering services")
                    isDiscovering = false
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    val errorMsg = getErrorMessage(errorCode, "discovery start")
                    Log.e(TAG, "Discovery start failed: $errorMsg")
                    callback.onError("Discovery start failed: $errorMsg")
                    isDiscovering = false
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    val errorMsg = getErrorMessage(errorCode, "discovery stop")
                    Log.e(TAG, "Discovery stop failed: $errorMsg")
                    callback.onError("Discovery stop failed: $errorMsg")
                }
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            Log.d(TAG, "Starting discovery for: $SERVICE_TYPE")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery", e)
            callback.onError("Discovery error: ${e.message}")
        }
    }

    private fun stopDiscovery() {
        if (isDiscovering && discoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
                isDiscovering = false
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                val errorMsg = getErrorMessage(errorCode, "resolve")
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorMsg")
            }

            override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                val serviceName = resolvedService.serviceName
                val address = resolvedService.host?.hostAddress
                val port = resolvedService.port

                if (address == null) {
                    Log.w(TAG, "No address found for service: $serviceName")
                    return
                }

                Log.d(TAG, "Service resolved: $serviceName at $address:$port")

                // Extract attributes safely
                val attributes = resolvedService.attributes ?: emptyMap()
                val type = getAttributeValue(attributes, "type") ?: "unknown"

                // Only process Linux servers (ignore Android clients)
                if (type == "linux_server" || serviceName.contains("Server", ignoreCase = true)) {
                    val serverInfo = ServerInfo(
                        name = serviceName,
                        address = address,
                        port = port,
                        width = getAttributeValue(attributes, "width")?.toIntOrNull() ?: 1920,
                        height = getAttributeValue(attributes, "height")?.toIntOrNull() ?: 1080,
                        framerate = getAttributeValue(attributes, "framerate")?.toIntOrNull() ?: 30,
                        version = getAttributeValue(attributes, "version") ?: "1.0",
                        type = type
                    )

                    discoveredServers[serviceName] = serverInfo

                    val message = "Server discovered: $serverInfo"
                    Log.i(TAG, message)
                    callback.onLog(message)
                    callback.onServerDiscovered(serverInfo)
                } else {
                    Log.d(TAG, "Ignoring non-server service: $serviceName (type: $type)")
                }
            }
        }

        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service: ${serviceInfo.serviceName}", e)
        }
    }

    private fun getAttributeValue(attributes: Map<String, ByteArray>, key: String): String? {
        return try {
            attributes[key]?.let { String(it, Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading attribute $key", e)
            null
        }
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return metrics
    }

    private fun getErrorMessage(errorCode: Int, operation: String): String {
        return when (errorCode) {
            NsdManager.FAILURE_ALREADY_ACTIVE -> "$operation already active"
            NsdManager.FAILURE_INTERNAL_ERROR -> "Internal $operation error"
            NsdManager.FAILURE_MAX_LIMIT -> "Maximum $operation limit reached"
            else -> "Unknown $operation error: $errorCode"
        }
    }

    // Public interface methods
    fun getDiscoveredServers(): Map<String, ServerInfo> = HashMap(discoveredServers)

    fun hasServers(): Boolean = discoveredServers.isNotEmpty()

    fun getNewestServer(): ServerInfo? = discoveredServers.values.maxByOrNull { it.discoveredAt }

    fun cleanup() = stop()
}