package dev.suryansh.airspeaker.core.mdns

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.suryansh.airspeaker.core.Logs
import dev.suryansh.airspeaker.core.ReceiverIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Publishes the `_raop._tcp` service through Android's NsdManager. One registration at a time. */
class RaopAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var current: Registration? = null

    /** Replaces any existing registration. Throws [RegistrationFailedException] if the system refuses it. */
    suspend fun advertise(identity: ReceiverIdentity, port: Int, network: Network?) {
        withdraw()
        val info = NsdServiceInfo().apply {
            serviceName = RaopService.serviceName(identity)
            serviceType = RaopService.TYPE
            this.port = port
            RaopService.txtRecords.forEach { (key, value) -> setAttribute(key, value) }
            if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) this.network = network
        }
        val registration = Registration()
        current = registration
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        val registered = registration.registered.await()
        Logs.mdns.i("registered ${RaopService.TYPE} '${registered.serviceName}' port $port")
    }

    /** Removes the registration and waits briefly for the goodbye packets to go out. */
    suspend fun withdraw() {
        val registration = current ?: return
        current = null
        if (unregister(registration)) {
            withTimeoutOrNull(3_000) { registration.unregistered.await() }
        }
    }

    /** Non-suspending variant for service teardown. */
    fun close() {
        current?.let(::unregister)
        current = null
    }

    private fun unregister(registration: Registration): Boolean = try {
        nsd.unregisterService(registration)
        true
    } catch (e: IllegalArgumentException) {
        false // never registered (registration failed)
    }

    private class Registration : NsdManager.RegistrationListener {
        val registered = CompletableDeferred<NsdServiceInfo>()
        val unregistered = CompletableDeferred<Unit>()

        override fun onServiceRegistered(info: NsdServiceInfo) {
            registered.complete(info)
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            registered.completeExceptionally(RegistrationFailedException(errorCode))
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Logs.mdns.i("unregistered '${info.serviceName}'")
            unregistered.complete(Unit)
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Logs.mdns.w("NSD unregistration failed, error $errorCode")
            unregistered.complete(Unit)
        }
    }
}

class RegistrationFailedException(val errorCode: Int) : Exception("NSD registration failed, error $errorCode")
