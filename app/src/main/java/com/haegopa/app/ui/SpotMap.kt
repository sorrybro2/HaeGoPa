package com.haegopa.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.SearchRadius
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.sdk.Waypoint
import com.haegopa.app.sdk.WaypointKind
import com.haegopa.app.ui.theme.HaegopaColors
import com.haegopa.app.ui.theme.LocalHaegopaPalette
import com.haegopa.app.ui.theme.MapPalette
import com.haegopa.app.ui.theme.Pretendard
import com.haegopa.app.ui.theme.color
import com.haegopa.app.ui.theme.ink
import kotlinx.coroutines.isActive
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MAX_PIN_LABELS = 5

/** 시연용 빨리감기: 차속 1km/h 당 화면 이동 속도(dp/s) */
private const val SCREEN_DP_PER_KMH = 0.8f

/** 도시 격자를 화면 축에서 살짝 비틀어 '모눈종이' 느낌을 없앤다 */
private const val GRID_ANGLE = -14f

/** 스팟을 고르면 지도가 그 스팟을 화면 가운데로 끌어오며 이만큼 확대한다 */
private const val FOCUS_ZOOM = 1.9f
private const val FOCUS_PIN_SCALE = 1.4f
private const val CAMERA_MS = 650

/** 화면 세로 위치: 전체 보기의 현재 위치 / 확대 보기의 고른 스팟 (위 칩 · 아래 카드를 피한 가운데) */
private const val ORIGIN_ANCHOR_Y = 0.56f
private const val FOCUS_ANCHOR_Y = 0.47f

/**
 * 해고파 '주변 스팟 지도'.
 *
 * 현재 위치 기준 반경 2km 안의 추천 스팟과 내 경로(경유지·목적지)를 한눈에 보여 주는 해고파 자체 지도다.
 * 순정 내비 화면을 흉내 내지 않는다 — 실제 길안내는 Pleos 순정 내비가 맡고, 해고파는 NaviHelper 로 경유지만 보낸다.
 * Pleos Connect 에서는 앱이 내비와 같은 메인 영역에 열리므로, 해고파 화면 안에도 '어디에 뭐가 있는지' 보여 줄 지도가 필요하다.
 *
 * 지도 타일 없이 Canvas 로 그리는 일러스트 지도: 고정 시드로 만든 가상의 도시(블록·공원·강·간선도로) 위에
 * 실제 좌표(현재 위치 대비 동·북 거리)로 핀을 투영한다. 정적 레이어와 동적 레이어(현재 위치 펄스·경로 흐름)를 나눠 그린다.
 *
 * 목록 카드나 핀을 눌러 [selected] 가 생기면 카메라가 그 스팟으로 스윽 이동·확대하고 핀을 강조한다.
 * 핀을 누르면 [onSpotTap], 빈 곳을 누르면 [onBackgroundTap] (선택 해제 → 전체 보기).
 */
