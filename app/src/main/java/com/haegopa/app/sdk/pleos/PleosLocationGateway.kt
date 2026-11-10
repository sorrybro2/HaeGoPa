package com.haegopa.app.sdk.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.haegopa.app.domain.model.GeoPoint
import com.haegopa.app.sdk.LocationGateway
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SdkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos FusedLocation SDK(v1.1.3) 어댑터 — 차의 현재 위치를 1~2초마다 받는다.
 *
 * 순정 내비(NaviHelper)의 현재 위치는 내비 화면 기준이라 Pleos 에뮬레이터에서는 GPS 를 바꿔도 그대로다
 * (수정구 시흥동 고정, 2026-11-10 확인). 반면 FusedLocation 은 기기 GPS 를 그대로 따라가므로
 * 검색 반경의 중심 · 지도가 따라가는 차 위치는 이 값을 쓴다. 서비스가 없는 기기(일반 AVD)에서는 null.
 */
class PleosLocationGateway(context: Context) : LocationGateway {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(SdkStatus("Pleos FusedLocation", SdkBackend.CONNECTING, "연결 중"))
    override val status: StateFlow<SdkStatus> = _status.asStateFlow()

    private val _location = MutableStateFlow<GeoPoint?>(null)
    override val location: StateFlow<GeoPoint?> = _location.asStateFlow()

    private var fused: FusedLocation? = null
    private var attempts = 0
    private var registered = false

    private val listener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            if (data == null || (data.latitude == 0.0 && data.longitude == 0.0)) return
            _location.value = GeoPoint(data.latitude, data.longitude)
            if (_status.value.backend != SdkBackend.PLEOS) {
                _status.value = SdkStatus("Pleos FusedLocation", SdkBackend.PLEOS, "v1.1.3 · 차 현재 위치")
            }
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "FusedLocation error", e)
        }
    }

    init {
        if (PleosServiceProbe.isFusedLocationServiceAvailable(appContext)) {
            connect()
        } else {
            _status.value = SdkStatus("위치 없음", SdkBackend.MOCK, "Pleos 위치 서비스 미탑재 → 내비 · 가상 위치")
        }
    }

    private fun connect() {
        val sdk = try {
            FusedLocation(appContext).also { it.initialize() }
        } catch (t: Throwable) {
            Log.i(TAG, "FusedLocation unavailable", t)
            _status.value = SdkStatus("위치 없음", SdkBackend.MOCK, "Pleos 위치 연결 실패")
            return
        }
        fused = sdk
        // 서비스 바인딩이 끝나기 전에 등록하면 실패할 수 있어, 조금 기다렸다가 몇 번 다시 시도한다
        mainHandler.postDelayed(::register, REGISTER_DELAY_MS)
    }

    override fun refresh() {
        if (registered || fused == null) return
        attempts = 0
        mainHandler.post(::register)
    }

    private fun register() {
        val sdk = fused ?: return
        if (registered) return
        // 위치 서비스는 런타임 권한(FUSED_LOCATION)이 없으면 SecurityException → 허용 뒤 refresh() 로 다시 온다
        if (ContextCompat.checkSelfPermission(appContext, PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            _status.value = SdkStatus("Pleos FusedLocation", SdkBackend.CONNECTING, "위치 권한 대기")
            return
        }
        attempts += 1
        val ok = runCatching {
            sdk.getLastKnownLocation(listener)
            sdk.registerFusedLocationCallback(listener)
        }.onFailure { Log.w(TAG, "register attempt $attempts failed: ${it.message}") }.isSuccess
        registered = ok
        if (!ok && attempts < MAX_ATTEMPTS) {
            mainHandler.postDelayed(::register, REGISTER_DELAY_MS * attempts)
        } else if (!ok) {
            _status.value = SdkStatus("위치 없음", SdkBackend.MOCK, "Pleos 위치 등록 실패")
        }
    }

    companion object {
        /** Pleos 위치 서비스가 요구하는 런타임 권한 (protectionLevel=dangerous) */
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val TAG = "PleosLocation"
        private const val REGISTER_DELAY_MS = 600L
        private const val MAX_ATTEMPTS = 4
    }
}
