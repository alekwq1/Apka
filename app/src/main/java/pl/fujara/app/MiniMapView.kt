package pl.fujara.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Pamiec podreczna kafelkow OpenStreetMap (tylko gdy modul Trasa jest wlaczony). */
object TileCache {
    private val cache = LruCache<String, Bitmap>(120)
    private val failed = ConcurrentHashMap<String, Long>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "fujara-tiles").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun get(z: Int, x: Int, y: Int, onLoaded: () -> Unit): Bitmap? {
        val key = "$z/$x/$y"
        cache.get(key)?.let { return it }
        failed[key]?.let { if (System.currentTimeMillis() - it < 60_000L) return null }
        if (!loading.add(key)) return null
        executor.execute {
            val bitmap = runCatching {
                val connection = (URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 7_000
                    setRequestProperty("User-Agent", RouteAdvisor.USER_AGENT)
                }
                try {
                    if (connection.responseCode in 200..299) connection.inputStream.use { BitmapFactory.decodeStream(it) } else null
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            loading.remove(key)
            if (bitmap != null) {
                cache.put(key, bitmap)
                main.post(onLoaded)
            } else {
                failed[key] = System.currentTimeMillis()
            }
        }
        return null
    }
}

/**
 * Mini-mapa w nakladce: moja pozycja, punkty biezacych zlecen i nowej oferty.
 * Podklad: kafelki OSM (opcjonalnie), inaczej schematyczna siatka.
 */
class MiniMapView(context: Context) : View(context) {
    private var points: List<MapPoint> = emptyList()
    private var tilesEnabled = true
    private var zoom = 13
    private var originPxX = 0.0
    private var originPxY = 0.0

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(70, 255, 255, 255) }
    private val bgPaint = Paint().apply { color = Color.rgb(38, 42, 52) }
    private val gridPaint = Paint().apply { color = Color.argb(60, 255, 255, 255); strokeWidth = 1f }
    private val activeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 140, 0); strokeWidth = dp(3f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val newLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 204, 113); strokeWidth = dp(3f); style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f); strokeCap = Paint.Cap.ROUND }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(10f); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 22, 28) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(9f); isFakeBoldText = true }
    private val attribution = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0); textSize = dp(7f) }
    private val attributionBg = Paint().apply { color = Color.argb(150, 255, 255, 255) }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = dp(2f); textSize = dp(8f) }

    fun setData(points: List<MapPoint>, tilesEnabled: Boolean) {
        this.points = points
        this.tilesEnabled = tilesEnabled
        requestLayout()
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun lonToPx(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * 256.0 * 2.0.pow(z)
    private fun latToPx(lat: Double, z: Int): Double {
        val rad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * 256.0 * 2.0.pow(z)
    }

    private fun fitView() {
        if (points.isEmpty() || width == 0 || height == 0) return
        val lats = points.map { it.point.lat }
        val lons = points.map { it.point.lon }
        val minLat = lats.min(); val maxLat = lats.max()
        val minLon = lons.min(); val maxLon = lons.max()
        val pad = dp(26f)
        var z = 16
        while (z > 9) {
            val w = lonToPx(maxLon, z) - lonToPx(minLon, z)
            val h = latToPx(minLat, z) - latToPx(maxLat, z)
            if (w <= width - 2 * pad && h <= height - 2 * pad) break
            z--
        }
        zoom = z
        val centerX = (lonToPx(minLon, z) + lonToPx(maxLon, z)) / 2.0
        val centerY = (latToPx(minLat, z) + latToPx(maxLat, z)) / 2.0
        originPxX = centerX - width / 2.0
        originPxY = centerY - height / 2.0
    }

    private fun toScreen(p: GeoPoint): Pair<Float, Float> =
        ((lonToPx(p.lon, zoom) - originPxX).toFloat()) to ((latToPx(p.lat, zoom) - originPxY).toFloat())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        fitView()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (tilesEnabled) {
            val firstTileX = floor(originPxX / 256.0).toInt()
            val firstTileY = floor(originPxY / 256.0).toInt()
            val lastTileX = floor((originPxX + width) / 256.0).toInt()
            val lastTileY = floor((originPxY + height) / 256.0).toInt()
            val maxTile = 2.0.pow(zoom).toInt() - 1
            for (tx in firstTileX..lastTileX) for (ty in firstTileY..lastTileY) {
                if (tx < 0 || ty < 0 || tx > maxTile || ty > maxTile) continue
                val bitmap = TileCache.get(zoom, tx, ty) { invalidate() } ?: continue
                val left = (tx * 256.0 - originPxX).toFloat()
                val top = (ty * 256.0 - originPxY).toFloat()
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + 256f, top + 256f), tilePaint)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        } else {
            var x = -(originPxX % 64).toFloat()
            while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += 64f }
            var y = -(originPxY % 64).toFloat()
            while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, gridPaint); y += 64f }
        }

        // Linie: ja → punkty biezacych (pomaranczowa), nowy odbior → nowa dostawa (zielona przerywana)
        val me = points.firstOrNull { it.kind == MapPoint.Kind.ME }
        val active = points.filter { it.kind == MapPoint.Kind.ACTIVE_PICKUP || it.kind == MapPoint.Kind.ACTIVE_DROP }
        if (active.isNotEmpty()) {
            val path = Path()
            val seq = listOfNotNull(me) + active
            seq.forEachIndexed { index, mp ->
                val (x, y) = toScreen(mp.point)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, activeLine)
        }
        val newPoints = points.filter { it.kind == MapPoint.Kind.NEW_PICKUP } + points.filter { it.kind == MapPoint.Kind.NEW_DROP }
        if (newPoints.isNotEmpty()) {
            val path = Path()
            val start = me ?: newPoints.first()
            val (sx, sy) = toScreen(start.point)
            path.moveTo(sx, sy)
            newPoints.forEach { mp -> val (x, y) = toScreen(mp.point); path.lineTo(x, y) }
            canvas.drawPath(path, newLine)
        }

        // Markery
        val order = listOf(MapPoint.Kind.ACTIVE_PICKUP, MapPoint.Kind.ACTIVE_DROP, MapPoint.Kind.NEW_PICKUP, MapPoint.Kind.NEW_DROP, MapPoint.Kind.ME)
        points.sortedBy { order.indexOf(it.kind) }.forEach { mp ->
            val (x, y) = toScreen(mp.point)
            val radius = if (mp.kind == MapPoint.Kind.ME) dp(6f) else dp(9f)
            markerFill.color = when (mp.kind) {
                MapPoint.Kind.ME -> Color.rgb(66, 133, 244)
                MapPoint.Kind.ACTIVE_PICKUP -> Color.rgb(255, 140, 0)
                MapPoint.Kind.ACTIVE_DROP -> Color.rgb(230, 90, 0)
                MapPoint.Kind.NEW_PICKUP -> Color.rgb(46, 204, 113)
                MapPoint.Kind.NEW_DROP -> Color.rgb(24, 160, 90)
            }
            canvas.drawCircle(x, y, radius, markerFill)
            canvas.drawCircle(x, y, radius, markerStroke)
            val glyph = when (mp.kind) {
                MapPoint.Kind.ME -> ""
                MapPoint.Kind.ACTIVE_PICKUP -> "A"
                MapPoint.Kind.ACTIVE_DROP -> "B"
                MapPoint.Kind.NEW_PICKUP -> "1"
                MapPoint.Kind.NEW_DROP -> "2"
            }
            if (glyph.isNotEmpty()) canvas.drawText(glyph, x, y + dp(3.5f), markerText)
            val label = mp.label
            if (label.isNotBlank()) {
                val tw = labelText.measureText(label)
                val lx = (x + dp(11f)).coerceAtMost(width - tw - dp(6f))
                val ly = (y - dp(2f)).coerceIn(dp(12f), height - dp(4f))
                canvas.drawRoundRect(RectF(lx - dp(3f), ly - dp(10f), lx + tw + dp(3f), ly + dp(3f)), dp(4f), dp(4f), labelBg)
                canvas.drawText(label, lx, ly, labelText)
            }
        }

        // Skala
        val centerLat = points.map { it.point.lat }.average()
        val metersPerPx = 156543.03 * cos(Math.toRadians(centerLat)) / 2.0.pow(zoom)
        val candidates = listOf(100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)
        val scaleMeters = candidates.lastOrNull { it / metersPerPx <= width * 0.4 } ?: 100.0
        val scalePx = (scaleMeters / metersPerPx).toFloat()
        val sy = height - dp(8f)
        canvas.drawLine(dp(8f), sy, dp(8f) + scalePx, sy, scalePaint)
        canvas.drawText(if (scaleMeters >= 1000) "${(scaleMeters / 1000).toInt()} km" else "${scaleMeters.toInt()} m", dp(8f), sy - dp(4f), scalePaint)

        if (tilesEnabled) {
            val text = "© OpenStreetMap"
            val tw = attribution.measureText(text)
            canvas.drawRect(width - tw - dp(6f), height - dp(12f), width.toFloat(), height.toFloat(), attributionBg)
            canvas.drawText(text, width - tw - dp(3f), height - dp(3f), attribution)
        }
    }

    companion object {
        /** Pomocniczo: rozmiar kafelka OSM. */
        const val TILE = 256

        @Suppress("unused")
        fun tileToLat(y: Int, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / 2.0.pow(z)))))

        @Suppress("unused")
        fun clampZoom(z: Int): Int = max(9, min(17, z))
    }
}
