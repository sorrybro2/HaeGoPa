package com.haegopa.app.sdk.mock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.haegopa.app.sdk.SdkBackend
import com.haegopa.app.sdk.SdkStatus
import com.haegopa.app.sdk.SpeechCaption
import com.haegopa.app.sdk.SpeechGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 음성 Mock(폴백): Android 기본 TTS + Android SpeechRecognizer.
 * 엔진이 없으면 "자막 모드"(화면 자막만) / "음성 응답 시뮬레이션"(빈 결과 → 시뮬레이션 버튼 안내)으로 동작한다.
 */
class MockSpeechHelperImpl(context: Context) : SpeechGateway {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captionSeq = AtomicLong(0)

    private val _ttsStatus = MutableStateFlow(SdkStatus("Android TTS", SdkBackend.CONNECTING, "초기화 중"))
    override val ttsStatus: StateFlow<SdkStatus> = _ttsStatus.asStateFlow()

    private val _sttStatus = MutableStateFlow(initialSttStatus())
    override val sttStatus: StateFlow<SdkStatus> = _sttStatus.asStateFlow()

    private val _caption = MutableStateFlow<SpeechCaption?>(null)
    override val caption: StateFlow<SpeechCaption?> = _caption.asStateFlow()

    @Volatile
    private var ttsReady = false

    @Volatile
    private var pendingScript: String? = null

    /** speakThen 완료 대기: (토큰, 콜백). 새 문장을 읽으면 비운다 */
    private val doneSeq = AtomicLong(0)

    @Volatile
    private var pendingDone: Pair<Long, () -> Unit>? = null

    /** 듣기 요청 토큰. stopListening 이나 새 요청이 오면 바뀌어, 이전 요청의 결과는 버린다 */
    private val listenSeq = AtomicLong(0)

    private val tts: TextToSpeech? = try {
        TextToSpeech(appContext) { status -> onTtsInitialized(status) }
    } catch (t: Throwable) {
        Log.w(TAG, "Android TTS unavailable", t)
        _ttsStatus.value = SdkStatus("자막 모드", SdkBackend.MOCK, "TTS 엔진 없음")
        null
    }

    private var recognizer: SpeechRecognizer? = null

