package com.haegopa.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.Spot
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.util.GeoUtils
import com.haegopa.app.sdk.Waypoint
import com.haegopa.app.sdk.WaypointKind
import com.haegopa.app.ui.theme.HaegopaColors
import com.haegopa.app.ui.theme.color
import kotlinx.coroutines.isActive
import kotlin.math.min

private const val RADIUS_METERS = 2_000f
private const val MAX_PIN_LABELS = 4

/**
 * 좌측 65~70% '순정 내비게이션 지도 영역'을 대신하는 커스텀 캔버스 (지도 엔진을 직접 만들지 않는다는 기획 의도).
 * 실차에서는 이 영역에 Pleos 순정 내비가 그대로 보이고, 해고파는 NaviHelper 로 경유지만 주입한다.
 *
 * 정적 레이어(지형·도로·2km 반경·핀)와 동적 레이어(현재 위치 펄스·경로 흐름)를 나눠 매 프레임 재그리기를 최소화한다.
 */
@Composable
fun NaviMapMock(
    origin: GeoPoint,
    spots: List<Spot>,
    category: SpotCategory,
    waypoints: List<Waypoint>,
    speedKmh: Int,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val accent = category.color()

    val transition = rememberInfiniteTransition(label = "navi-map")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_600, easing = LinearEasing)),
        label = "pulse",
    )

    // 주행 중이면 경로 위 점선이 차속에 비례해 흐른다
    val latestSpeed by rememberUpdatedState(speedKmh)
    var travel by remember { mutableFloatStateOf(0f) }
    val moving = speedKmh > 0
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                travel += (now - last) / 1_000_000_000f * latestSpeed * 4f
                last = now
            }
        }
    }

    Box(modifier) {
        // ── 정적 레이어 ──
        Canvas(Modifier.fillMaxSize()) {
            val geo = MapGeometry(size.width, size.height)
            drawRect(HaegopaColors.MapBase)
            drawTerrain(geo)
            drawRoads(geo)

            // 반경 2km
            drawCircle(accent.copy(alpha = 0.07f), geo.radiusPx, geo.center)
            drawCircle(
                color = accent.copy(alpha = 0.6f),
                radius = geo.radiusPx,
                center = geo.center,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 10.dp.toPx())),
                ),
            )
            // 경로 (현재 위치 → 경유지 → 목적지)
            drawRoute(routePath(geo, origin, waypoints))

            // 반경 라벨은 경로선(세로 중앙)과 겹치지 않도록 원의 왼쪽 위(-135°)에 표시
            drawLabel(
                measurer = textMeasurer,
                text = "반경 2km",
                center = Offset(geo.center.x - geo.radiusPx * 0.707f, geo.center.y - geo.radiusPx * 0.707f),
                color = accent,
                fontSize = 13.sp,
                background = HaegopaColors.MapBase.copy(alpha = 0.85f),
            )

            // 스팟 핀
            val pinRadius = 15.dp.toPx()
            val visiblePins = spots.mapNotNull { spot ->
                val p = geo.project(origin, spot.location)
                val inside = p.x >= -pinRadius && p.x <= size.width + pinRadius &&
                    p.y >= -pinRadius && p.y <= size.height + pinRadius
                if (inside) spot to p else null
            }
            visiblePins.forEach { (_, p) ->
                drawCircle(Color.Black.copy(alpha = 0.35f), pinRadius + 3.dp.toPx(), p + Offset(0f, 2.dp.toPx()))
                drawCircle(accent, pinRadius, p)
                drawCircle(Color.White, pinRadius, p, style = Stroke(2.dp.toPx()))
                drawLabel(textMeasurer, category.emoji, p, Color.White, 13.sp)
            }

            // 이름 라벨: 가까운 곳부터, 현재 위치 마커·핀·다른 라벨과 겹치지 않는 자리에만 표시
            val occupied = mutableListOf(Rect(center = geo.center, radius = 30.dp.toPx()))
            visiblePins.forEach { (_, p) -> occupied += Rect(center = p, radius = pinRadius + 2.dp.toPx()) }
            val labelStyle = TextStyle(color = HaegopaColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            val padX = 6.dp.toPx()
            val padY = 2.dp.toPx()
            val gap = 4.dp.toPx()
            var labeled = 0
            for ((spot, p) in visiblePins) {
                if (labeled >= MAX_PIN_LABELS) break
                val layout = textMeasurer.measure(spot.name, labelStyle)
                val w = layout.size.width + padX * 2
                val h = layout.size.height + padY * 2
                val candidates = listOf(
                    Offset(p.x - w / 2, p.y + pinRadius + gap), // 아래
                    Offset(p.x - w / 2, p.y - pinRadius - gap - h), // 위
                    Offset(p.x + pinRadius + gap, p.y - h / 2), // 오른쪽
                    Offset(p.x - pinRadius - gap - w, p.y - h / 2), // 왼쪽
                )
                val spotRect = candidates
                    .map { Rect(it, Size(w, h)) }
                    .firstOrNull { rect ->
                        rect.left >= 0f && rect.top >= 0f && rect.right <= size.width && rect.bottom <= size.height &&
                            occupied.none { it.overlaps(rect) }
                    } ?: continue
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.6f),
                    topLeft = spotRect.topLeft,
                    size = spotRect.size,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
                drawText(layout, topLeft = Offset(spotRect.left + padX, spotRect.top + padY))
                occupied += spotRect
                labeled += 1
            }

            // 경유지 / 목적지 마커
            val markerRadius = 17.dp.toPx()
            waypoints.forEach { waypoint ->
                val p = geo.project(origin, waypoint.location)
                if (waypoint.kind == WaypointKind.DESTINATION) {
                    drawCircle(HaegopaColors.Danger, markerRadius, p)
                    drawLabel(textMeasurer, "🏁", p, Color.White, 14.sp)
                } else {
                    drawCircle(HaegopaColors.Booster, markerRadius, p)
                    drawCircle(HaegopaColors.Background, markerRadius, p, style = Stroke(3.dp.toPx()))
                    drawLabel(textMeasurer, "${waypoint.order}", p, HaegopaColors.Background, 15.sp, FontWeight.ExtraBold)
                }
            }
        }

        // ── 동적 레이어 ──
        Canvas(Modifier.fillMaxSize()) {
            val geo = MapGeometry(size.width, size.height)
            if (moving) {
                drawPath(
                    path = routePath(geo, origin, waypoints),
                    color = Color.White.copy(alpha = 0.6f),
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(
                            intervals = floatArrayOf(10.dp.toPx(), 14.dp.toPx()),
                            phase = -travel,
                        ),
                    ),
                )
            }
            val halo = 18.dp.toPx() + pulse * 26.dp.toPx()
            drawCircle(HaegopaColors.Route.copy(alpha = (1f - pulse) * 0.35f), halo, geo.center)
            drawCircle(Color.White, 14.dp.toPx(), geo.center)
            val s = 9.dp.toPx()
            val arrow = Path().apply {
                moveTo(geo.center.x, geo.center.y - s)
                lineTo(geo.center.x + s * 0.8f, geo.center.y + s * 0.8f)
                lineTo(geo.center.x, geo.center.y + s * 0.35f)
                lineTo(geo.center.x - s * 0.8f, geo.center.y + s * 0.8f)
                close()
            }
            drawPath(arrow, HaegopaColors.Route)
        }
    }
}

