package fr.jlambert.k1remote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Service de premier plan : partage la position du téléphone avec l'équipe
 * et reçoit celles des coéquipiers, même écran éteint ou appli en arrière-plan.
 * Une notification permanente indique que le partage est actif.
 */
class PositionService : Service() {

    companion object {
        private const val CHANNEL = "carte"
        private const val NOTIF_ID = 7
        const val ACTION_STOP = "fr.jlambert.k1remote.STOP_SHARE"
        /** Relais MQTT publics (TLS). Le second sert de secours. */
        private val BROKERS = listOf("broker.hivemq.com" to 8883, "broker.emqx.io" to 8883)

        fun start(ctx: Context) {
            val i = Intent(ctx, PositionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, PositionService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var key: ByteArray? = null
    @Volatile private var mqtt: Mqtt? = null
    @Volatile private var stopping = false
    private var net: Thread? = null
    private var lastSent = 0L
    private var lm: LocationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        if (TeamMapState.running) return START_STICKY
        key = TeamMap.keyFromText(prefs.mapKey)
        if (key == null) { stopSelf(); return START_NOT_STICKY }
        startForegroundCompat()
        TeamMapState.running = true
        stopping = false
        setStatus("Connexion au relais…")
        startLocation()
        net = Thread({ netLoop() }, "k1-carte").also { it.start() }
        return START_STICKY
    }

    // ---------------------------------------------------------------- notification

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Carte d'équipe", NotificationManager.IMPORTANCE_LOW))
        }
        val flagsPi = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MapActivity::class.java), flagsPi)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, PositionService::class.java).setAction(ACTION_STOP), flagsPi)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Partage de position actif")
            .setContentText("Équipe « ${prefs.mapTeam} » · toutes les ${prefs.shareInterval} s")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)
    }

    // ---------------------------------------------------------------- position

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onLocation(l)
        @Deprecated("Android < 10")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        val every = prefs.shareInterval * 1000L
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (m.isProviderEnabled(provider)) m.requestLocationUpdates(provider, every / 2, 0f, listener, Looper.getMainLooper())
            }
        }
        runCatching { m.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocation(it) } }
    }

    private fun onLocation(l: Location) {
        // Le GPS est prioritaire : on ignore une position réseau moins précise que la dernière, si elle est récente
        val prev = TeamMapState.me
        if (prev != null && l.provider == LocationManager.NETWORK_PROVIDER &&
            System.currentTimeMillis() - prev.time < 30_000 && l.accuracy > prev.accuracy) return
        val me = Player(prefs.playerId, prefs.playerName, l.latitude, l.longitude, l.accuracy,
            System.currentTimeMillis(), if (l.hasBearing() && l.hasSpeed() && l.speed > 0.7f) l.bearing else null)
        TeamMapState.me = me
        TeamMapState.changed()
        if (System.currentTimeMillis() - lastSent >= prefs.shareInterval * 1000L - 500) sendMine()
    }

    private fun sendMine() {
        val me = TeamMapState.me ?: return
        val k = key ?: return
        val c = mqtt ?: return
        if (!c.connected) return
        lastSent = System.currentTimeMillis()
        Thread({
            runCatching { c.publish(TeamMap.topicFor(k, me.id), TeamMap.seal(k, TeamMap.encodePosition(me)), retain = true) }
        }, "k1-carte-pub").start()
    }

    // ---------------------------------------------------------------- relais

    private fun netLoop() {
        val k = key ?: return
        var attempt = 0
        while (!stopping) {
            val (host, port) = BROKERS[attempt % BROKERS.size]
            val c = Mqtt(host, port, "k1r-" + prefs.playerId + "-" + (System.currentTimeMillis() % 100000)) { topic, payload ->
                onMessage(k, topic, payload)
            }
            try {
                c.connect()
                mqtt = c
                c.subscribe(TeamMap.teamFilter(k))
                attempt = 0
                setStatus("Partage actif")
                main.post { sendMine() }
                // Ping régulier pour garder la liaison
                val pinger = Thread({
                    try { while (c.connected) { Thread.sleep(30_000); if (c.connected) c.ping() } } catch (_: Exception) {}
                }, "k1-carte-ping").apply { isDaemon = true; start() }
                c.readLoop()
                pinger.interrupt()
            } catch (_: Exception) {
                c.close()
            }
            mqtt = null
            if (stopping) break
            attempt++
            setStatus("Pas de réseau ? Nouvel essai…")
            try { Thread.sleep(minOf(30_000L, 3_000L * attempt)) } catch (_: InterruptedException) { break }
        }
    }

    private fun onMessage(k: ByteArray, topic: String, payload: ByteArray) {
        val id = topic.substringAfterLast('/')
        if (id == prefs.playerId) return
        if (payload.isEmpty()) {                 // le joueur a arrêté son partage
            TeamMapState.players.remove(id)
            TeamMapState.changed()
            return
        }
        val plain = TeamMap.open(k, payload) ?: return     // pas la bonne clé : ignoré
        val p = TeamMap.decodePosition(id, plain) ?: return
        if (System.currentTimeMillis() - p.time > TeamMap.FORGET_MS) return
        TeamMapState.players[id] = p
        TeamMapState.changed()
    }

    private fun setStatus(s: String) {
        TeamMapState.status = s
        main.post { TeamMapState.changed() }
    }

    // ---------------------------------------------------------------- arrêt

    private fun shutdown() {
        stopping = true
        runCatching { lm?.removeUpdates(listener) }
        val k = key
        val c = mqtt
        Thread({
            // Efface la position laissée sur le relais : les autres ne voient plus ce joueur
            if (k != null && c != null && c.connected) runCatching {
                c.publish(TeamMap.topicFor(k, prefs.playerId), ByteArray(0), retain = true)
                Thread.sleep(300)
            }
            c?.close()
        }, "k1-carte-stop").start()
        net?.interrupt()
        TeamMapState.running = false
        TeamMapState.clear()
        TeamMapState.status = "Partage arrêté"
        main.post { TeamMapState.changed() }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        if (TeamMapState.running) shutdown()
        super.onDestroy()
    }
}
