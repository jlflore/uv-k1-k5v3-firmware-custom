package fr.jlambert.k1remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Liaison avec la radio : câble USB ou boîtier Bluetooth « K1 Relais ». */
interface RadioLink {
    /** Libellé court affiché dans la barre d'état. */
    val label: String
    fun write(bytes: ByteArray): Boolean
    fun close()
}

/**
 * Liaison Bluetooth LE avec le boîtier « K1 Relais » (ESP32-S3).
 * Le boîtier relaie tel quel le flux USB de la radio (protocole K5Viewer)
 * sur un service de type « Nordic UART ».
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val ctx: Context,
    private val address: String,
    private val onData: (ByteArray, Int) -> Unit,
    /** Appelé (fil principal) : true = radio branchée sur le boîtier. */
    private val onRadio: (Boolean) -> Unit,
    /** Appelé (fil principal) une fois la liaison prête. */
    private val onReady: () -> Unit,
    /** Appelé (fil principal) quand la liaison est perdue ou impossible. */
    private val onLost: (String) -> Unit,
) : RadioLink {

    companion object {
        val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")   // téléphone -> radio
        val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")   // radio -> téléphone
        val STATE: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
        val CTL: UUID = UUID.fromString("6e400005-b5a3-f393-e0a9-e50e24dcca9e")   // commandes du boîtier
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val NAME = "K1 Relais"

        /** Supprime l'appairage Android avec le boîtier (méthode cachée d'Android). */
        fun forget(ctx: Context, address: String) {
            runCatching {
                val dev = adapter(ctx)?.getRemoteDevice(address) ?: return
                dev.javaClass.getMethod("removeBond").invoke(dev)
            }
        }

        fun adapter(ctx: Context): BluetoothAdapter? =
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        /**
         * Cherche un boîtier pendant [timeoutMs] et rend son adresse (ou null).
         * [done] est appelé sur le fil principal.
         */
        fun scan(ctx: Context, timeoutMs: Long, done: (String?) -> Unit) {
            val scanner = adapter(ctx)?.bluetoothLeScanner ?: run { done(null); return }
            val main = Handler(Looper.getMainLooper())
            var finished = false
            lateinit var cb: ScanCallback
            val finish = { addr: String? ->
                if (!finished) {
                    finished = true
                    runCatching { scanner.stopScan(cb) }
                    main.post { done(addr) }
                }
            }
            cb = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    val name = r.scanRecord?.deviceName ?: r.device.name
                    val hasSvc = r.scanRecord?.serviceUuids?.any { it.uuid == SERVICE } == true
                    if (hasSvc || name == NAME) finish(r.device.address)
                }
                override fun onScanFailed(errorCode: Int) { finish(null) }
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            // Pas de filtre par service : le nom peut arriver sans l'UUID (réponse de scan)
            runCatching { scanner.startScan(emptyList<ScanFilter>(), settings, cb) }
                .onFailure { finish(null); return }
            main.postDelayed({ finish(null) }, timeoutMs)
        }
    }

    override val label = "Bluetooth"

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var ctl: BluetoothGattCharacteristic? = null
    /** Le boîtier a confirmé la liaison chiffrée (premier état reçu). */
    @Volatile var secured = false
        private set
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var payload = 20
    private val writeLock = Any()
    private val writeDone = Semaphore(0)
    private val descDone = Semaphore(0)

    fun open() {
        val dev: BluetoothDevice = adapter(ctx)?.getRemoteDevice(address) ?: run {
            onLost("Bluetooth indisponible"); return
        }
        gatt = if (Build.VERSION.SDK_INT >= 23)
            dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        else dev.connectGatt(ctx, false, callback)
        // Sans réponse au bout de 12 s : on abandonne
        main.postDelayed({ if (!ready && !closed) fail("Boîtier introuvable") }, 12_000)
    }

    private fun fail(msg: String) {
        if (closed) return
        close()
        main.post { onLost(msg) }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail(if (ready) "Boîtier Bluetooth perdu" else "Connexion au boîtier impossible")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) payload = (mtu - 3).coerceIn(20, 244)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE) ?: run { fail("Ce n'est pas un boîtier K1 Relais"); return }
            rx = svc.getCharacteristic(RX)
            ctl = svc.getCharacteristic(CTL)
            val tx = svc.getCharacteristic(TX)
            val st = svc.getCharacteristic(STATE)
            if (rx == null || tx == null) { fail("Boîtier K1 Relais incomplet"); return }
            // Les opérations GATT doivent se suivre : on les enchaîne hors du fil Bluetooth
            Thread({
                enableNotify(g, tx)
                if (st != null) {
                    enableNotify(g, st)
                    g.readCharacteristic(st)
                }
                ready = true
                main.post { onReady() }
            }, "k1-ble-setup").start()
        }

        private fun handle(c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                TX -> if (value.isNotEmpty()) onData(value, value.size)
                STATE -> if (value.isNotEmpty()) {
                    secured = true
                    val radio = value[0].toInt() and 1 == 1
                    main.post { onRadio(radio) }
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            handle(c, value)

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handle(c, c.value ?: return)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            handle(c, value)

        @Deprecated("Android < 13")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) handle(c, c.value ?: return)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.release()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descDone.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        descDone.drainPermits()
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == 0
                 else { d.value = v; g.writeDescriptor(d) }
        if (ok) descDone.tryAcquire(2, TimeUnit.SECONDS)
    }

    /** Envoie vers la radio, découpé à la taille d'un paquet Bluetooth. Bloquant, à appeler hors fil principal. */
    @Suppress("DEPRECATION")
    override fun write(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = rx ?: return false
        if (!ready) return false
        synchronized(writeLock) {
            var off = 0
            while (off < bytes.size) {
                val n = minOf(payload, bytes.size - off)
                val chunk = bytes.copyOfRange(off, off + n)
                writeDone.drainPermits()
                var sent = false
                for (attempt in 0 until 5) {
                    sent = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == 0
                    } else {
                        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        c.value = chunk
                        g.writeCharacteristic(c)
                    }
                    if (sent) break
                    Thread.sleep(15)   // pile Bluetooth occupée : on réessaie
                }
                if (!sent) return false
                // Pas d'accusé de la pile Bluetooth en 300 ms : l'écriture est considérée comme ratée
                if (!writeDone.tryAcquire(300, TimeUnit.MILLISECONDS)) return false
                off += n
            }
        }
        return true
    }

    /** Intensité de la lumière du boîtier : 0 éteinte, 1 faible, 2 normale. */
    fun setLed(level: Int) = control(byteArrayOf(0x01, level.coerceIn(0, 2).toByte()))

    /** Veille du boîtier après [minutes] sans radio (0 = jamais). Ignoré par les boîtiers 1.0/1.1. */
    fun setSleep(minutes: Int) = control(byteArrayOf(0x02, minutes.coerceIn(0, 240).toByte()))

    @Suppress("DEPRECATION")
    private fun control(v: ByteArray) {
        val g = gatt ?: return
        val c = ctl ?: return
        Thread({
            synchronized(writeLock) {
                writeDone.drainPermits()
                val ok = if (Build.VERSION.SDK_INT >= 33)
                    g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
                else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = v; g.writeCharacteristic(c) }
                if (ok) writeDone.tryAcquire(1, TimeUnit.SECONDS)
            }
        }, "k1-ble-led").start()
    }

    override fun close() {
        closed = true
        ready = false
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
    }
}
