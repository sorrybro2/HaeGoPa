package com.haegopa.app.domain.repository

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.model.SpotPage

/**
 * 반경 내 스팟을 "가까운 순(오름차순)"으로 페이지 단위 제공하는 포트.
 * 프로토타입은 [com.haegopa.app.data.MockDataRepository], 실서비스는 POI/제휴 API 구현체로 교체한다.
 */
interface SpotRepository {
    suspend fun getNearbySpots(
        category: SpotCategory,
        origin: GeoPoint,
        page: Int,
        pageSize: Int,
        radiusMeters: Int = DEFAULT_RADIUS_METERS,
    ): SpotPage

    /**
     * 가상 스팟을 깔 기준점(출발 위치). 차가 움직여도 스팟이 따라오지 않게 한 번 정해 둔다.
     * 실제 POI 저장소는 좌표가 이미 고정이라 할 일이 없다.
     */
    fun anchorAt(point: GeoPoint) = Unit

    companion object {
        const val DEFAULT_RADIUS_METERS = 2_000
    }
}
