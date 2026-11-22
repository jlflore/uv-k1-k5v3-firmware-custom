package fr.jlambert.k1remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Carte à tuiles (projection Web Mercator) dessinée à la main :
 * fond IGN (photos aériennes ou plan), déplacement au doigt, zoom à deux doigts
 * ou double-tape, positions des joueurs par-dessus. Les tuiles sont gardées
 * en mémoire et sur le téléphone pour resservir sans réseau.
 */
class TileMapView(ctx: Context) : View(ctx) {

    enum class Layer(val label: String, val maxZoom: Int, val ext: String, val urls: List<String>) {
        PHOTO("Photo", 19, "jpg", listOf(
            "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTHOIMAGERY.ORTHOPHOTOS&STYLE=normal&FORMAT=image/jpeg&TILEMATRIXSET=PM_0_19&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}",
            "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTHOIMAGERY.ORTHOPHOTOS&STYLE=normal&FORMAT=image/jpeg&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}")),
        PLAN("Plan", 18, "png", listOf(
            "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2&STYLE=normal&FORMAT=image/png&TILEMATRIXSET=PM&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}",
            "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2&STYLE=normal&FORMAT=image/png&TILEMATRIXSET=PM_0_19&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}")),
    }

    var layer = Layer.PHOTO
        set(v) { field = v; invalidate() }
    /** Mode nuit : carte assombrie et teintée de rouge. */
    var night = false
        set(v) { field = v; tilePaint.colorFilter = if (v) nightFilter else null; invalidate() }
    var onUserMove: (() -> Unit)? = null
    /**
     * Orientation de la carte, en degrés : direction (depuis le nord) placée en haut de l'écran.
     * 0 = nord en haut. La carte tourne autour du centre de l'écran ; noms et pastilles restent droits.
     */
    var mapBearing = 0f
        set(v) { field = ((v % 360f) + 360f) % 360f; invalidate() }
    /** Distance (et trait) de moi à chaque coéquipier. */
    var showDistances = false
        set(v) { field = v; invalidate() }

    // Centre en coordonnées Mercator normalisées (0..1) et zoom continu
    private var cx = 0.5
    private var cy = 0.5
    var zoom = 5.0
        private set