@Composable
fun SpotMap(
    origin: GeoPoint,
    spots: List<Spot>,
    category: SpotCategory,
    waypoints: List<Waypoint>,
    speedKmh: Int,
    modifier: Modifier = Modifier,
    radiusMeters: Int = SearchRadius.DEFAULT_METERS,
    selected: Spot? = null,
    onSpotTap: (Spot) -> Unit = {},
    onBackgroundTap: () -> Unit = {},
) {
    val palette = LocalHaegopaPalette.current
    val map = palette.map
    val accent = category.color()
    val accentInk = category.ink()
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val pinIcon = rememberVectorPainter(ImageVector.vectorResource(category.iconRes()))
    val flagIcon = rememberVectorPainter(ImageVector.vectorResource(HaegopaIcons.Flag))
    val routeCasing = if (palette.isDark) Color(0xFF0B1F3A) else Color.White
    val passedRoute = if (palette.isDark) Color(0xFF5A6578) else Color(0xFFAEB7C4)

    val transition = rememberInfiniteTransition(label = "spot-map")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_800, easing = LinearEasing)),
        label = "pulse",
    )

    // 주행 중이면 차가 경로(도로)를 따라 달린다. 실제 속도로는 화면에서 거의 안 움직이므로
    // 차속에 비례한 화면 속도(30km/h ≈ 24dp/s)로 빨리 감는다. 정차하면 현재 위치로 돌아온다.
    val latestSpeed by rememberUpdatedState(speedKmh)
    var travelDp by remember { mutableFloatStateOf(0f) }
    val moving = speedKmh > 0
    LaunchedEffect(moving) {
        if (!moving) {
            travelDp = 0f
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                travelDp += (now - last) / 1_000_000_000f * latestSpeed * SCREEN_DP_PER_KMH
                last = now
            }
        }
    }

    // 카메라: 고른 스팟이 있으면 그 스팟을 (가로 중앙, FOCUS_ANCHOR_Y)로 끌어오며 확대, 없으면 현재 위치 중심 전체 보기.
    // 값은 그리기 단계에서만 읽어 애니메이션 중에도 재구성 없이 다시 그리기만 한다.
    val focus = selected?.let { GeoUtils.toLocalMeters(origin, it.location) }
    val cameraSpec = tween<Float>(durationMillis = CAMERA_MS, easing = FastOutSlowInEasing)
    val zoom = animateFloatAsState(if (focus != null) FOCUS_ZOOM else 1f, cameraSpec, label = "camera-zoom")
    val east = animateFloatAsState(focus?.first?.toFloat() ?: 0f, cameraSpec, label = "camera-east")
    val north = animateFloatAsState(focus?.second?.toFloat() ?: 0f, cameraSpec, label = "camera-north")
    val anchorY = animateFloatAsState(if (focus != null) FOCUS_ANCHOR_Y else ORIGIN_ANCHOR_Y, cameraSpec, label = "camera-anchor")
    fun camera() = Camera(zoom.value, east.value, north.value, anchorY.value)

    val tapSpots by rememberUpdatedState(spots)
    val tapSelected by rememberUpdatedState(selected)
    val tapOrigin by rememberUpdatedState(origin)
    val tapRadius by rememberUpdatedState(radiusMeters)
    val tapSpot by rememberUpdatedState(onSpotTap)
    val tapBackground by rememberUpdatedState(onBackgroundTap)

    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val geo = MapGeometry(size.width.toFloat(), size.height.toFloat(), tapRadius, camera())
                    val hit = hitTestPin(tap, geo, tapOrigin, tapSpots, tapSelected)
                    if (hit != null) tapSpot(hit) else tapBackground()
                }
            },
    ) {
        // ── 정적 레이어: 도시 · 반경 · 경로 · 핀 ──
        Canvas(Modifier.fillMaxSize()) {
            val geo = MapGeometry(size.width, size.height, radiusMeters, camera())
            drawRect(map.base)
            drawCity(geo, map)

            // 반경 2km: 안쪽은 은은한 카테고리색, 테두리는 점선
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.05f)),
                    center = geo.center,
                    radius = geo.radiusPx,
                ),
                radius = geo.radiusPx,
                center = geo.center,
            )
            drawCircle(
                color = accent.copy(alpha = 0.75f),
                radius = geo.radiusPx,
                center = geo.center,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 9.dp.toPx())),
                ),
            )

            // 내 경로 (현재 위치 → 경유지 → 목적지): 도로 격자를 따라 꺾인다
            val route = buildRoute(geo, origin, waypoints)
            rotate(GRID_ANGLE, pivot = geo.center) {
                drawPath(route.path, routeCasing, style = Stroke(13.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(route.path, HaegopaColors.Route, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            val occupied = mutableListOf(Rect(center = geo.center, radius = 26.dp.toPx()))

            // 반경 라벨: 원의 왼쪽 위(-135°)
            occupied += drawChip(
                measurer = textMeasurer,
                text = "반경 ${GeoUtils.formatRadius(radiusMeters)}",
                center = Offset(geo.center.x - geo.radiusPx * 0.707f, geo.center.y - geo.radiusPx * 0.707f),
                textColor = accentInk,
                background = map.chip,
                fontSize = 13,
            )

            // 스팟 핀 (가까운 순 = 목록 순서). 1위는 조금 크게, 고른 스팟은 더 크게 맨 위에
            val margin = 24.dp.toPx()
            val pins = pinsOf(spots, selected).mapIndexedNotNull { index, spot ->
                val p = geo.project(origin, spot.location)
                val focused = spot.id == selected?.id
                val inside = p.x in -margin..(size.width + margin) && p.y in -margin..(size.height + margin)
                if (!inside && !focused) return@mapIndexedNotNull null
                val scale = when {
                    focused -> FOCUS_PIN_SCALE
                    index == 0 -> 1.18f
                    else -> 1f
                }
                MapPin(spot, tip = p, head = Offset(p.x, p.y - pinHeadLift() * scale), scale = scale, focused = focused)
            }
            val focusedPin = pins.firstOrNull { it.focused }
            (pins.asReversed().filterNot { it.focused } + listOfNotNull(focusedPin)).forEach { pin ->
                occupied += drawPin(pin.tip, pin.scale, accent, pinIcon)
            }

            // 이름 라벨: 고른 스팟은 강조색으로 먼저 자리를 잡고, 나머지는 가까운 곳부터 겹치지 않는 자리에만
            if (focusedPin != null) {
                placeLabel(textMeasurer, focusedPin.spot.name, focusedPin.head, occupied, map, emphasize = true, highlight = accent, force = true)
                    ?.let { occupied += it }
            }
            var labeled = 0
            for (pin in pins) {
                if (labeled >= MAX_PIN_LABELS) break
                if (pin.focused) continue
                val rect = placeLabel(textMeasurer, pin.spot.name, pin.head, occupied, map, emphasize = pin.scale > 1f) ?: continue
                occupied += rect
                labeled += 1
            }

            // 경유지 · 목적지 마커
            waypoints.forEach { waypoint ->
                val p = geo.project(origin, waypoint.location)
                if (waypoint.kind == WaypointKind.DESTINATION) {
                    drawCircle(Color.Black.copy(alpha = 0.18f), 19.dp.toPx(), p + Offset(0f, 2.dp.toPx()))
                    drawCircle(HaegopaColors.Danger, 17.dp.toPx(), p)
                    drawCircle(Color.White, 17.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
                    drawIcon(flagIcon, p, 18.dp.toPx(), Color.White)
                } else {
                    drawCircle(Color.Black.copy(alpha = 0.18f), 18.dp.toPx(), p + Offset(0f, 2.dp.toPx()))
                    drawCircle(HaegopaColors.Amber, 16.dp.toPx(), p)
                    drawCircle(Color.White, 16.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
                    val layout = textMeasurer.measure(
                        "${waypoint.order}",
                        TextStyle(fontFamily = Pretendard, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = HaegopaColors.OnAccent),
                    )
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                }
            }
        }

        // ── 동적 레이어: 고른 스팟 물결 · 달리는 차 · 지나온 길 ──
        Canvas(Modifier.fillMaxSize()) {
            val geo = MapGeometry(size.width, size.height, radiusMeters, camera())

            // 고른 스팟: 핀 머리 둘레로 퍼지는 물결 (지도가 옮겨 간 곳이 어디인지 바로 보이게)
            selected?.let { spot ->
                val tip = geo.project(origin, spot.location)
                val head = Offset(tip.x, tip.y - pinHeadLift() * FOCUS_PIN_SCALE)
                val headRadius = 14.dp.toPx() * FOCUS_PIN_SCALE
                drawCircle(
                    color = accent.copy(alpha = (1f - pulse) * 0.6f),
                    radius = headRadius + 4.dp.toPx() + pulse * 28.dp.toPx(),
                    center = head,
                    style = Stroke(3.dp.toPx()),
                )
            }

            val route = buildRoute(geo, origin, waypoints)
            val measure = PathMeasure().apply { setPath(route.path, false) }
            val visible = visibleLength(measure, geo, size)
            val distance = if (moving && visible > 0f) (travelDp.dp.toPx() % visible) else 0f

            rotate(GRID_ANGLE, pivot = geo.center) {
                // 차 위치·방향 (격자 좌표계에서 경로를 따라)
                val car = if (distance > 0f) measure.getPosition(distance) else geo.center
                val tangent = if (distance > 0f) measure.getTangent(distance) else null
                val heading = tangent?.let { Math.toDegrees(atan2(it.x.toDouble(), -it.y.toDouble())).toFloat() }
                    ?: (route.headingDegrees - GRID_ANGLE)

                // 지나온 구간은 회색으로 덮는다
                if (distance > 0f) {
                    val passed = Path()
                    measure.getSegment(0f, distance, passed, true)
                    drawPath(passed, passedRoute, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }

                val halo = 16.dp.toPx() + pulse * 30.dp.toPx()
                drawCircle(HaegopaColors.Route.copy(alpha = (1f - pulse) * 0.30f), halo, car)
                drawCircle(Color.Black.copy(alpha = 0.18f), 18.dp.toPx(), car + Offset(0f, 2.dp.toPx()))
                drawCircle(Color.White, 17.dp.toPx(), car)
                drawCircle(HaegopaColors.Route, 13.dp.toPx(), car)
                rotate(heading, pivot = car) {
                    val s = 7.5.dp.toPx()
                    val arrow = Path().apply {
                        moveTo(car.x, car.y - s)
                        lineTo(car.x + s * 0.78f, car.y + s * 0.75f)
                        lineTo(car.x, car.y + s * 0.32f)
                        lineTo(car.x - s * 0.78f, car.y + s * 0.75f)
                        close()
                    }
                    drawPath(arrow, Color.White)
                }
            }
        }
    }
}

// ── 좌표계 ───────────────────────────────────────────────────────────────────

/**
 * 지도 카메라: [zoom] 배율로, 현재 위치에서 동 [east] · 북 [north] 미터 떨어진 지점을 화면 (가로 중앙, 세로 [anchorY])에 둔다.
 * 전체 보기는 현재 위치(0, 0)를 세로 56% 지점에 둔 1배 화면.
 */
private class Camera(val zoom: Float, val east: Float, val north: Float, val anchorY: Float) {
    companion object {
        val Overview = Camera(zoom = 1f, east = 0f, north = 0f, anchorY = ORIGIN_ANCHOR_Y)
    }
}

/** 화면 크기 + 카메라 → 투영 파라미터 (전체 보기에서는 검색 반경 원이 화면 안에 들어오도록) */
private class MapGeometry(val width: Float, val height: Float, radiusMeters: Int, camera: Camera = Camera.Overview) {
    val zoom = camera.zoom
    val radiusPx = min(width, height) * 0.40f * zoom
    val grid = radiusPx * 0.21f
    private val pxPerMeter = radiusPx / radiusMeters

    /** 현재 위치(원점)의 화면 좌표. 카메라가 보는 지점이 (가로 중앙, anchorY)에 오도록 원점을 옮긴다 */
    val center = Offset(width * 0.5f - camera.east * pxPerMeter, height * camera.anchorY + camera.north * pxPerMeter)
    private val cosA = cos(Math.toRadians(GRID_ANGLE.toDouble())).toFloat()
    private val sinA = sin(Math.toRadians(GRID_ANGLE.toDouble())).toFloat()

    /** 격자 좌표 → 화면 좌표 (toGrid 의 역) */
    fun toScreen(g: Offset): Offset {
        val dx = g.x - center.x
        val dy = g.y - center.y
        return Offset(center.x + dx * cosA - dy * sinA, center.y + dx * sinA + dy * cosA)
    }

    /** 위경도 → 화면 좌표 (북쪽이 위) */
    fun project(origin: GeoPoint, point: GeoPoint): Offset {
        val (east, north) = GeoUtils.toLocalMeters(origin, point)
        return Offset(center.x + (east * pxPerMeter).toFloat(), center.y - (north * pxPerMeter).toFloat())
    }

    /** 화면 좌표 → 격자 좌표 (rotate(GRID_ANGLE) 의 역변환) */
    fun toGrid(p: Offset): Offset {
        val dx = p.x - center.x
        val dy = p.y - center.y
        return Offset(center.x + dx * cosA + dy * sinA, center.y - dx * sinA + dy * cosA)
    }

    fun snapToRoad(y: Float): Float = center.y + ((y - center.y) / grid).roundToInt() * grid
}

private class Route(val path: Path, val headingDegrees: Float)

private class MapPin(val spot: Spot, val tip: Offset, val head: Offset, val scale: Float, val focused: Boolean)

/** 지도에 꽂을 스팟: 반경 안 스팟 + (목록 뒤쪽 페이지에서 골라 아직 없는) 고른 스팟 */
private fun pinsOf(spots: List<Spot>, selected: Spot?): List<Spot> =
    if (selected == null || spots.any { it.id == selected.id }) spots else spots + selected

/** 터치한 자리에서 가장 가까운 핀(머리 ~ 끝 사이 막대 기준). 손가락 크기를 감안해 넉넉히 잡는다 */
private fun Density.hitTestPin(tap: Offset, geo: MapGeometry, origin: GeoPoint, spots: List<Spot>, selected: Spot?): Spot? {
    val slop = 20.dp.toPx()
    return pinsOf(spots, selected)
        .map { spot ->
            val tip = geo.project(origin, spot.location)
            val scale = if (spot.id == selected?.id) FOCUS_PIN_SCALE else 1f
            val head = Offset(tip.x, tip.y - 22.dp.toPx() * scale)
            spot to distanceToSegment(tap, head, tip) - 14.dp.toPx() * scale
        }
        .filter { (_, distance) -> distance <= slop }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val lengthSq = ab.x * ab.x + ab.y * ab.y
    val t = if (lengthSq == 0f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / lengthSq).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

/** 경로가 화면 밖으로 나가기 전까지의 길이 — 차가 화면 안 구간만 반복해서 달리게 한다 */
private fun visibleLength(measure: PathMeasure, geo: MapGeometry, size: Size): Float {
    val length = measure.length
    if (length <= 0f) return 0f
    val margin = 8f
    var d = 0f
    val step = 6f
    while (d < length) {
        val p = geo.toScreen(measure.getPosition(d))
        if (p.x < -margin || p.y < -margin || p.x > size.width + margin || p.y > size.height + margin) return d
        d += step
    }
    return length
}

/** 격자 좌표계에서 도로를 따라 꺾이는 경로를 만든다. 그릴 때는 rotate(GRID_ANGLE) 안에서 그린다 */
private fun buildRoute(geo: MapGeometry, origin: GeoPoint, waypoints: List<Waypoint>): Route {
    val stops = waypoints.filter { it.kind == WaypointKind.WAYPOINT }.sortedBy { it.order }
        .map { geo.toGrid(geo.project(origin, it.location)) }
    val destination = waypoints.firstOrNull { it.kind == WaypointKind.DESTINATION }
        ?.let { geo.toGrid(geo.project(origin, it.location)) }

    val c = geo.center
    val targets = mutableListOf<Offset>()
    if (stops.isEmpty() && destination == null) {
        // 가상 경로: 북쪽으로 직진 → 동쪽 두 블록 → 다시 북쪽 (목적지는 화면 밖)
        targets += Offset(c.x + geo.grid * 2, c.y - geo.grid * 3)
        targets += Offset(c.x + geo.grid * 2, c.y - geo.grid * 14)
    } else {
        targets += stops
        targets += destination ?: Offset(targets.last().x, c.y - geo.grid * 14)
    }

    val path = Path().apply { moveTo(c.x, c.y) }
    var current = c
    var heading: Offset? = null
    for (target in targets) {
        val roadY = geo.snapToRoad(target.y)
        val legs = listOf(Offset(current.x, roadY), Offset(target.x, roadY), target)
        for (leg in legs) {
            if (heading == null && hypot(leg.x - current.x, leg.y - current.y) > 1f) {
                heading = Offset(leg.x - current.x, leg.y - current.y)
            }
            path.lineTo(leg.x, leg.y)
            current = Offset(leg.x, leg.y)
        }
    }
    val h = heading ?: Offset(0f, -1f)
    // 화면 기준 '위'가 0도. 격자 회전만큼 더한다
    val degrees = Math.toDegrees(atan2(h.x.toDouble(), -h.y.toDouble())).toFloat() + GRID_ANGLE
    return Route(path, degrees)
}

// ── 일러스트 도시 ─────────────────────────────────────────────────────────────

private fun hash(i: Int, j: Int): Int {
    var h = i * 374_761_393 + j * 668_265_263
    h = (h xor (h ushr 13)) * 1_274_126_177
    return h xor (h ushr 16)
}

/** 공원으로 쓰는 격자 칸 (현재 위치 기준 i=동쪽, j=남쪽 블록 번호) */
private fun isPark(i: Int, j: Int): Boolean =
    (i in -6..-4 && j in -5..-4) || (i in 3..4 && j in 0..1) || (i in -3..-2 && j in 2..2) || (i in 5..7 && j in -7..-6)

private fun DrawScope.drawCity(geo: MapGeometry, map: MapPalette) {
    val s = geo.grid
    val c = geo.center
    // 카메라가 원점을 화면 가운데에서 밀어낸 만큼 블록을 더 깐다
    val shift = hypot(c.x - size.width / 2f, c.y - size.height / 2f)
    val n = ((hypot(size.width, size.height) / 2f + shift) / s).toInt() + 2
    val minorWidth = 3.5.dp.toPx()
    val inset = minorWidth / 2f + 1.5.dp.toPx()
    val corner = CornerRadius(3.dp.toPx())

    rotate(GRID_ANGLE, pivot = c) {
        // 블록 (일부는 둘로 쪼개 건물 덩어리처럼)
        for (i in -n..n) for (j in -n..n) {
            val left = c.x + i * s + inset
            val top = c.y + j * s + inset
            val side = s - inset * 2
            val h = hash(i, j)
            if (isPark(i, j)) {
                drawRect(map.park, Offset(left - inset, top - inset), Size(s, s))
                continue
            }
            if (h % 9 == 0) continue // 공터
            val color = if (h % 3 == 0) map.blockAlt else map.block
            if (h % 4 == 1) {
                val split = side * (0.4f + (h ushr 8 and 0xFF) / 255f * 0.2f)
                drawRoundRect(color, Offset(left, top), Size(side, split - inset / 2), corner)
                drawRoundRect(color, Offset(left, top + split + inset / 2), Size(side, side - split - inset / 2), corner)
            } else {
                drawRoundRect(color, Offset(left, top), Size(side, side), corner)
            }
        }
        // 이면도로
        for (k in -n..n) {
            drawLine(map.roadMinor, Offset(c.x + k * s, c.y - n * s), Offset(c.x + k * s, c.y + n * s), minorWidth)
            drawLine(map.roadMinor, Offset(c.x - n * s, c.y + k * s), Offset(c.x + n * s, c.y + k * s), minorWidth)
        }
    }

    // 강 · 도시고속도로는 원점(c) 기준 '세계 좌표'로 그려 카메라 이동 · 확대를 그대로 따라간다.
    // wx(f) = 전체 보기에서 화면 가로 f 지점. 양 끝은 화면 밖까지 곧게 늘여 옆으로 옮겨 가도 끊기지 않게 한다
    val r = geo.radiusPx
    fun wx(f: Float) = c.x + (f - 0.5f) * size.width * geo.zoom
    val edge = 60f * geo.zoom
    val reach = size.width * 2f

    // 강: 화면 아래쪽을 가로지르는 넓은 물길 (다리 역할을 하는 간선도로가 위에 그려진다)
    val river = Path().apply {
        moveTo(wx(0f) - edge - reach, c.y + r * 1.12f)
        lineTo(wx(0f) - edge, c.y + r * 1.12f)
        cubicTo(wx(0.28f), c.y + r * 0.86f, wx(0.52f), c.y + r * 1.32f, wx(0.78f), c.y + r * 1.02f)
        cubicTo(wx(0.90f), c.y + r * 0.90f, wx(0.97f), c.y + r * 0.98f, wx(1f) + edge, c.y + r * 0.92f)
        lineTo(wx(1f) + edge + reach, c.y + r * 0.92f)
    }
    drawPath(river, map.water, style = Stroke(width = r * 0.26f, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // 간선도로 (격자에 맞춘 큰길) — 테두리 + 채움
    val majorFill = 9.dp.toPx()
    val majorCasing = majorFill + 3.dp.toPx()
    rotate(GRID_ANGLE, pivot = c) {
        val far = n * s
        val verticals = listOf(0, 5, -5)
        val horizontals = listOf(-3, 4)
        verticals.forEach { k ->
            drawLine(map.roadCasing, Offset(c.x + k * s, c.y - far), Offset(c.x + k * s, c.y + far), majorCasing)
        }
        horizontals.forEach { k ->
            drawLine(map.roadCasing, Offset(c.x - far, c.y + k * s), Offset(c.x + far, c.y + k * s), majorCasing)
        }
        verticals.forEach { k ->
            drawLine(map.roadMajor, Offset(c.x + k * s, c.y - far), Offset(c.x + k * s, c.y + far), majorFill)
        }
        horizontals.forEach { k ->
            drawLine(map.roadMajor, Offset(c.x - far, c.y + k * s), Offset(c.x + far, c.y + k * s), majorFill)
        }
    }

    // 도시고속도로: 화면 위쪽을 부드럽게 가로지른다
    val highway = Path().apply {
        moveTo(wx(0f) - edge - reach, c.y - r * 1.05f)
        lineTo(wx(0f) - edge, c.y - r * 1.05f)
        cubicTo(wx(0.30f), c.y - r * 1.45f, wx(0.62f), c.y - r * 0.80f, wx(1f) + edge, c.y - r * 1.25f)
        lineTo(wx(1f) + edge + reach, c.y - r * 1.25f)
    }
    drawPath(highway, map.highwayCasing, style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round))
    drawPath(highway, map.highway, style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round))
}

// ── 핀 · 라벨 ─────────────────────────────────────────────────────────────────

private fun DrawScope.pinHeadLift(): Float = 22.dp.toPx()

/** 물방울 모양 핀. 끝이 실제 위치를 가리키고, 머리에 카테고리 아이콘. 머리 영역을 돌려준다 */
private fun DrawScope.drawPin(tip: Offset, scale: Float, accent: Color, icon: Painter): Rect {
    val r = 14.dp.toPx() * scale
    val head = Offset(tip.x, tip.y - pinHeadLift() * scale)
    // 바닥 그림자
    drawOval(
        color = Color.Black.copy(alpha = 0.22f),
        topLeft = Offset(tip.x - 7.dp.toPx() * scale, tip.y - 2.dp.toPx()),
        size = Size(14.dp.toPx() * scale, 4.dp.toPx()),
    )
    val body = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(head.x - r * 0.62f, head.y + r * 0.72f)
        lineTo(head.x + r * 0.62f, head.y + r * 0.72f)
        close()
    }
    drawPath(body, Color.White)
    drawCircle(Color.White, r + 2.5.dp.toPx(), head)
    drawPath(
        Path().apply {
            moveTo(tip.x, tip.y - 3.dp.toPx())
            lineTo(head.x - r * 0.5f, head.y + r * 0.6f)
            lineTo(head.x + r * 0.5f, head.y + r * 0.6f)
            close()
        },
        accent,
    )
    drawCircle(accent, r, head)
    drawIcon(icon, head, r * 1.15f, Color.White)
    return Rect(center = head, radius = r + 3.dp.toPx())
}

private fun DrawScope.drawIcon(painter: Painter, center: Offset, side: Float, tint: Color) {
    translate(center.x - side / 2f, center.y - side / 2f) {
        with(painter) { draw(Size(side, side), colorFilter = ColorFilter.tint(tint)) }
    }
}

private fun DrawScope.placeLabel(
    measurer: TextMeasurer,
    text: String,
    head: Offset,
    occupied: List<Rect>,
    map: MapPalette,
    emphasize: Boolean,
    highlight: Color? = null,
    force: Boolean = false,
): Rect? {
    val style = TextStyle(
        fontFamily = Pretendard,
        fontSize = if (highlight != null) 16.sp else if (emphasize) 14.sp else 13.sp,
        fontWeight = if (emphasize) FontWeight.Bold else FontWeight.SemiBold,
        color = if (highlight != null) HaegopaColors.OnAccent else map.chipText,
    )
    val layout = measurer.measure(text, style)
    val padX = 8.dp.toPx()
    val padY = 4.dp.toPx()
    val w = layout.size.width + padX * 2
    val h = layout.size.height + padY * 2
    val gap = (if (highlight != null) 26 else 18).dp.toPx() // 고른 핀은 머리가 커서 더 띄운다
    val candidates = listOf(
        Offset(head.x + gap, head.y - h / 2), // 오른쪽
        Offset(head.x - gap - w, head.y - h / 2), // 왼쪽
        Offset(head.x - w / 2, head.y - gap - h), // 위
        Offset(head.x - w / 2, head.y + gap + 6.dp.toPx()), // 아래
    )
    val rects = candidates.map { Rect(it, Size(w, h)) }
    fun onScreen(r: Rect) = r.left >= 0f && r.top >= 0f && r.right <= size.width && r.bottom <= size.height
    // force: 고른 스팟 라벨은 빈자리가 없어도 화면 안 첫 자리에 그린다
    val rect = rects.firstOrNull { r -> onScreen(r) && occupied.none { it.overlaps(r) } }
        ?: (if (force) rects.firstOrNull(::onScreen) ?: rects.first() else null)
        ?: return null
    drawRoundRect(Color.Black.copy(alpha = 0.10f), rect.topLeft + Offset(0f, 1.5.dp.toPx()), rect.size, CornerRadius(10.dp.toPx()))
    drawRoundRect(highlight ?: map.chip, rect.topLeft, rect.size, CornerRadius(10.dp.toPx()))
    drawText(layout, topLeft = Offset(rect.left + padX, rect.top + padY))
    return rect
}

/** 가운데 정렬 칩. 차지한 영역을 돌려준다 */
private fun DrawScope.drawChip(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    textColor: Color,
    background: Color,
    fontSize: Int,
): Rect {
    val layout = measurer.measure(
        text,
        TextStyle(fontFamily = Pretendard, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, color = textColor),
    )
    val padX = 8.dp.toPx()
    val padY = 3.dp.toPx()
    val rect = Rect(
        offset = Offset(center.x - layout.size.width / 2f - padX, center.y - layout.size.height / 2f - padY),
        size = Size(layout.size.width + padX * 2, layout.size.height + padY * 2),
    )
    drawRoundRect(background, rect.topLeft, rect.size, CornerRadius(10.dp.toPx()))
    drawText(layout, topLeft = Offset(rect.left + padX, rect.top + padY))
    return rect
}
