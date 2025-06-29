package com.example.streamclient3


import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ServiceDiscoveryManager (
    private val context: Context,
    private val callback: ServiceDiscoveryCallback
) {
    companion object {
        private const val TAG = "ServiceDiscovery"
        private const val SERVICE_TYPE = "_screenstream._tcp"
        private const val SERVICE_NAME = "AndroidClient"
        private const val DEFAULT_STREAM_PORT = 5001
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
    private var isDiscovering = false
    private var isRegistered = false

    fun start() {
        try {
            callback.onLog("Starting NSD service discovery...")

            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

            registerService()
            startDiscovery()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service discovery", e)
            callback.onError("Failed to start service discovery: ${e.message}")
        }
    }

    fun stop() {
        try {
            callback.onLog("Stopping NSD service discovery...")

            stopDiscovery()
            unregisterService()

            discoveredServers.clear()

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service discovery", e)
        }
    }

    private fun registerService() {
        try {
            val metrics = getScreenMetrics()
            val deviceName = android.os.Build.MODEL.replace("\\s+".toRegex(), "_")
            val serviceName = "${SERVICE_NAME}_$deviceName"

            serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                serviceType = SERVICE_TYPE
                port = DEFAULT_STREAM_PORT

                // Add attributes (Android NSD supports limited attributes)
                setAttribute("width", metrics.widthPixels.toString())
                setAttribute("height", metrics.heightPixels.toString())
                setAttribute("density", metrics.densityDpi.toString())
                setAttribute("version", "1.0")
                setAttribute("type", "android_client")
                setAttribute("device", deviceName)
                setAttribute("os", "Android ${android.os.Build.VERSION.RELEASE}")
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Service registration failed: $errorCode")
                    callback.onError("Service registration failed: $errorCode")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "Service unregistration failed: $errorCode")
                }

                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    val registeredName = serviceInfo.serviceName
                    Log.i(TAG, "Service registered: $registeredName")

                    val metrics = getScreenMetrics()
                    val logMessage = "Registered service: $registeredName (${metrics.widthPixels}x${metrics.heightPixels})"
                    callback.onLog(logMessage)
                    callback.onServiceRegistered(registeredName)
                    isRegistered = true
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "Service unregistered: ${serviceInfo.serviceName}")
                    callback.onLog("Service unregistered")
                    isRegistered = false
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

        } catch (e: Exception) {
            Log.e(TAG, "Error registering service", e)
            callback.onError("Error registering service: ${e.message}")
        }
    }

    private fun unregisterService() {
        if (isRegistered && registrationListener != null) {
            try {
                nsdManager?.unregisterService(registrationListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering service", e)
            }
        }
    }

    private fun startDiscovery() {
        if (isDiscovering) return

        try {
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery start failed: $errorCode")
                    callback.onError("Discovery start failed: $errorCode")
                    isDiscovering = false
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery stop failed: $errorCode")
                }

                override fun onDiscoveryStarted(serviceType: String) {
                    Log.i(TAG, "Discovery started for: $serviceType")
                    callback.onLog("Started discovering services")
                    isDiscovering = true
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.i(TAG, "Discovery stopped for: $serviceType")
                    callback.onLog("Stopped discovering services")
                    isDiscovering = false
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                    callback.onLog("Service found: ${serviceInfo.serviceName}")

                    // Resolve the service to get full details
                    resolveService(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val serviceName = serviceInfo.serviceName
                    Log.d(TAG, "Service lost: $serviceName")
                    callback.onLog("Service lost: $serviceName")

                    discoveredServers.remove(serviceName)
                    callback.onServerLost(serviceName)
                }
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery", e)
            callback.onError("Error starting discovery: ${e.message}")
        }
    }

    private fun stopDiscovery() {
        if (isDiscovering && discoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${resolvedService.serviceName}")

                val serviceName = resolvedService.serviceName
                val address = resolvedService.host?.hostAddress ?: return
                val port = resolvedService.port

                // Extract attributes
                val attributes = resolvedService.attributes
                val type = getAttributeValue(attributes, "type") ?: "unknown"

                // Only process Linux servers (ignore our own service and other Android clients)
                if (type != "linux_server") {
                    Log.d(TAG, "Ignoring non-server service: $serviceName (type: $type)")
                    return
                }

                val serverInfo = ServerInfo(
                    name = serviceName,
                    address = address,
                    port = port,
                    width = getAttributeValue(attributes, "width")?.toIntOrNull() ?: 1920,
                    height = getAttributeValue(attributes, "height")?.toIntOrNull() ?: 1080,
                    framerate = getAttributeValue(attributes, "framerate")?.toIntOrNull() ?: 30,
                    version = getAttributeValue(attributes, "version") ?: "unknown",
                    type = type
                )

                discoveredServers[serviceName] = serverInfo

                val logMessage = "Discovered server: $serverInfo"
                Log.i(TAG, logMessage)
                callback.onLog(logMessage)
                callback.onServerDiscovered(serverInfo)
            }
        }

        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service", e)
        }
    }

    private fun getAttributeValue(attributes: Map<String, ByteArray>?, key: String): String? {
        return attributes?.get(key)?.let { String(it) }
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return metrics
    }

    fun getDiscoveredServers(): Map<String, ServerInfo> {
        return HashMap(discoveredServers)
    }

    fun hasServers(): Boolean = discoveredServers.isNotEmpty()

    fun getNewestServer(): ServerInfo? {
        return discoveredServers.values.maxByOrNull { it.discoveredAt }
    }

    fun cleanup() {
        stop()
    }
}