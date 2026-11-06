package com.haegopa.app.domain.usecase

import com.haegopa.app.domain.model.Spot
import com.haegopa.app.sdk.NaviHelperGateway
import com.haegopa.app.sdk.WaypointResult

/** 원터치 경유지 주입: 카드의 [경유지 추가] → 순정 내비(NaviHelper) 경유지 등록 */
class AddWaypointUseCase(private val naviHelper: NaviHelperGateway) {

    operator fun invoke(spot: Spot): WaypointResult = naviHelper.addWaypointDetailed(
        name = spot.name,
        latitude = spot.location.latitude,
        longitude = spot.location.longitude,
    )
}
