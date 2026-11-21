package fr.jlambert.k1remote

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Client MQTT 3.1.1 minimal, chiffré TLS (port 8883), sans dépendance.
 * Juste ce qu'il faut pour la carte d'équipe : CONNECT, SUBSCRIBE,
 * PUBLISH (QoS 0, avec ou sans « retain ») et PING.
 */
class Mqtt(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
) {
    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    @Volatile var connected = false
        private set
    private var packetId = 1

    /** Connexion bloquante (à appeler hors fil principal). Lève une exception en cas d'échec. */
    fun connect(keepAliveSec: Int = 60) {
        val s = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), 10_000)
        s.soTimeout = 15_000
        s.startHandshake()
        socket = s
        out = s.outputStream
        val vh = ByteArrayOutputStream().apply {
            writeStr(this, "MQTT")
            write(4)                 // version 3.1.1
            write(0x02)              // session propre
            write(keepAliveSec shr 8); write(keepAliveSec and 0xFF)
            writeStr(this, clientId)
        }.toByteArray()
        send(0x10, vh)
        val inp = DataInputStream(s.inputStream)
        val type = inp.readUnsignedByte()
        val len = readLen(inp)
        val body = ByteArray(len).also { inp.readFully(it) }
        if (type != 0x20 || len < 2 || body[1].toInt() != 0) throw IllegalStateException("Relais : connexion refusée")
        s.soTimeout = 0
        connected = true
    }

    /** Boucle de lecture bloquante : rend la main quand la connexion tombe. */
    fun readLoop() {
        val inp = DataInputStream(socket?.inputStream ?: return)
        try {
            while (true) {
                val h = inp.readUnsignedByte()
                val len = readLen(inp)
                val body = ByteArray(len).also { inp.readFully(it) }
                if (h shr 4 == 3) {          // PUBLISH
                    val tl = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                    val topic = String(body, 2, tl)
                    var off = 2 + tl
                    if ((h shr 1) and 3 > 0) off += 2   // identifiant de paquet (QoS > 0)
                    onMessage(topic, body.copyOfRange(off, body.size))
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    fun subscribe(filter: String) {
        val b = ByteArrayOutputStream().apply {
            val id = packetId++
            write(id shr 8); write(id and 0xFF)
            writeStr(this, filter)
            write(0)                 // QoS 0
        }.toByteArray()
        send(0x82, b)
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        val b = ByteArrayOutputStream().apply {
            writeStr(this, topic)
            write(payload)
        }.toByteArray()
        send(0x30 or (if (retain) 1 else 0), b)
    }

    fun ping() = send(0xC0, ByteArray(0))

    fun close() {
        connected = false
        runCatching { if (socket?.isClosed == false) send(0xE0, ByteArray(0)) }
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    private fun send(header: Int, body: ByteArray) {
        val o = out ?: throw IllegalStateException("Relais non connecté")
        val pkt = ByteArrayOutputStream().apply {
            write(header)
            var n = body.size
            do {
                var d = n % 128
                n /= 128
                if (n > 0) d = d or 0x80
                write(d)
            } while (n > 0)
            write(body)
        }.toByteArray()
        synchronized(writeLock) { o.write(pkt); o.flush() }
    }

    private fun writeStr(o: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray()
        o.write(b.size shr 8); o.write(b.size and 0xFF); o.write(b)
    }

    private fun readLen(inp: DataInputStream): Int {
        var mult = 1
        var v = 0
        repeat(4) {
            val d = inp.readUnsignedByte()
            v += (d and 0x7F) * mult
            if (d and 0x80 == 0) return v
            mult *= 128
        }
        throw IllegalStateException("Paquet MQTT invalide")
    }
}
