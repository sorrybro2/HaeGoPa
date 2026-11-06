package com.haegopa.app.sdk.pleos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.sdk.SpeechCaption
import com.haegopa.app.sdk.SpeechGateway
import com.haegopa.app.sdk.mock.MockSpeechHelperImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import ai.pleos.playground.stt.SpeechToText as PleosSpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener as SttResultListener
import ai.pleos.playground.tts.TextToSpeech as PleosTextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * Pleos TextToSpeech / SpeechToText SDK(v3.3.1, ON_DEVICE · ko-KR) 어댑터.
 * 초기화에 실패하면(일반 AVD, 엔진 미탑재) Android TTS / SpeechRecognizer / 시뮬레이션으로 폴백한다.
 */
class PleosSpeechGateway(context: Context) : SpeechGateway {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fallback = MockSpeechHelperImpl(appContext)

    private val pleosTtsStatus = MutableStateFlow<SdkStatus?>(null)
    private val pleosSttStatus = MutableStateFlow<SdkStatus?>(null)

    override val ttsStatus: StateFlow<SdkStatus> =
        combine(pleosTtsStatus, fallback.ttsStatus) { pleos, android -> pleos ?: android }
            .stateIn(scope, SharingStarted.Eagerly, CONNECTING_STATUS)

    override val sttStatus: StateFlow<SdkStatus> =
        combine(pleosSttStatus, fallback.sttStatus) { pleos, android -> pleos ?: android }
            .stateIn(scope, SharingStarted.Eagerly, CONNECTING_STATUS)

    override val caption: StateFlow<SpeechCaption?> = fallback.caption

    @Volatile
    private var pleosTts: PleosTextToSpeech? = null

    @Volatile
    private var pleosTtsReady = false

    @Volatile
    private var pleosStt: PleosSpeechToText? = null

    @Volatile
    private var pleosSttReady = false

    private val pendingSttCallback = AtomicReference<((String) -> Unit)?>(null)

    @Volatile
    private var lastPartial = ""

    // ── TTS ────────────────────────────────────────────────────────────────────

    private fun initPleosTts() {
        try {
            val engine = PleosTextToSpeech(appContext, TtsMode.ON_DEVICE)
            engine.addEventListener(object : TtsEventListener {
                override fun onStart() = Unit
                override fun onReady() = Unit
                override fun onDone() = Unit
                override fun onStop() = Unit
                override fun onUpdatedRms(rms: Double) = Unit
                override fun onError(errorType: TtsErrorType) {
                    Log.w(TAG, "Pleos TTS error: $errorType")
                }
            })
            engine.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    runCatching { engine.setLocale(Locale.KOREA) }
                    pleosTtsReady = true
                    pleosTtsStatus.value = SdkStatus("Pleos TextToSpeech", SdkBackend.PLEOS, "ON_DEVICE · ko-KR")
                }

                override fun onFailure(errorType: TtsErrorType) {
                    Log.i(TAG, "Pleos TTS init failed: $errorType → Android TTS")
                    pleosTtsReady = false
                    pleosTtsStatus.value = null
                }
            })
            pleosTts = engine
        } catch (t: Throwable) {
            Log.i(TAG, "Pleos TTS unavailable → Android TTS", t)
        }
    }

    override fun speakText(script: String) {
        val engine = pleosTts
        if (pleosTtsReady && engine != null) {
            fallback.publishCaption(script)
            val spoken = runCatching { engine.speak(script) }.isSuccess
            if (spoken) return
        }
        fallback.speakText(script)
    }

    override fun stopSpeaking() {
        runCatching { pleosTts?.stop() }
        fallback.stopSpeaking()
    }

    // ── STT ────────────────────────────────────────────────────────────────────

    private val sttListener = object : SttResultListener {
        override fun onUpdated(stt: String, completed: Boolean) {
            lastPartial = stt
            if (completed) deliver(stt)
        }

        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit

        override fun onEndedRecognition() {
            deliver(lastPartial)
        }

        override fun onError(errorType: SttErrorType) {
            Log.w(TAG, "Pleos STT error: $errorType")
            deliver("")
        }
    }

    // 주의: Kotlin 은 선언 순서대로 초기화하므로, sttListener 등 모든 프로퍼티 선언 뒤에 init 을 둔다.
    init {
        // Pleos 음성(CaaS) 서비스가 없는 기기에서는 SDK 를 만들지 않고 Android TTS/STT 로 동작
        if (PleosServiceProbe.isSpeechServiceAvailable(appContext)) {
            initPleosTts()
            initPleosStt()
        }
    }

    private fun initPleosStt() {
        try {
            val engine = PleosSpeechToText(appContext, SttMode.ON_DEVICE)
            engine.addListener(sttListener)
            engine.initialize(object : SttInitListener {
                override fun onSuccess() {
                    runCatching { engine.setLocale(Locale.KOREA) }
                    pleosSttReady = true
                    pleosSttStatus.value = SdkStatus("Pleos SpeechToText", SdkBackend.PLEOS, "ON_DEVICE · ko-KR")
                }

                override fun onFailure(errorType: SttErrorType) {
                    Log.i(TAG, "Pleos STT init failed: $errorType → fallback")
                    pleosSttReady = false
                    pleosSttStatus.value = null
                }
            })
            pleosStt = engine
        } catch (t: Throwable) {
            Log.i(TAG, "Pleos STT unavailable → fallback", t)
        }
    }

    override fun startVoiceRecognition(callback: (command: String) -> Unit) {
        val engine = pleosStt
        val micGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (pleosSttReady && engine != null && micGranted) {
            lastPartial = ""
            pendingSttCallback.set(callback)
            val started = runCatching { engine.request() }.isSuccess
            if (started) {
                // 응답이 오지 않는 경우를 대비한 안전 타임아웃
                mainHandler.postDelayed({ deliver(lastPartial) }, STT_TIMEOUT_MS)
                return
            }
            pendingSttCallback.set(null)
        }
        fallback.startVoiceRecognition(callback)
    }

    private fun deliver(text: String) {
        val callback = pendingSttCallback.getAndSet(null) ?: return
        mainHandler.post { callback(text) }
    }

    private companion object {
        const val TAG = "PleosSpeech"
        const val STT_TIMEOUT_MS = 8_000L
        val CONNECTING_STATUS = SdkStatus("음성 엔진", SdkBackend.CONNECTING, "초기화 중")
    }
}
