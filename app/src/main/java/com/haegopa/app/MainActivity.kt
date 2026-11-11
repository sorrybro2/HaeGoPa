package com.haegopa.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.haegopa.app.presentation.MainViewModel
import com.haegopa.app.sdk.car.CarApiAvailability
import com.haegopa.app.sdk.pleos.PleosLocationGateway
import com.haegopa.app.ui.MainCarScreen
import com.haegopa.app.ui.theme.HaegopaTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as HaegopaApplication).container }

    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(container) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { container.onRuntimePermissionsUpdated() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissions()
        setContent {
            HaegopaTheme {
                MainCarScreen(viewModel = viewModel)
            }
        }
    }

    // 순정 내비가 같은 자리를 덮으면 onStop → 그때부터 음성 코파일럿이 말을 건다
    override fun onStart() {
        super.onStart()
        container.onAppVisibilityChanged(true)
    }

    override fun onStop() {
        container.onAppVisibilityChanged(false)
        super.onStop()
    }

    /**
     * - android.car.permission.CAR_SPEED : VHAL 차속 (Pleos 에뮬레이터/실차)
     * - pleos.car.permission.CAR_DRIVE/INFO : Pleos Vehicle SDK 기어·주행 시간
     * - pleos.car.permission.FUSED_LOCATION : Pleos FusedLocation 차 현재 위치 (검색 반경의 중심)
     * - RECORD_AUDIO : 음성 코파일럿이 "응 / 아니" 대답을 들을 때 (거부하면 알림 버튼으로만 고른다).
     *   공모전 베타는 음성 대답을 꺼 두므로(BuildConfig.COPILOT_VOICE_REPLY = false) 묻지 않는다
     * - POST_NOTIFICATIONS (Android 13+) : 순정 내비 위 헤드업 제안 알림
     * 정의되지 않은 권한(일반 AVD의 car 권한)은 시스템이 자동 거부하므로 안전하다.
     */
    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add(CarApiAvailability.CAR_SPEED_PERMISSION)
            add("pleos.car.permission.CAR_DRIVE")
            add("pleos.car.permission.CAR_INFO")
            add(PleosLocationGateway.PERMISSION)
            if (BuildConfig.COPILOT_VOICE_REPLY) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            container.onRuntimePermissionsUpdated()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
