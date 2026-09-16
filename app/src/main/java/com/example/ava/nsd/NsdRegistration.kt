package com.example.ava.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.core.content.ContextCompat
import timber.log.Timber

class NsdRegistration(
    var name: String,
    type: String,
    port: Int,
    attributes: Map<String, String> = emptyMap()
) {
    private val serviceInfo = NsdServiceInfo().apply {
        serviceName = name
        serviceType = type
        this.port = port
        for ((key, value) in attributes) {
            setAttribute(key, value)
        }
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
            // Name may have been automatically changed to resolve a conflict
            name = nsdServiceInfo.serviceName
            Timber.d("Service registered: $name")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Timber.e("Service registration failed: $errorCode")
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            Timber.d("Service unregistered: $name")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Timber.e("Service unregistration failed: $errorCode")
        }
    }

    fun register(context: Context) {
        try {
            ContextCompat.getSystemService(context, NsdManager::class.java)?.apply {
                registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            }
        } catch (e: Exception) {
            Timber.e(e, "Service registration failed")
        }
    }

    fun unregister(context: Context) {
        try {
            ContextCompat.getSystemService(context, NsdManager::class.java)?.apply {
                unregisterService(registrationListener)
            }
        } catch (e: Exception) {
            Timber.e(e, "Service unregistration failed")
        }
    }
}