package dev.logb.android.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

interface ConnectivityMonitor {
    val isOnline: StateFlow<Boolean>
    val isUnmetered: Boolean
}

/** Follows the default network; metered-ness decides whether originals download (phase 3). */
@Singleton
class Connectivity @Inject constructor(@ApplicationContext context: Context) : ConnectivityMonitor {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _isOnline = MutableStateFlow(current()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    override val isOnline: StateFlow<Boolean> = _isOnline

    override val isUnmetered: Boolean
        get() = current()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    init {
        manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _isOnline.value = true }
            override fun onLost(network: Network) { _isOnline.value = false }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _isOnline.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        })
    }

    private fun current(): NetworkCapabilities? = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
}
