package com.example.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NsdDiscoveryManager(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_wavedrop._tcp" // Removed trailing dot for discovery
    private val serviceName = "WaveDropDevice"

    private val _discoveredDevices = MutableStateFlow<List<NsdServiceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<NsdServiceInfo>> = _discoveredDevices

    private var isResolving = false
    private val pendingResolves = mutableListOf<NsdServiceInfo>()

    private fun resolveNext() {
        if (isResolving || pendingResolves.isEmpty()) return
        val service = pendingResolves.removeAt(0)
        isResolving = true
        try {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e("NSD", "Resolve failed: $errorCode")
                    isResolving = false
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    Log.d("NSD", "Resolve Succeeded: $serviceInfo")
                    val current = _discoveredDevices.value.toMutableList()
                    if (current.none { it.serviceName == serviceInfo.serviceName }) {
                        current.add(serviceInfo)
                        _discoveredDevices.value = current
                    }
                    isResolving = false
                    resolveNext()
                }
            })
        } catch (e: Exception) {
            Log.e("NSD", "Resolve crash", e)
            isResolving = false
            resolveNext()
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d("NSD", "Service discovery started")
            isDiscovering = true
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d("NSD", "Service found: ${service.serviceName}")
            // Check if it's our service type
            if (service.serviceType.contains(serviceType)) {
                pendingResolves.add(service)
                resolveNext()
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.d("NSD", "Service lost: ${service.serviceName}")
            val current = _discoveredDevices.value.toMutableList()
            current.removeAll { it.serviceName == service.serviceName }
            _discoveredDevices.value = current
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i("NSD", "Discovery stopped")
            isDiscovering = false
            pendingResolves.clear()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("NSD", "Start discovery failed: $errorCode")
            try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("NSD", "Stop discovery failed: $errorCode")
            try { nsdManager.stopServiceDiscovery(this) } catch (e: Exception) {}
        }
    }

    private var isRegistered = false
    private var isDiscovering = false

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(regInfo: NsdServiceInfo) {
            Log.d("NSD", "Service registered: ${regInfo.serviceName}")
            isRegistered = true
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("NSD", "Registration failed: $errorCode")
            isRegistered = false
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            Log.d("NSD", "Service unregistered: ${arg0.serviceName}")
            isRegistered = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("NSD", "Unregistration failed: $errorCode")
        }
    }

    fun registerService(port: Int, customName: String) {
        if (isRegistered) return
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        var deviceModel = if (model.lowercase().startsWith(manufacturer.lowercase())) {
            model
        } else {
            "${manufacturer} ${model}"
        }
        deviceModel = deviceModel.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        val safeModel = deviceModel.replace(Regex("[^a-zA-Z0-9-]"), "-").take(24)
        
        val sanitized = customName.replace("[^a-zA-Z0-9 -]".toRegex(), "").trim()
        val finalServiceName = if (sanitized.isNotEmpty() && sanitized != "My Android Device" && sanitized != "Unnamed_Device" && sanitized != "My Application") {
            sanitized
        } else {
            safeModel
        }
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = finalServiceName + "_" + (System.currentTimeMillis() % 1000)
            this.serviceType = this@NsdDiscoveryManager.serviceType
            this.port = port
        }
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("NSD", "Failed to register", e)
        }
    }

    fun unregisterService() {
        if (isRegistered) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (e: Exception) {
                Log.e("NSD", "Failed to unregister", e)
            }
        }
    }

    fun discoverServices() {
        if (isDiscovering) return
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            isDiscovering = true
        } catch (e: Exception) {
             Log.e("NSD", "Failed to discover", e)
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
            isDiscovering = false
        } catch (e: Exception) {
            // Already stopped or failed
        }
    }
}