    private val density = resources.displayMetrics.density
    /** Taille affichée d'une tuile de 256 px (un peu agrandie sur les écrans denses). */
    private val tileDp = 256f * maxOf(1f, density / 2f)

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(k: String, b: Bitmap) = b.byteCount
    }
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(4)
    /** Pour chaque fond, l'adresse de tuile qui fonctionne (la première qui répond). */
    private val urlIndex = IntArray(Layer.values().size)
    private val diskDir = File(ctx.cacheDir, "tuiles")

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val nightFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        0.45f, 0.35f, 0.1f, 0f, 0f,
        0.06f, 0.05f, 0.02f, 0f, 0f,
        0.04f, 0.03f, 0.01f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f)))
    private val bgPaint = Paint().apply { color = Color.rgb(222, 226, 220) }
    private val gridPaint = Paint().apply { color = Color.argb(40, 0, 0, 0); strokeWidth = 1f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density; typeface = Typeface.DEFAULT_BOLD
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)

    // ------------------------------------------------------------------ projection

    fun center(lat: Double, lon: Double, z: Double? = null) {
        cx = lonToX(lon); cy = latToY(lat)
        if (z != null) zoom = z.coerceIn(3.0, 20.0)
        invalidate()
    }

    private fun lonToX(lon: Double) = (lon + 180.0) / 360.0
    private fun latToY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }
    private fun yToLat(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))
    private fun xToLon(x: Double) = x * 360.0 - 180.0

    /** Taille du monde entier en pixels écran au zoom courant. */
    private fun worldPx() = tileDp * 2.0.pow(zoom)

    private fun screenX(x: Double) = ((x - cx) * worldPx() + width / 2.0).toFloat()
    private fun screenY(y: Double) = ((y - cy) * worldPx() + height / 2.0).toFloat()

    /** Fait tourner un point de l'écran autour de son centre de [deg] degrés (sens horaire). */
    private fun turn(x: Float, y: Float, deg: Float): Pair<Float, Float> {
        if (deg == 0f) return x to y
        val a = Math.toRadians(deg.toDouble())
        val dx = x - width / 2f; val dy = y - height / 2f
        return (width / 2f + (dx * cos(a) - dy * sin(a)).toFloat()) to (height / 2f + (dx * sin(a) + dy * cos(a)).toFloat())
    }

    /** Position écran d'un point de la carte, rotation comprise. */
    private fun project(lat: Double, lon: Double): Pair<Float, Float> =
        turn(screenX(lonToX(lon)), screenY(latToY(lat)), -mapBearing)

    /** Mètres par pixel écran au centre (pour le cercle de précision et l'échelle). */
    private fun metersPerPx(): Double = 40_075_016.686 * cos(Math.toRadians(yToLat(cy))) / worldPx()

    // ------------------------------------------------------------------ gestes

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAround(ln(d.scaleFactor.toDouble()) / ln(2.0), d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx0: Float, dy0: Float): Boolean {
            // Carte tournée : le glissement du doigt est ramené dans le repère de la carte
            val a = Math.toRadians(mapBearing.toDouble())
            val dx = (dx0 * cos(a) - dy0 * sin(a)).toFloat()
            val dy = (dx0 * sin(a) + dy0 * cos(a)).toFloat()
            cx = (cx + dx / worldPx()).let { it - floor(it) }
            cy = (cy + dy / worldPx()).coerceIn(0.0, 1.0)
            onUserMove?.invoke()
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoomAround(1.0, e.x, e.y); return true }
    })

    private fun zoomAround(dz: Double, fx0: Float, fy0: Float) {
        val (fx, fy) = turn(fx0, fy0, mapBearing)     // point sous les doigts, dans le repère de la carte
        val before = worldPx()
        val nz = (zoom + dz).coerceIn(3.0, 20.0)
        if (nz == zoom) return
        // Garde fixe le point sous les doigts
        val wx = cx + (fx - width / 2.0) / before
        val wy = cy + (fy - height / 2.0) / before
        zoom = nz
        val after = worldPx()
        cx = wx - (fx - width / 2.0) / after
        cy = (wy - (fy - height / 2.0) / after).coerceIn(0.0, 1.0)
        onUserMove?.invoke()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    // ------------------------------------------------------------------ dessin

    override fun onDraw(c: Canvas) {
        c.drawPaint(bgPaint)
        val l = layer
        val z = minOf(floor(zoom).toInt(), l.maxZoom)
        val n = 1 shl z
        val tile = (worldPx() / n).toFloat()        // taille écran d'une tuile du niveau z
        val left = screenX(0.0)
        val top = screenY(0.0)
        // Carte tournée : on couvre le cercle circonscrit à l'écran
        val r = if (mapBearing == 0f) 0f else (kotlin.math.hypot(width.toFloat(), height.toFloat()) / 2f)
        val ex = if (r == 0f) 0f else r - width / 2f
        val ey = if (r == 0f) 0f else r - height / 2f
        val x0 = floor((-ex - left) / tile).toInt()
        val x1 = floor((width + ex - left) / tile).toInt()
        val y0 = maxOf(0, floor((-ey - top) / tile).toInt())
        val y1 = minOf(n - 1, floor((height + ey - top) / tile).toInt())
        c.save()
        c.rotate(-mapBearing, width / 2f, height / 2f)
        val dst = RectF()
        for (ty in y0..y1) for (tx in x0..x1) {
            val wx = ((tx % n) + n) % n
            dst.set(left + tx * tile, top + ty * tile, left + (tx + 1) * tile, top + (ty + 1) * tile)
            val bmp = tileBitmap(l, z, wx, ty)
            if (bmp != null) c.drawBitmap(bmp, null, dst, tilePaint)
            else {
                // En attendant : la tuile du niveau au-dessus, agrandie, sinon une grille
                val parent = if (z > 0) cache.get(key(l, z - 1, wx / 2, ty / 2)) else null
                if (parent != null) {
                    val half = parent.width / 2
                    val src = android.graphics.Rect((wx % 2) * half, (ty % 2) * half, (wx % 2) * half + half, (ty % 2) * half + half)
                    c.drawBitmap(parent, src, dst, tilePaint)
                } else {
                    c.drawLine(dst.left, dst.top, dst.right, dst.top, gridPaint)
                    c.drawLine(dst.left, dst.top, dst.left, dst.bottom, gridPaint)
                }
            }
        }
        c.restore()
        drawPlayers(c)
        drawScale(c)
    }

    private fun drawPlayers(c: Canvas) {
        val now = System.currentTimeMillis()
        val me = TeamMapState.me
        for (p in TeamMapState.players.values) {
            val age = now - p.time
            if (age > TeamMap.FORGET_MS) continue
            var dist: String? = null
            if (showDistances && me != null) {
                // Trait pointillé de moi vers le coéquipier, et distance dans son étiquette
                val m = distanceM(me.lat, me.lon, p.lat, p.lon)
                dist = if (m >= 1000) String.format(java.util.Locale.FRANCE, "%.1f km", m / 1000) else "${(Math.round(m / 5.0) * 5).toInt()} m"
                val (x1, y1) = project(me.lat, me.lon)
                val (x2, y2) = project(p.lat, p.lon)
                stroke.color = if (night) Color.argb(200, 255, 90, 70) else Color.argb(210, 255, 255, 255)
                stroke.strokeWidth = 2.5f * density
                stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
                c.drawLine(x1, y1, x2, y2, stroke)
                stroke.pathEffect = null
            }
            drawMarker(c, p, colorFor(p.id), stale = age > TeamMap.STALE_MS, ageMs = age, isMe = false, dist = dist)
        }
        TeamMapState.me?.let { drawMarker(c, it, Color.rgb(25, 118, 210), stale = false, ageMs = 0, isMe = true) }
    }

    private fun drawMarker(c: Canvas, p: Player, color: Int, stale: Boolean, ageMs: Long, isMe: Boolean, dist: String? = null) {
        val (x, y) = project(p.lat, p.lon)
        val col = if (stale) Color.rgb(140, 140, 140) else if (night) Color.rgb(230, 60, 40) else color
        // Cercle de précision
        val accPx = (p.accuracy / metersPerPx()).toFloat()
        if (accPx > 14 * density && accPx < width * 2f) {
            fill.color = Color.argb(40, Color.red(col), Color.green(col), Color.blue(col))
            c.drawCircle(x, y, accPx, fill)
        }
        // Flèche de direction
        p.bearing?.let { b ->
            val r = 17 * density
            val a = Math.toRadians((b - mapBearing).toDouble())
            val path = Path().apply {
                moveTo(x + (r * 1.5f * sin(a)).toFloat(), y - (r * 1.5f * cos(a)).toFloat())
                lineTo(x + (r * sin(a + 0.5)).toFloat(), y - (r * cos(a + 0.5)).toFloat())
                lineTo(x + (r * sin(a - 0.5)).toFloat(), y - (r * cos(a - 0.5)).toFloat())
                close()
            }
            fill.color = col
            c.drawPath(path, fill)
        }
        val r = (if (isMe) 9f else 11f) * density
        fill.color = Color.WHITE
        c.drawCircle(x, y, r + 2.5f * density, fill)
        fill.color = col
        c.drawCircle(x, y, r, fill)
        if (!isMe) {
            // Initiale dans la pastille (les autres joueurs)
            label.color = Color.WHITE
            label.textAlign = Paint.Align.CENTER
            c.drawText(p.name.take(1).uppercase(), x, y + label.textSize * 0.36f, label)
        }
        run {
            // Nom (et âge de la position pour les autres) ; pour soi : son nom, ou « Moi » s'il n'est pas réglé
            val txt = if (isMe) (if (p.name.isBlank() || p.name == "Joueur") "Moi" else p.name)
                      else p.name + (dist?.let { "  ·  $it" } ?: "") + (if (ageMs > 30_000) "  " + ageText(ageMs) else "")
            label.textAlign = Paint.Align.LEFT
            val w = label.measureText(txt)
            val lx = x + r + 6 * density
            val ly = y - r - 2 * density
            labelBg.color = if (night) Color.argb(200, 30, 0, 0) else Color.argb(215, 255, 255, 255)
            c.drawRoundRect(RectF(lx - 5 * density, ly - label.textSize, lx + w + 5 * density, ly + 5 * density),
                6 * density, 6 * density, labelBg)
            label.color = if (night) Color.rgb(255, 90, 70) else if (stale) Color.DKGRAY
                          else if (isMe) Color.rgb(25, 118, 210) else Color.rgb(20, 30, 40)
            c.drawText(txt, lx, ly, label)
        }
    }

    private fun drawScale(c: Canvas) {
        val mpp = metersPerPx()
        val target = 90 * density * mpp
        val steps = doubleArrayOf(5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0)
        val m = steps.lastOrNull { it <= target } ?: steps[0]
        val w = (m / mpp).toFloat()
        val x = 14 * density
        val y = height - 40 * density
        stroke.color = if (night) Color.rgb(255, 90, 70) else Color.BLACK
        stroke.strokeWidth = 3 * density
        c.drawLine(x, y, x + w, y, stroke)
        c.drawLine(x, y - 6 * density, x, y, stroke)
        c.drawLine(x + w, y - 6 * density, x + w, y, stroke)
        label.textAlign = Paint.Align.LEFT
        label.color = stroke.color
        c.drawText(if (m >= 1000) "${(m / 1000).toInt()} km" else "${m.toInt()} m", x, y - 9 * density, label)
    }

    /** Distance au sol (formule de haversine), en mètres. */
    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
    }

    private fun ageText(ms: Long): String {
        val s = ms / 1000
        return if (s < 90) "${s} s" else if (s < 5400) "${s / 60} min" else "${s / 3600} h"
    }

    /** Couleur stable par joueur. */
    private fun colorFor(id: String): Int {
        val palette = intArrayOf(Color.rgb(211, 47, 47), Color.rgb(56, 142, 60), Color.rgb(245, 124, 0),
            Color.rgb(123, 31, 162), Color.rgb(0, 151, 167), Color.rgb(194, 24, 91), Color.rgb(93, 64, 55), Color.rgb(85, 139, 47))
        return palette[(id.hashCode() and 0x7fffffff) % palette.size]
    }

    // ------------------------------------------------------------------ tuiles

    private fun key(l: Layer, z: Int, x: Int, y: Int) = "${l.name}/$z/$x/$y"

    private fun tileBitmap(l: Layer, z: Int, x: Int, y: Int): Bitmap? {
        val k = key(l, z, x, y)
        cache.get(k)?.let { return it }
        val f = failed[k]
        if (f != null && System.currentTimeMillis() - f < 20_000) return null
        if (loading.add(k)) pool.execute { load(l, z, x, y, k) }
        return null
    }

    private fun load(l: Layer, z: Int, x: Int, y: Int, k: String) {
        try {
            val file = File(diskDir, "$k.${l.ext}")
            var bmp: Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            if (bmp == null) {
                val bytes = download(l, z, x, y)
                if (bytes != null) {
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) runCatching { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
                }
            }
            if (bmp != null) { cache.put(k, bmp); failed.remove(k); postInvalidate() }
            else failed[k] = System.currentTimeMillis()
        } catch (_: Exception) {
            failed[k] = System.currentTimeMillis()
        } finally {
            loading.remove(k)
        }
    }

    private fun download(l: Layer, z: Int, x: Int, y: Int): ByteArray? {
        val start = urlIndex[l.ordinal]
        for (i in l.urls.indices) {
            val idx = (start + i) % l.urls.size
            val url = l.urls[idx].replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("User-Agent", "K1Remote/0.10 (Android; carte d'equipe airsoft)")
            }
            try {
                if (conn.responseCode == 200 && conn.contentType?.startsWith("image") == true) {
                    urlIndex[l.ordinal] = idx
                    return conn.inputStream.use { it.readBytes() }
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    fun release() = pool.shutdownNow()
}