/** 화면 크기 → 투영 파라미터 (현재 위치는 화면 가로 중앙 · 세로 62% 지점, 2km 원이 화면에 들어오도록) */
private class MapGeometry(val width: Float, val height: Float) {
    val center = Offset(width * 0.5f, height * 0.62f)
    val radiusPx = min(width, height) * 0.40f
    private val pxPerMeter = radiusPx / RADIUS_METERS

    fun project(origin: GeoPoint, point: GeoPoint): Offset {
        val (east, north) = GeoUtils.toLocalMeters(origin, point)
        return Offset(center.x + (east * pxPerMeter).toFloat(), center.y - (north * pxPerMeter).toFloat())
    }
}

private fun routePath(geo: MapGeometry, origin: GeoPoint, waypoints: List<Waypoint>): Path {
    val stops = waypoints.filter { it.kind == WaypointKind.WAYPOINT }.sortedBy { it.order }.map { geo.project(origin, it.location) }
    val destination = waypoints.firstOrNull { it.kind == WaypointKind.DESTINATION }?.let { geo.project(origin, it.location) }

    val points = mutableListOf(geo.center)
    if (stops.isEmpty() && destination == null) {
        // 가상 경로: 북쪽으로 직진 후 우회전, 다시 북쪽 (목적지는 화면 밖)
        points += Offset(geo.center.x, geo.center.y - geo.height * 0.28f)
        points += Offset(geo.center.x + geo.width * 0.14f, geo.center.y - geo.height * 0.28f)
        points += Offset(geo.center.x + geo.width * 0.14f, -geo.height * 0.1f)
    } else {
        points += stops
        points += destination ?: Offset(points.last().x, -geo.height * 0.1f)
    }

    // 도로처럼 보이도록 세로 → 가로 꺾은선으로 연결
    val path = Path()
    path.moveTo(points.first().x, points.first().y)
    var current = points.first()
    for (next in points.drop(1)) {
        path.lineTo(current.x, next.y)
        path.lineTo(next.x, next.y)
        current = next
    }
    return path
}

