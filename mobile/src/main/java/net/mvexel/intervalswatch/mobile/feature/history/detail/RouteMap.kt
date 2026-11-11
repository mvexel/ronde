package net.mvexel.intervalswatch.mobile.feature.history.detail

import android.content.Context
import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.mvexel.intervalswatch.mobile.data.activity.LatLon
import net.mvexel.intervalswatch.mobile.ui.RondeCondensed
import net.mvexel.intervalswatch.mobile.ui.RondeColors
import net.mvexel.intervalswatch.mobile.ui.RondeTheme
import net.mvexel.intervalswatch.shared.StepKind
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos

/** Size of the corner left free on the thumbnail so MapLibre's collapsed (i) attribution button stays tappable. */
private val ATTRIBUTION_CORNER = 40.dp

/** Space left around the route on each side, as a fraction of the map's width/height, so the streets around it show. */
internal const val ROUTE_FRAME_PAD = 0.18f

/** Closest the framing zooms, so a short loop still shows its neighbourhood (z15.5 is a few blocks across). */
private const val ROUTE_MAX_FRAME_ZOOM = 15.5

/** The Route card: a square, non-interactive thumbnail with the credit line under it; tap opens an interactive full-screen map. */
@Composable
internal fun RouteMapCard(route: RouteDrawing, caption: String?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val p = RondeTheme.colors
    DetailCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Route", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = p.ink3)
            }
            Spacer(Modifier.height(10.dp))
            // Square at phone width; on wide screens capped in height (it then reads about 4:3 or wider).
            Box(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp)).background(p.mapLand),
            ) {
                RouteMapView(route, interactive = false, Modifier.fillMaxSize())
                // Swallow touches on the thumbnail so the page scrolls and a tap expands, except in the bottom-end
                // corner, where MapLibre's collapsed (i) attribution button sits and must stay tappable.
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().clickable(onClickLabel = "Expand map") { expanded = true })
                    Row(Modifier.height(ATTRIBUTION_CORNER).fillMaxWidth()) {
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = "Expand map") { expanded = true })
                        Spacer(Modifier.width(ATTRIBUTION_CORNER))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val kinds = route.segments.mapNotNull { it.kind }.toSet()
                if (StepKind.ACTIVE in kinds) RouteKey(p.work, "work")
                if (StepKind.REST in kinds) RouteKey(p.recovery, "easy")
                if (StepKind.WARMUP in kinds || StepKind.COOLDOWN in kinds) RouteKey(p.warmup, "warm-up / cool-down")
            }
            Text("↗ tap to expand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(p.mapLand)) {
                RouteMapView(route, interactive = true, Modifier.fillMaxSize())
                FilledTonalIconButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close map")
                }
            }
        }
    }
}

@Composable
private fun RouteKey(color: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(width = 14.dp, height = 4.dp).background(color, RoundedCornerShape(2.dp)))
    Spacer(Modifier.width(4.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** MapLibre in an AndroidView; a Canvas sketch in previews and Paparazzi, where the native renderer can't run. */
@Composable
private fun RouteMapView(route: RouteDrawing, interactive: Boolean, modifier: Modifier) {
    val desc = "Route map, ${route.markers.size} reps marked"
    if (LocalInspectionMode.current) {
        RouteSketch(route, modifier.semantics { contentDescription = desc })
        return
    }
    val p = RondeTheme.colors
    val context = LocalContext.current
    val mapView = remember(interactive) { RondeMaps.mapView(context, interactive, p.mapLand) }
    // Hidden until the style is loaded, the route framed and the first full frame drawn, then faded in over the
    // land colour behind it: no loading colour, default camera or camera jump shows (the "flicker").
    var ready by remember(mapView) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (ready) 1f else 0f, tween(200), label = "map-reveal")
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var state = Lifecycle.State.CREATED
        fun moveTo(target: Lifecycle.State) {
            if (target >= Lifecycle.State.STARTED && state < Lifecycle.State.STARTED) mapView.onStart()
            if (target >= Lifecycle.State.RESUMED && state < Lifecycle.State.RESUMED) mapView.onResume()
            if (target < Lifecycle.State.RESUMED && state >= Lifecycle.State.RESUMED) mapView.onPause()
            if (target < Lifecycle.State.STARTED && state >= Lifecycle.State.STARTED) mapView.onStop()
            state = target
        }
        val observer = LifecycleEventObserver { _, e -> moveTo(e.targetState.coerceAtLeast(Lifecycle.State.CREATED)) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            moveTo(Lifecycle.State.CREATED)
            mapView.onDestroy()
        }
    }
    AndroidView(
        factory = { mapView.also { v -> v.getMapAsync { map -> RondeMaps.show(v, map, route, p) { ready = true } } } },
        modifier = modifier.alpha(alpha).semantics { contentDescription = desc },
    )
}

