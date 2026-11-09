package com.haegopa.app.domain.usecase

import com.haegopa.app.domain.model.Spot
import com.haegopa.app.sdk.NaviHelperGateway
import com.haegopa.app.sdk.WaypointResult

/** 원터치 목적지 안내: 카드의 [목적지] → 순정 내비(NaviHelper.requestRoute)로 이 스팟까지 길안내 */
class SetDestinationUseCase(private val naviHelper: NaviHelperGateway) {

    operator fun invoke(spot: Spot): WaypointResult = naviHelper.setDestinationDetailed(
        name = spot.name,
        latitude = spot.location.latitude,
        longitude = spot.location.longitude,
    )
}
