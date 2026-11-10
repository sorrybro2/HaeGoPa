package com.haegopa.app.domain.util

import com.haegopa.app.domain.model.GeoPoint
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** 하버사인 거리(m) */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** origin 에서 bearing(북=0°, 시계방향) 방향으로 distance(m) 떨어진 좌표 */
    fun offset(origin: GeoPoint, distanceMeters: Double, bearingDegrees: Double): GeoPoint {
        val angular = distanceMeters / EARTH_RADIUS_M
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(origin.latitude)
        val lng1 = Math.toRadians(origin.longitude)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lng2 = lng1 + atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2),
        )
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lng2))
    }

    /** [toLocalMeters] 의 역: origin 에서 동쪽 [east] m · 북쪽 [north] m 지점 */
    fun fromLocalMeters(origin: GeoPoint, east: Double, north: Double): GeoPoint = GeoPoint(
        latitude = origin.latitude + north / 110_540.0,
        longitude = origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))),
    )

    /** from → to 방위각 (북=0°, 시계방향, 0..360) */
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val (east, north) = toLocalMeters(from, to)
        return (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
    }

    /** 지도 투영용: origin 기준 (동쪽 m, 북쪽 m) */
    fun toLocalMeters(origin: GeoPoint, point: GeoPoint): Pair<Double, Double> {
        val north = (point.latitude - origin.latitude) * 110_540.0
        val east = (point.longitude - origin.longitude) * 111_320.0 * cos(Math.toRadians(origin.latitude))
        return east to north
    }

    /** 320m / 1.4km */
    fun formatDistance(meters: Int): String =
        if (meters < 1000) "${meters}m" else String.format(Locale.US, "%.1fkm", meters / 1000.0)

    /** TTS 낭독용: 320미터 / 1.4킬로미터 */
    fun spokenDistance(meters: Int): String =
        if (meters < 1000) "${meters}미터" else String.format(Locale.US, "%.1f킬로미터", meters / 1000.0)

    /** 검색 반경 표기: 2km / 7.5km (정수 km 는 소수점 없이) */
    fun formatRadius(meters: Int): String =
        if (meters % 1000 == 0) "${meters / 1000}km" else String.format(Locale.US, "%.1fkm", meters / 1000.0)

    /** 검색 반경 낭독: 2킬로미터 / 7.5킬로미터 */
    fun spokenRadius(meters: Int): String =
        if (meters % 1000 == 0) "${meters / 1000}킬로미터" else String.format(Locale.US, "%.1f킬로미터", meters / 1000.0)

    /** 도심 평균 속도 기준 예상 소요 시간(분, 최소 1분) */
    fun etaMinutes(distanceMeters: Int, avgSpeedKmh: Double = 24.0): Int =
        max(1, ceil(distanceMeters / (avgSpeedKmh * 1000.0 / 60.0)).toInt())

    /** 방위각 → 8방위 한국어 */
    fun bearingToKorean(bearingDegrees: Double): String {
        val names = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
        val normalized = ((bearingDegrees % 360) + 360) % 360
        return names[((normalized + 22.5) / 45.0).toInt() % 8]
    }
}
