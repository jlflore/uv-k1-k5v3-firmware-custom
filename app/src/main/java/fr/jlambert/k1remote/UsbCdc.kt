package fr.jlambert.k1remote

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Liaison série minimale USB CDC-ACM, sans bibliothèque externe.
 * La radio n'émet que si DTR est actif (cdc_acm_data_send_with_dtr côté firmware),
 * d'où l'envoi de SET_CONTROL_LINE_STATE à l'ouverture.
 */
class UsbCdc(private val manager: UsbManager, val device: UsbDevice) : RadioLink {

    override val label = "USB"

    private var connection: UsbDeviceConnection? = null
    private var commIf: UsbInterface? = null
    private var dataIf: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    @Volatile private var running = false
    private var reader: Thread? = null
    private val writeLock = Any()

    fun open(onData: (ByteArray, Int) -> Unit, onError: (String) -> Unit): Boolean {
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            when (itf.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> if (commIf == null) commIf = itf
                UsbConstants.USB_CLASS_CDC_DATA -> if (dataIf == null) dataIf = itf
            }
        }
        val data = dataIf ?: run { onError("Interface données CDC introuvable"); return false }
        for (i in 0 until data.endpointCount) {
            val ep = data.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
        }
        if (epIn == null || epOut == null) { onError("Endpoints bulk introuvables"); return false }

        val conn = manager.openDevice(device) ?: run { onError("Ouverture USB refusée"); return false }
        commIf?.let { conn.claimInterface(it, true) }
        if (!conn.claimInterface(data, true)) {
            conn.close(); onError("Impossible de réserver l'interface USB"); return false
        }
        connection = conn

        val ctrlIndex = commIf?.id ?: 0
        // SET_LINE_CODING : 38400 bauds, 8N1 (ignoré par un vrai CDC, mais inoffensif)
        val baud = 38400
        val lineCoding = byteArrayOf(
            (baud and 0xFF).toByte(), (baud shr 8 and 0xFF).toByte(),
            (baud shr 16 and 0xFF).toByte(), (baud shr 24 and 0xFF).toByte(),
            0, 0, 8
        )
        conn.controlTransfer(0x21, 0x20, 0, ctrlIndex, lineCoding, lineCoding.size, 500)
        // SET_CONTROL_LINE_STATE : DTR + RTS
        conn.controlTransfer(0x21, 0x22, 0x03, ctrlIndex, null, 0, 500)

        running = true
        reader = Thread({
            val buf = ByteArray(epIn!!.maxPacketSize.coerceAtLeast(64) * 8)
            while (running) {
                val n = conn.bulkTransfer(epIn, buf, buf.size, 200)
                if (n > 0) onData(buf, n)
            }
        }, "k1-usb-reader").apply { isDaemon = true; start() }
        return true
    }

    override fun write(bytes: ByteArray): Boolean {
        val conn = connection ?: return false
        synchronized(writeLock) {
            return conn.bulkTransfer(epOut, bytes, bytes.size, 300) == bytes.size
        }
    }

    override fun close() {
        running = false
        reader?.join(500)
        reader = null
        connection?.let { c ->
            runCatching { c.controlTransfer(0x21, 0x22, 0x00, commIf?.id ?: 0, null, 0, 200) }
            commIf?.let { c.releaseInterface(it) }
            dataIf?.let { c.releaseInterface(it) }
            c.close()
        }
        connection = null
    }
}
