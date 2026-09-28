package io.github.romanvht.byedpi.utility

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.gson.Gson
import io.github.romanvht.byedpi.strategy.ProfileBook
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.io.File

/** A network the user can be on. `key` identifies it, `label` is what we show. */
data class NetworkId(val key: String, val label: String)

enum class ApplyMode(val value: String) {
    Off("off"), Suggest("suggest"), Auto("auto");

    companion object {
        fun fromString(value: String) = entries.firstOrNull { it.value == value } ?: Off
    }
}

object NetworkProfileUtils {
    private const val TAG = "NetworkProfileUtils"
    private const val FILE = "network_profiles.json"
    private const val UNKNOWN_SSID = "<unknown ssid>"
    private val lock = Any()

    fun applyMode(context: Context): ApplyMode =
        ApplyMode.fromString(context.getPreferences().getStringNotNull("byedpi_network_apply", "suggest"))

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Name typed by the user wins; otherwise the physical network we are connected to. */
    fun currentNetwork(context: Context): NetworkId {
        val manual = context.getPreferences().getStringNotNull("byedpi_network_name", "").trim()
        if (manual.isNotEmpty()) return NetworkId("manual:$manual", manual)

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Our own VPN is the default network while running, so look at the real ones underneath.
        @Suppress("DEPRECATION")
        val transports = manager.allNetworks.mapNotNull { manager.getNetworkCapabilities(it) }
            .filter {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }

        return when {
            transports.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> wifi(context)
            transports.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> cellular(context)
            transports.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> NetworkId("ethernet", "Ethernet")
            else -> NetworkId("other", "Other")
        }
    }

    @Suppress("DEPRECATION")
    private fun wifi(context: Context): NetworkId {
        val ssid = if (hasLocationPermission(context)) {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            manager.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
        } else null
        return if (ssid != null) NetworkId("wifi:$ssid", ssid) else NetworkId("wifi", "Wi-Fi")
    }

    private fun cellular(context: Context): NetworkId {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val code = manager.networkOperator.orEmpty()
        val name = manager.networkOperatorName.orEmpty().ifEmpty { "Mobile" }
        return if (code.isNotEmpty()) NetworkId("cell:$code", name) else NetworkId("cell", name)
    }

    fun load(context: Context): ProfileBook = synchronized(lock) {
        try {
            AtomicFile(File(context.filesDir, FILE)).openRead().bufferedReader().use {
                Gson().fromJson(it, ProfileBook::class.java) ?: ProfileBook()
            }
        } catch (_: Exception) {
            ProfileBook()
        }
    }

    fun record(context: Context, network: NetworkId, command: String, score: Int) = synchronized(lock) {
        val book = load(context)
        book.record(network.key, network.label, command, score, System.currentTimeMillis())
        val file = AtomicFile(File(context.filesDir, FILE))
        val output = file.startWrite()
        try {
            output.write(Gson().toJson(book).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            Log.e(TAG, "Failed to save network profiles", e)
        }
    }

    /** What we know about the current network: its best command and whether that one is already in use. */
    data class NetworkBest(val network: NetworkId, val command: String?, val score: Int, val inUse: Boolean)

    fun bestFor(context: Context): NetworkBest {
        val network = currentNetwork(context)
        val best = load(context).profiles[network.key]?.best
        val inUse = best != null && best.key == context.getPreferences().getCmdArgs()
        return NetworkBest(network, best?.key, best?.value?.score ?: 0, inUse)
    }

    /** Best known command for the current network, if it differs from the one in use. */
    fun pendingBest(context: Context): Pair<NetworkId, String>? {
        val best = bestFor(context)
        return if (best.command != null && !best.inUse) best.network to best.command else null
    }

    fun apply(context: Context, command: String) {
        context.getPreferences().edit(commit = true) { putString("byedpi_cmd_args", command) }
    }

    /** Used before the service starts: in auto mode, switch to what worked best on this network. */
    fun applyIfAuto(context: Context) {
        if (applyMode(context) != ApplyMode.Auto) return
        pendingBest(context)?.let { (network, command) ->
            Log.i(TAG, "Applying best command for ${network.key}: $command")
            apply(context, command)
        }
    }

    /** Emits whenever a physical (non-VPN) network appears, changes or goes away. */
    fun networkChanges(context: Context): Flow<Unit> = callbackFlow {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        manager.registerNetworkCallback(request, callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.conflate()
}
