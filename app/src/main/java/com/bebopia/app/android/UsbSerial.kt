package com.bebopia.app.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.bebopia.app.core.roomba.SerialLink
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Carte USB-série (FTDI FT232, CH340, CP2102…) branchée sur la prise USB-C du téléphone, vers la prise du Roomba.
 * La ligne de réveil est envoyée à la fois sur RTS et DTR : peu importe laquelle est câblée sur la broche 5.
 */
class UsbSerial private constructor(private val port: UsbSerialPort) : SerialLink {
    private val buf = ByteArray(256)
    private val pending = java.io.ByteArrayOutputStream()

    override fun write(b: ByteArray) = port.write(b, 500)

    override fun read(n: Int, timeoutMs: Int): ByteArray? {
        val end = System.currentTimeMillis() + timeoutMs
        while (pending.size() < n) {
            val left = (end - System.currentTimeMillis()).toInt()
            if (left <= 0) return null
            val k = port.read(buf, left.coerceAtLeast(20))
            if (k > 0) pending.write(buf, 0, k)
        }
        val all = pending.toByteArray()
        pending.reset()
        if (all.size > n) pending.write(all, n, all.size - n)
        return all.copyOf(n)
    }

    override fun clearInput() {
        pending.reset()
        runCatching { while (port.read(buf, 5) > 0) Unit }
    }

    override fun setWakeLine(low: Boolean) {
        // RTS# et DTR# sont actifs à l'état bas : « vrai » tire la broche vers 0 V
        runCatching { port.setRTS(low) }
        runCatching { port.setDTR(low) }
    }

    override fun setBaud(baud: Int) = port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

    override fun close() { runCatching { port.close() } }

    companion object {
        private const val ACTION = "com.bebopia.app.USB_PERMISSION"

        /**
         * Ouvre la première carte USB-série branchée (demande l'autorisation si besoin). Bloquant.
         * Lève une exception au message clair si rien n'est branché ou si l'accès est refusé.
         */
        fun open(context: Context): UsbSerial {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            val driver = drivers.firstOrNull() ?: throw IllegalStateException(
                if (manager.deviceList.isEmpty()) "aucune carte USB détectée : vérifie le câble USB-C (il doit transmettre les données, pas seulement charger)"
                else "un appareil USB est branché mais ce n'est pas une carte USB-série reconnue (FTDI, CH340, CP2102)")
            val device = driver.device
            if (!manager.hasPermission(device)) {
                val latch = CountDownLatch(1)
                var granted = false
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        latch.countDown()
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
                try {
                    val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION).setPackage(context.packageName), flags)
                    manager.requestPermission(device, pi)
                    latch.await(60, TimeUnit.SECONDS)
                } finally {
                    runCatching { context.unregisterReceiver(receiver) }
                }
                if (!granted && !manager.hasPermission(device)) throw IllegalStateException("accès à la carte USB refusé (accepte la demande d'Android)")
            }
            val conn = manager.openDevice(device) ?: throw IllegalStateException("impossible d'ouvrir la carte USB")
            val port = driver.ports[0]
            port.open(conn)
            port.setParameters(115_200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            return UsbSerial(port)
        }
    }
}
