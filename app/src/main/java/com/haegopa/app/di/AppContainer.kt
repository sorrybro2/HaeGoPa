package com.haegopa.app.di

import android.content.Context
import com.haegopa.app.audio.MrSynthPlayer
import com.haegopa.app.data.MockDataRepository
import com.haegopa.app.domain.repository.SpotRepository
import com.haegopa.app.domain.usecase.AddWaypointUseCase
import com.haegopa.app.domain.usecase.GetNearbySpotsUseCase
import com.haegopa.app.sdk.NaviHelperGateway
import com.haegopa.app.sdk.SpeechGateway
import com.haegopa.app.sdk.VehicleGateway
import com.haegopa.app.sdk.pleos.PleosNaviHelperGateway
import com.haegopa.app.sdk.pleos.PleosSpeechGateway
import com.haegopa.app.sdk.pleos.PleosVehicleGateway

/**
 * 수동 DI 컨테이너 (앱 단일 인스턴스).
 * Pleos 게이트웨이는 Pleos Connect 환경이면 실제 SDK, 아니면 내장 Mock 으로 스스로 폴백한다.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val naviHelper: NaviHelperGateway by lazy { PleosNaviHelperGateway(appContext) }
    val vehicleManager: VehicleGateway by lazy { PleosVehicleGateway(appContext) }
    val speechHelper: SpeechGateway by lazy { PleosSpeechGateway(appContext) }

    val spotRepository: SpotRepository by lazy { MockDataRepository() }
    val mrPlayer: MrSynthPlayer by lazy { MrSynthPlayer(appContext) }

    val getNearbySpots: GetNearbySpotsUseCase by lazy { GetNearbySpotsUseCase(spotRepository) }
    val addWaypoint: AddWaypointUseCase by lazy { AddWaypointUseCase(naviHelper) }

    /** 런타임 권한(CAR_SPEED 등) 응답 후 차량 센서 재연결 */
    fun onRuntimePermissionsUpdated() {
        vehicleManager.refreshSensors()
    }
}