/** MapLibre setup: one-time init with a Ronde User-Agent, the bundled muted styles, and the route layers. */
private object RondeMaps {
    private var ready = false

    fun mapView(context: Context, interactive: Boolean, land: Color): MapView {
        if (!ready) {
            MapLibre.getInstance(context.applicationContext)
            // OpenFreeMap asks clients for a unique User-Agent.
            HttpRequestUtil.setOkHttpClient(
                OkHttpClient.Builder().addNetworkInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", "Ronde-Android (+https://github.com/mvexel/ronde)").build())
                }.build(),
            )
            ready = true
        }
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true) // draws inside a scrolling Compose list without SurfaceView z-order issues
            .foregroundLoadColor(land.toArgb()) // not MapLibre's default grey while the style loads
            // MapLibre's logo is optional under its BSD licence. Both maps credit the data with MapLibre's collapsed
            // (i) button, which lists the style's source credits (OpenFreeMap, OpenMapTiles, OpenStreetMap).
            .logoEnabled(false)
            .attributionEnabled(true)
            .attributionGravity(Gravity.BOTTOM or Gravity.END)
            .attributionMargins(IntArray(4) { (8 * context.resources.displayMetrics.density).toInt() })
            .compassEnabled(false)
        return MapView(context, options).apply { onCreate(null) }.also { v ->
            v.getMapAsync { m -> m.uiSettings.setAllGesturesEnabled(interactive); m.uiSettings.isRotateGesturesEnabled = false }
        }
    }

    fun show(view: MapView, map: MapLibreMap, route: RouteDrawing, p: RondeColors, onReady: () -> Unit) {
        val asset = if (p.isDark) "asset://map/ronde_route_dark.json" else "asset://map/ronde_route_light.json"
        map.setStyle(Style.Builder().fromUri(asset)) { style ->
            val lines = route.segments.map { seg ->
                Feature.fromGeometry(LineString.fromLngLats(seg.points.map { Point.fromLngLat(it.lon, it.lat) }))
                    .apply { addStringProperty("kind", seg.kind?.name ?: "NONE") }
            }
            style.addSource(GeoJsonSource("route", FeatureCollection.fromFeatures(lines)))
            fun c(color: Color) = Expression.color(color.toArgb())
            style.addLayer(
                LineLayer("route-casing", "route").withProperties(
                    PropertyFactory.lineColor(c(p.casing)), PropertyFactory.lineWidth(6.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer("route-line", "route").withProperties(
                    PropertyFactory.lineColor(
                        Expression.match(
                            Expression.get("kind"), c(p.routeNeutral),
                            Expression.stop(StepKind.ACTIVE.name, c(p.work)),
                            Expression.stop(StepKind.REST.name, c(p.recovery)),
                            Expression.stop(StepKind.WARMUP.name, c(p.warmup)),
                            Expression.stop(StepKind.COOLDOWN.name, c(p.warmup)),
                        ),
                    ),
                    PropertyFactory.lineWidth(3.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            val start = Feature.fromGeometry(Point.fromLngLat(route.start.lon, route.start.lat))
            style.addSource(GeoJsonSource("route-start", FeatureCollection.fromFeatures(listOf(start))))
            style.addLayer(
                CircleLayer("route-start", "route-start").withProperties(
                    PropertyFactory.circleRadius(5f), PropertyFactory.circleColor(c(Color.White)),
                    PropertyFactory.circleStrokeColor(c(p.casing)), PropertyFactory.circleStrokeWidth(2f),
                ),
            )
            val reps = route.markers.map { m ->
                Feature.fromGeometry(Point.fromLngLat(m.at.lon, m.at.lat)).apply { addStringProperty("n", m.rep.toString()) }
            }
            style.addSource(GeoJsonSource("route-reps", FeatureCollection.fromFeatures(reps)))
            style.addLayer(
                CircleLayer("route-rep-disc", "route-reps").withProperties(
                    PropertyFactory.circleRadius(8.5f), PropertyFactory.circleColor(c(Color.Black)),
                    PropertyFactory.circleStrokeColor(c(p.slabEdge ?: Color.Black)), PropertyFactory.circleStrokeWidth(1f),
                ),
            )
            style.addLayer(
                SymbolLayer("route-rep-number", "route-reps").withProperties(
                    PropertyFactory.textField(Expression.get("n")), PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(11f), PropertyFactory.textColor(c(p.work)),
                    PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
                ),
            )
            val b = DetailModel.bounds(route.all)
            val bounds = LatLngBounds.Builder().include(LatLng(b[0], b[1])).include(LatLng(b[2], b[3])).build()
            // Frame on the laid-out size: ROUTE_FRAME_PAD of the view on every side, then back off to a neighbourhood
            // view if the route is so short that the bounds would zoom in past ROUTE_MAX_FRAME_ZOOM.
            view.doOnLayout { v ->
                val px = (v.width * ROUTE_FRAME_PAD).toInt()
                val py = (v.height * ROUTE_FRAME_PAD).toInt()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, px, py, px, py))
                if (map.cameraPosition.zoom > ROUTE_MAX_FRAME_ZOOM) map.moveCamera(CameraUpdateFactory.zoomTo(ROUTE_MAX_FRAME_ZOOM))
                // Reveal once the framed view has fully rendered (tiles in), or after 1.5 s so a slow network
                // never leaves an empty card.
                var done = false
                fun reveal() { if (!done) { done = true; onReady() } }
                view.addOnDidFinishRenderingMapListener { partial -> if (!partial) reveal() }
                view.postDelayed({ reveal() }, 1500)
            }
        }
    }
}

/** The route on a plain land ground, for previews and screenshot tests (no tiles, no native code). */
@Composable
private fun RouteSketch(route: RouteDrawing, modifier: Modifier) {
    val p = RondeTheme.colors
    val measurer = rememberTextMeasurer()
    val numStyle = TextStyle(fontFamily = RondeCondensed, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = p.work)
    val other = p.routeNeutral
    Canvas(modifier) {
        // A park, a water edge and a street grid in the map tokens, so the stand-in reads as the real map.
        drawRect(p.mapPark, Offset(0f, size.height * 0.62f), Size(size.width * 0.3f, size.height * 0.38f))
        val step = 26.dp.toPx()
        var gx = step / 2
        while (gx < size.width) { drawLine(p.mapMinor, Offset(gx, 0f), Offset(gx, size.height), 3.dp.toPx()); gx += step }
        var gy = step / 2
        while (gy < size.height) { drawLine(p.mapMinor, Offset(0f, gy), Offset(size.width, gy), 3.dp.toPx()); gy += step }
        drawRect(p.mapWater, Offset(size.width * 0.84f, 0f), Size(size.width * 0.16f, size.height * 0.45f))
        // Same framing as the real map: ROUTE_FRAME_PAD of the view left free on every side.
        val b = DetailModel.bounds(route.all)
        val k = cos(Math.toRadians((b[0] + b[2]) / 2))
        val w = (b[3] - b[1]) * k
        val h = b[2] - b[0]
        val inner = 1 - 2 * ROUTE_FRAME_PAD
        val scale = minOf(size.width * inner / w, size.height * inner / h)
        val ox = (size.width - w * scale) / 2
        val oy = (size.height - h * scale) / 2
        fun pt(l: LatLon) = Offset((ox + (l.lon - b[1]) * k * scale).toFloat(), (oy + (b[2] - l.lat) * scale).toFloat())
        fun path(points: List<LatLon>) = Path().apply { points.forEachIndexed { i, l -> pt(l).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
        route.segments.forEach { drawPath(path(it.points), p.casing, style = Stroke(6.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        route.segments.forEach { s ->
            drawPath(path(s.points), s.kind?.let(p::kind) ?: other, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val s = pt(route.start)
        drawCircle(p.casing, 6.5.dp.toPx(), s)
        drawCircle(Color.White, 4.5.dp.toPx(), s)
        route.markers.forEach { m ->
            val c = pt(m.at)
            drawCircle(Color.Black, 8.5.dp.toPx(), c)
            val t = measurer.measure(m.rep.toString(), numStyle)
            drawText(t, topLeft = Offset(c.x - t.size.width / 2, c.y - t.size.height / 2))
        }
    }
}
