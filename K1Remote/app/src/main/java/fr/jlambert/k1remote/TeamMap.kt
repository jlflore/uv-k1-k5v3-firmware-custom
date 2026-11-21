package fr.jlambert.k1remote

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Carte d'équipe : partage des positions entre joueurs par Internet (4G/5G).
 *
 * Les positions transitent par un relais MQTT public, mais chiffrées
 * (AES-GCM) avec la clé de l'équipe : le relais ne voit qu'un identifiant
 * d'équipe illisible et des octets chiffrés. La clé se partage par QR code
 * ou par lien k1remote://carte?k=…&n=…
 */
object TeamMap {

    const val TOPIC_ROOT = "k1remote/v1/"
    /** Au-delà, un joueur est affiché en grisé (position ancienne). */
    const val STALE_MS = 2 * 60_000L
    /** Au-delà, un joueur n'est plus affiché du tout. */
    const val FORGET_MS = 60 * 60_000L

    private val rnd = SecureRandom()

    fun newKey(): ByteArray = ByteArray(16).also { rnd.nextBytes(it) }

    fun keyToText(k: ByteArray): String =
        Base64.encodeToString(k, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun keyFromText(s: String?): ByteArray? = runCatching {
        Base64.decode(s ?: return null, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }.getOrNull()?.takeIf { it.size == 16 }

    /** Identifiant d'équipe publié sur le relais : empreinte de la clé, sans rien révéler d'elle. */
    fun teamId(key: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest("k1remote-carte".toByteArray() + key)
        return h.take(8).joinToString("") { "%02x".format(it) }
    }

    fun topicFor(key: ByteArray, playerId: String) = TOPIC_ROOT + teamId(key) + "/" + playerId
    fun teamFilter(key: ByteArray) = TOPIC_ROOT + teamId(key) + "/+"

    fun link(key: ByteArray, team: String): String =
        "k1remote://carte?k=" + keyToText(key) + "&n=" + Uri.encode(team)

    /** Lit un lien d'équipe carte : (clé, nom de l'équipe) ou null. */
    fun fromLink(s: String?): Pair<ByteArray, String>? {
        val u = runCatching { Uri.parse(s?.trim() ?: return null) }.getOrNull() ?: return null
        if (u.scheme != "k1remote" || u.host != "carte") return null
        val k = keyFromText(u.getQueryParameter("k")) ?: return null
        return k to (u.getQueryParameter("n")?.takeIf { it.isNotBlank() } ?: "Équipe")
    }

    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(plain)
    }

    fun open(key: ByteArray, box: ByteArray): ByteArray? = runCatching {
        if (box.size < 12 + 16) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, box, 0, 12))
        c.doFinal(box, 12, box.size - 12)
    }.getOrNull()

    fun encodePosition(p: Player): ByteArray = JSONObject().apply {
        put("n", p.name); put("la", p.lat); put("lo", p.lon)
        put("a", p.accuracy.toDouble()); put("t", p.time)
        if (p.bearing != null) put("b", p.bearing.toDouble())
    }.toString().toByteArray()

    fun decodePosition(id: String, raw: ByteArray): Player? = runCatching {
        val j = JSONObject(String(raw))
        Player(id, j.optString("n", "?").take(20), j.getDouble("la"), j.getDouble("lo"),
            j.optDouble("a", 0.0).toFloat(), j.optLong("t", System.currentTimeMillis()),
            if (j.has("b")) j.getDouble("b").toFloat() else null)
    }.getOrNull()?.takeIf { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
}

data class Player(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    /** Moment de la mesure, horloge de l'émetteur (ms). */
    val time: Long,
    val bearing: Float? = null,
)

/** État partagé entre le service de partage et l'écran de carte. */
object TeamMapState {
    val players = ConcurrentHashMap<String, Player>()
    @Volatile var me: Player? = null
    @Volatile var running = false
    @Volatile var status = "Partage arrêté"
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    fun changed() { for (l in listeners) runCatching { l() } }

    fun clear() { players.clear(); me = null }
}
