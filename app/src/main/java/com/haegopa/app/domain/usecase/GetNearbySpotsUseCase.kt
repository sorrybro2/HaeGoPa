package com.haegopa.app.domain.usecase

import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.domain.model.SearchRadius
import com.haegopa.app.domain.model.SpotCategory
import com.haegopa.app.domain.model.SpotPage
import com.haegopa.app.domain.repository.SpotRepository

/** 선택한 반경(기본 2km) · 거리 오름차순 · 페이지 단위(무한 스크롤) 스팟 조회 */
class GetNearbySpotsUseCase(private val repository: SpotRepository) {
    suspend operator fun invoke(
        category: SpotCategory,
        origin: GeoPoint,
        page: Int,
        pageSize: Int = PAGE_SIZE,
        radiusMeters: Int = SearchRadius.DEFAULT_METERS,
    ): SpotPage = repository.getNearbySpots(
        category = category,
        origin = origin,
        page = page,
        pageSize = pageSize,
        radiusMeters = radiusMeters,
    )

    companion object {
        const val PAGE_SIZE = 5
    }
}
