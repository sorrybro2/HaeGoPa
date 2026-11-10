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
import java.util.concurrent.atomic.AtomicLong
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
 *
 * 코파일럿용 순서 보장:
 * - [speakThen] : Pleos TTS `onStart` 뒤에 오는 `onDone/onStop` 만 "다 읽음"으로 본다 (앞 문장을 끊으며 오는 onStop 무시)
 * - [startVoiceRecognition] / [stopListening] : 요청마다 토큰을 붙여, 취소되었거나 지난 요청의 결과 · 타임아웃은 버린다
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

    private val listenSeq = AtomicLong(0)

    // ── TTS ────────────────────────────────────────────────────────────────────

    private val doneSeq = AtomicLong(0)

    /** speakThen 완료 대기 (토큰, 콜백) */
    @Volatile
    private var pendingDone: Pair<Long, () -> Unit>? = null

    /** 기다리는 문장이 실제로 읽히기 시작했는지 (onStart 를 받은 뒤의 onDone/onStop 만 인정) */
    @Volatile
    private var pendingStarted = false

    private val ttsEvents = object : TtsEventListener {
        override fun onStart() {
            pendingStarted = true
        }

        override fun onReady() = Unit

        override fun onDone() {
            if (pendingStarted) firePendingDone()
        }

        override fun onStop() {
            if (pendingStarted) firePendingDone()
        }

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onError(errorType: TtsErrorType) {
            Log.w(TAG, "Pleos TTS error: $errorType")
            firePendingDone()
        }
    }

    private fun initPleosTts() {
        try {
            val engine = PleosTextToSpeech(appContext, TtsMode.ON_DEVICE)
            // 주의: Pleos TTS 는 initialize() 성공 전에 addEventListener() 를 부르면 내부 엔진이 없어
            // "lateinit property tts has not been initialized" 로 실패한다 (Pleos 에뮬레이터에서 확인) → 성공 콜백에서 등록
            engine.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    runCatching { engine.addEventListener(ttsEvents) }
                        .onFailure { Log.w(TAG, "Pleos TTS listener registration failed", it) }
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
        pendingDone = null
        val engine = pleosTts
        if (pleosTtsReady && engine != null) {
            fallback.publishCaption(script)
            val spoken = runCatching { engine.speak(script) }.isSuccess
            if (spoken) return
        }
        fallback.speakText(script)
    }

    override fun speakThen(script: String, onDone: () -> Unit) {
        val engine = pleosTts
        if (pleosTtsReady && engine != null) {
            val token = doneSeq.incrementAndGet()
            pendingStarted = false
            pendingDone = token to onDone
            fallback.publishCaption(script)
            if (runCatching { engine.speak(script) }.isSuccess) {
                // Pleos TTS 는 onStart · onDone 을 보내 준다 (에뮬레이터 확인). 이건 이벤트가 끝내 안 올 때만 쓰는 안전장치
                mainHandler.postDelayed(
                    { if (pendingDone?.first == token) firePendingDone() },
                    MockSpeechHelperImpl.safetyTimeoutMillis(script),
                )
                return
            }
            pendingDone = null
        }
        fallback.speakThen(script, onDone)
    }

    private fun firePendingDone() {
        val pending = pendingDone ?: return
        pendingDone = null
        pendingStarted = false
        mainHandler.post(pending.second)
    }

    override fun stopSpeaking() {
        pendingDone = null
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

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 마이크를 허용하지 않았으면 Pleos 음성 서비스가 마이크를 갖고 있어도 듣지 않는다 (사용자 선택 존중) */
    override val canListen: Boolean
        get() = micGranted() && (pleosSttReady || fallback.canListen)

    override fun startVoiceRecognition(callback: (command: String) -> Unit) {
        val engine = pleosStt
        if (pleosSttReady && engine != null && micGranted()) {
            val token = listenSeq.incrementAndGet()
            lastPartial = ""
            pendingSttCallback.set(callback)
            val started = runCatching { engine.request() }
                .onFailure { Log.w(TAG, "Pleos STT request failed", it) }
                .isSuccess
            if (started) {
                // 응답이 오지 않으면 그때까지 들은 말로 끝내고 인식도 멈춘다 (지난 요청의 타임아웃은 토큰으로 무시).
                // Pleos 에뮬레이터: request 뒤 듣기 준비까지 약 2초 → 실제로 듣는 시간은 약 8초
                mainHandler.postDelayed({
                    if (token == listenSeq.get() && pendingSttCallback.get() != null) {
                        deliver(lastPartial)
                        runCatching { engine.stop() }
                    }
                }, STT_TIMEOUT_MS)
                return
            }
            pendingSttCallback.set(null)
        }
        fallback.startVoiceRecognition(callback)
    }

    override fun stopListening() {
        listenSeq.incrementAndGet()
        if (pendingSttCallback.getAndSet(null) != null) runCatching { pleosStt?.stop() }
        fallback.stopListening()
    }

    private fun deliver(text: String) {
        val callback = pendingSttCallback.getAndSet(null) ?: return
        Log.i(TAG, "Pleos STT result: \"$text\"")
        mainHandler.post { callback(text) }
    }

    private companion object {
        const val TAG = "PleosSpeech"
        const val STT_TIMEOUT_MS = 10_000L
        val CONNECTING_STATUS = SdkStatus("음성 엔진", SdkBackend.CONNECTING, "초기화 중")
    }
}
