package com.haegopa.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.haegopa.app.presentation.MainViewModel
import com.haegopa.app.sdk.car.CarApiAvailability
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

    /**
     * - android.car.permission.CAR_SPEED : VHAL 차속 (Pleos 에뮬레이터/실차)
     * - pleos.car.permission.CAR_DRIVE/INFO : Pleos Vehicle SDK 기어·주행 시간
     * - RECORD_AUDIO : 퀴즈 음성 응답(STT) — 졸음 방지 모드를 켠 빌드에서만 요청
     * 정의되지 않은 권한(일반 AVD의 car 권한)은 시스템이 자동 거부하므로 안전하다.
     */
    private fun requestRuntimePermissions() {
        val wanted = listOfNotNull(
            CarApiAvailability.CAR_SPEED_PERMISSION,
            "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.CAR_INFO",
            Manifest.permission.RECORD_AUDIO.takeIf { FeatureFlags.DROWSINESS_BOOSTER },
        )
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
