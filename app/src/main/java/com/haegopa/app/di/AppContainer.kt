package com.haegopa.app.di

import android.content.Context
import com.haegopa.app.BuildConfig
import com.haegopa.app.copilot.CopilotConfig
import com.haegopa.app.copilot.CopilotEngine
import com.haegopa.app.copilot.CopilotNotifications
import com.haegopa.app.copilot.CopilotService
import com.haegopa.app.copilot.PrefsCopilotSettings
import com.haegopa.app.data.MockDataRepository
import com.haegopa.app.domain.repository.SpotRepository
import com.haegopa.app.domain.usecase.AddWaypointUseCase
import com.haegopa.app.domain.usecase.GetNearbySpotsUseCase
import com.haegopa.app.domain.usecase.SetDestinationUseCase
import com.haegopa.app.sdk.LocationGateway
import com.haegopa.app.sdk.NaviHelperGateway
import com.haegopa.app.sdk.SpeechGateway
import com.haegopa.app.sdk.VehicleGateway
import com.haegopa.app.sdk.pleos.PleosLocationGateway
import com.haegopa.app.sdk.pleos.PleosNaviHelperGateway
import com.haegopa.app.sdk.pleos.PleosSpeechGateway
import com.haegopa.app.sdk.pleos.PleosVehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 수동 DI 컨테이너 (앱 단일 인스턴스).
 * Pleos 게이트웨이는 Pleos Connect 환경이면 실제 SDK, 아니면 내장 Mock 으로 스스로 폴백한다.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** 화면(ViewModel)보다 오래 사는 작업 — 순정 내비가 앞에 있어도 도는 음성 코파일럿 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val naviHelper: NaviHelperGateway by lazy { PleosNaviHelperGateway(appContext) }
    val vehicleManager: VehicleGateway by lazy { PleosVehicleGateway(appContext) }
    val speechHelper: SpeechGateway by lazy { PleosSpeechGateway(appContext) }
    val location: LocationGateway by lazy { PleosLocationGateway(appContext) }

    val spotRepository: SpotRepository by lazy { MockDataRepository() }

    val getNearbySpots: GetNearbySpotsUseCase by lazy { GetNearbySpotsUseCase(spotRepository) }
    val addWaypoint: AddWaypointUseCase by lazy { AddWaypointUseCase(naviHelper) }
    val setDestination: SetDestinationUseCase by lazy { SetDestinationUseCase(naviHelper) }

    private val _appVisible = MutableStateFlow(false)

    /** 해고파 화면이 앞에 보이는지 (Pleos 에서 순정 내비가 같은 자리를 덮으면 false) */
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    val copilotNotifications: CopilotNotifications by lazy { CopilotNotifications(appContext) }

    val copilot: CopilotEngine by lazy {
        CopilotEngine(
            scope = appScope,
            getNearbySpots = getNearbySpots,
            addWaypoint = addWaypoint,
            setDestination = setDestination,
            naviHelper = naviHelper,
            vehicle = vehicleManager,
            speech = speechHelper,
            appVisible = appVisible,
            settings = PrefsCopilotSettings(appContext),
            config = CopilotConfig(voiceReply = BuildConfig.COPILOT_VOICE_REPLY),
        ).also { it.presenter = copilotNotifications }
    }

    /** 런타임 권한(CAR_SPEED 등) 응답 후 차량 센서 재연결 */
    fun onRuntimePermissionsUpdated() {
        vehicleManager.refreshSensors()
        location.refresh()
    }

    fun onAppVisibilityChanged(visible: Boolean) {
        _appVisible.value = visible
        // 포그라운드 서비스는 화면이 보일 때만 시작할 수 있다 → 보일 때마다 맞춰 둔다
        if (visible) syncCopilotService()
    }

    fun setCopilotEnabled(enabled: Boolean) {
        copilot.setEnabled(enabled)
        syncCopilotService()
    }

    private fun syncCopilotService() {
        if (copilot.state.value.enabled) CopilotService.start(appContext) else CopilotService.stop(appContext)
    }
}
