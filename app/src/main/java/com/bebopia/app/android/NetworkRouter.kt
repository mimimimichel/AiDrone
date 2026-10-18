package com.bebopia.app.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.bebopia.app.core.bebop.SocketBinder
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * Fait passer le trafic du drone par le Wi-Fi du Bebop (qui n'a pas Internet)
 * et les appels à Claude par la 4G/5G, en même temps.
 */
class NetworkRouter(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile var wifi: Network? = null
        private set
    @Volatile var cellular: Network? = null
        private set

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wifi = network }
        override fun onLost(network: Network) { if (wifi == network) wifi = null }
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { cellular = network }
        override fun onLost(network: Network) { if (cellular == network) cellular = null }
    }

    fun start() {
        try {
            cm.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                wifiCallback,
            )
        } catch (e: Exception) { }
        try {
            cm.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cellCallback,
            )
        } catch (e: Exception) { }
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        runCatching { cm.unregisterNetworkCallback(cellCallback) }
    }

    /** Attache les sockets du drone au Wi-Fi. */
    val droneBinder = object : SocketBinder {
        override fun bind(socket: Socket) { wifi?.bindSocket(socket) }
        override fun bind(socket: DatagramSocket) { wifi?.bindSocket(socket) }
    }

    /** Ouvre une connexion HTTPS vers l'API par la 4G si elle est disponible. */
    fun openApi(url: URL): HttpURLConnection =
        (cellular?.openConnection(url) ?: url.openConnection()) as HttpURLConnection

    val describe: String
        get() = when {
            wifi != null && cellular != null -> "Wi-Fi drone + 4G"
            wifi != null -> "Wi-Fi seulement (pas de 4G)"
            cellular != null -> "4G seulement"
            else -> "aucun réseau"
        }
}
