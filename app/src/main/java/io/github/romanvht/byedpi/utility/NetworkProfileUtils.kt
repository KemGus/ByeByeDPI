package io.github.romanvht.byedpi.utility

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.gson.Gson
import io.github.romanvht.byedpi.strategy.ProfileBook
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
        ApplyMode.fromString(context.getPreferences().getStringNotNull("byedpi_network_apply", "off"))

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

    /** Best known command for the current network, if it differs from the one in use. */
    fun pendingBest(context: Context): Pair<NetworkId, String>? {
        val network = currentNetwork(context)
        val best = load(context).best(network.key) ?: return null
        return if (best != context.getPreferences().getCmdArgs()) network to best else null
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
}
