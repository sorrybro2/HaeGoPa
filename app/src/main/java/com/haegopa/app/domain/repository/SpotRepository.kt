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

    companion object {
        const val DEFAULT_RADIUS_METERS = 2_000
    }
}