    private fun onTtsInitialized(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            _ttsStatus.value = SdkStatus("자막 모드", SdkBackend.MOCK, "TTS 엔진 없음")
            return
        }
        val result = engine.setLanguage(Locale.KOREAN)
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = onUtteranceFinished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onUtteranceFinished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceFinished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = onUtteranceFinished(utteranceId)
        })
        _ttsStatus.value = if (ttsReady) {
            SdkStatus("Android TTS", SdkBackend.ANDROID, "ko-KR")
        } else {
            SdkStatus("자막 모드", SdkBackend.MOCK, "한국어 음성 데이터 없음")
        }
        pendingScript?.let { script ->
            pendingScript = null
            if (ttsReady) speakNow(script, utteranceId())
        }
    }

    /** 화면 자막만 갱신 (Pleos TTS 가 낭독을 맡을 때 사용) */
    fun publishCaption(script: String) {
        _caption.value = SpeechCaption(script, captionSeq.incrementAndGet())
    }

    override fun speakText(script: String) {
        pendingDone = null
        publishCaption(script)
        if (ttsReady) speakNow(script, utteranceId()) else pendingScript = script
    }

    override fun speakThen(script: String, onDone: () -> Unit) {
        val token = doneSeq.incrementAndGet()
        pendingDone = token to onDone
        publishCaption(script)
        val speaking = ttsReady
        if (speaking) speakNow(script, "$DONE_PREFIX$token") else pendingScript = null
        // 엔진이 없으면(자막 모드) 읽는 데 걸릴 시간만큼 기다린 뒤 완료.
        // 엔진이 있으면 onDone 이 기준이고, 이건 이벤트가 끝내 오지 않을 때만 쓰는 넉넉한 안전장치다.
        val wait = if (speaking) safetyTimeoutMillis(script) else estimatedSpeechMillis(script)
        mainHandler.postDelayed({ fireDone(token) }, wait)
    }

    private fun onUtteranceFinished(utteranceId: String?) {
        val token = utteranceId?.removePrefix(DONE_PREFIX)?.takeIf { it != utteranceId }?.toLongOrNull() ?: return
        fireDone(token)
    }

    /** 토큰이 지금 기다리는 문장과 같을 때 한 번만 부른다 */
    private fun fireDone(token: Long) {
        val pending = pendingDone ?: return
        if (pending.first != token) return
        pendingDone = null
        mainHandler.post(pending.second)
    }

    private fun utteranceId() = "haegopa-${captionSeq.get()}"

    private fun speakNow(script: String, utteranceId: String) {
        runCatching {
            tts?.speak(script, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }.onFailure { Log.w(TAG, "speak failed", it) }
    }

    override fun stopSpeaking() {
        pendingScript = null
        pendingDone = null
        runCatching { tts?.stop() }
    }

    override val canListen: Boolean
        get() = micGranted() && runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)

    override fun startVoiceRecognition(callback: (command: String) -> Unit) {
        val token = listenSeq.incrementAndGet()
        mainHandler.post { startOnMain(token, callback) }
    }

    override fun stopListening() {
        listenSeq.incrementAndGet()
        mainHandler.post { runCatching { recognizer?.cancel() } }
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startOnMain(token: Long, callback: (command: String) -> Unit) {
        if (token != listenSeq.get()) return
        val micGranted = micGranted()
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)
        if (!micGranted || !available) {
            _sttStatus.value = SdkStatus(
                "음성 응답 시뮬레이션",
                SdkBackend.MOCK,
                if (!micGranted) "마이크 권한 없음" else "STT 엔진 없음",
            )
            // 빈 문자열 = 인식 불가 → 호출부가 버튼으로 고르게 안내한다
            mainHandler.postDelayed({ if (token == listenSeq.get()) callback("") }, 400)
            return
        }

        var delivered = false
        fun deliver(text: String) {
            if (delivered || token != listenSeq.get()) return
            delivered = true
            callback(text)
        }

        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also { recognizer = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                Log.d(TAG, "SpeechRecognizer error=$error")
                deliver("")
            }

            override fun onResults(results: Bundle?) {
                val best = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                deliver(best)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        _sttStatus.value = SdkStatus("Android STT", SdkBackend.ANDROID, "ko-KR")
        runCatching { sr.startListening(intent) }.onFailure { deliver("") }
    }

    private fun initialSttStatus(): SdkStatus {
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)
        return if (available) {
            SdkStatus("Android STT", SdkBackend.ANDROID, "ko-KR")
        } else {
            SdkStatus("음성 응답 시뮬레이션", SdkBackend.MOCK, "STT 엔진 없음")
        }
    }

    companion object {
        private const val TAG = "HaegopaSpeechMock"
        private const val DONE_PREFIX = "haegopa-done-"

        /**
         * 읽는 데 걸리는 시간 추정. Pleos TTS(seoyoon)는 글자당 약 190ms
         * (Pleos 에뮬레이터: 공백 뺀 52자 문장이 9.8초) → 글자당 200ms + 0.6초.
         */
        fun estimatedSpeechMillis(script: String): Long =
            600L + script.count { !it.isWhitespace() } * 200L

        /**
         * 완료 이벤트가 오는 엔진에서 쓰는 안전장치 — 추정보다 일찍 끊으면 TTS 끝부분을 STT 가 듣게 되므로
         * 추정의 2배 + 2초를 기다린다 (정상이라면 onDone 이 먼저 온다).
         */
        fun safetyTimeoutMillis(script: String): Long = estimatedSpeechMillis(script) * 2 + 2_000L
    }
}
