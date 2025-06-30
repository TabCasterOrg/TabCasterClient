package com.example.streamclient3

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class NetworkDiscovery(
    private val context: Context,
    private val callback: DiscoveryCallback
) {
    companion object {
        private const val TAG = "NetworkDiscovery"
        private const val SERVICE_TYPE = "_screenstream._tcp."
    }

    interface DiscoveryCallback {
        fun onServerFound(serverAddress: String) // "ServerName@192.168.1.100:5001"
        fun onServerLost(serverName: String)
    }

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val discoveredServers = mutableSetOf<String>()
    private var isDiscovering = false

    fun start() {
        if (isDiscovering) return

        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    fun stop() {
        if (isDiscovering && discoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
                isDiscovering = false
                discoveredServers.clear()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
    }

    fun getServers(): List<String> = discoveredServers.toList()

    private fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
                isDiscovering = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == SERVICE_TYPE &&
                    !serviceInfo.serviceName.contains("AndroidStreamClient")) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val serviceName = serviceInfo.serviceName
                discoveredServers.removeIf { it.startsWith("$serviceName@") }
                callback.onServerLost(serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: $errorCode")
                isDiscovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }

        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed: ${serviceInfo.serviceName}")
            }

            override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                val serviceName = resolvedService.serviceName
                val address = resolvedService.host?.hostAddress ?: return
                val port = resolvedService.port

                val serverAddress = "$serviceName@$address:$port"
                discoveredServers.add(serverAddress)
                callback.onServerFound(serverAddress)
            }
        }

        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service", e)
        }
    }
}