private fun DrawScope.drawRoute(path: Path) {
    drawPath(
        path = path,
        color = HaegopaColors.Route.copy(alpha = 0.35f),
        style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = path,
        color = HaegopaColors.Route,
        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawTerrain(geo: MapGeometry) {
    val w = geo.width
    val h = geo.height
    val corner = CornerRadius(18.dp.toPx())
    drawRoundRect(HaegopaColors.MapPark, Offset(w * 0.05f, h * 0.10f), Size(w * 0.18f, h * 0.22f), corner)
    drawRoundRect(HaegopaColors.MapPark, Offset(w * 0.70f, h * 0.52f), Size(w * 0.22f, h * 0.16f), corner)
    drawRoundRect(HaegopaColors.MapPark, Offset(w * 0.60f, h * 0.06f), Size(w * 0.12f, h * 0.12f), corner)
    drawRoundRect(HaegopaColors.MapBlock, Offset(w * 0.28f, h * 0.30f), Size(w * 0.12f, h * 0.14f), corner)
    val river = Path().apply {
        moveTo(-40f, h * 0.84f)
        cubicTo(w * 0.25f, h * 0.72f, w * 0.45f, h * 0.97f, w * 0.70f, h * 0.86f)
        cubicTo(w * 0.85f, h * 0.80f, w * 0.95f, h * 0.90f, w + 40f, h * 0.82f)
    }
    drawPath(river, HaegopaColors.MapWater, style = Stroke(width = 46.dp.toPx(), cap = StrokeCap.Round))
}

private fun DrawScope.drawRoads(geo: MapGeometry) {
    val w = geo.width
    val h = geo.height
    val spacing = 88.dp.toPx()
    val minor = 5.dp.toPx()
    var x = geo.center.x % spacing
    while (x < w) {
        drawLine(HaegopaColors.MapRoad, Offset(x, 0f), Offset(x, h), strokeWidth = minor)
        x += spacing
    }
    var y = geo.center.y % spacing
    while (y < h) {
        drawLine(HaegopaColors.MapRoad, Offset(0f, y), Offset(w, y), strokeWidth = minor)
        y += spacing
    }
    val major = 12.dp.toPx()
    drawLine(HaegopaColors.MapRoadMajor, Offset(geo.center.x, 0f), Offset(geo.center.x, h), strokeWidth = major)
    drawLine(HaegopaColors.MapRoadMajor, Offset(0f, geo.center.y - spacing * 2), Offset(w, geo.center.y - spacing * 2), strokeWidth = major)
    drawLine(HaegopaColors.MapRoadMajor, Offset(0f, h * 0.18f), Offset(w, h * 0.56f), strokeWidth = major * 0.8f)
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    color: Color,
    fontSize: TextUnit,
    weight: FontWeight = FontWeight.SemiBold,
    background: Color? = null,
) {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = fontSize, fontWeight = weight))
    val topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
    if (background != null) {
        val padX = 6.dp.toPx()
        val padY = 2.dp.toPx()
        drawRoundRect(
            color = background,
            topLeft = Offset(topLeft.x - padX, topLeft.y - padY),
            size = Size(layout.size.width + padX * 2, layout.size.height + padY * 2),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )
    }
    drawText(layout, topLeft = topLeft)
}
