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
        _ttsStatus.value = if (ttsReady) {
            SdkStatus("Android TTS", SdkBackend.ANDROID, "ko-KR")
        } else {
            SdkStatus("자막 모드", SdkBackend.MOCK, "한국어 음성 데이터 없음")
        }
        pendingScript?.let { script ->
            pendingScript = null
            if (ttsReady) speakNow(script)
        }
    }

    /** 화면 자막만 갱신 (Pleos TTS 가 낭독을 맡을 때 사용) */
    fun publishCaption(script: String) {
        _caption.value = SpeechCaption(script, captionSeq.incrementAndGet())
    }

    override fun speakText(script: String) {
        publishCaption(script)
        if (ttsReady) speakNow(script) else pendingScript = script
    }

    private fun speakNow(script: String) {
        runCatching {
            tts?.speak(script, TextToSpeech.QUEUE_FLUSH, null, "haegopa-${captionSeq.get()}")
        }.onFailure { Log.w(TAG, "speak failed", it) }
    }

    override fun stopSpeaking() {
        pendingScript = null
        runCatching { tts?.stop() }
    }

    override fun startVoiceRecognition(callback: (command: String) -> Unit) {
        mainHandler.post { startOnMain(callback) }
    }

    private fun startOnMain(callback: (command: String) -> Unit) {
        val micGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)
        if (!micGranted || !available) {
            _sttStatus.value = SdkStatus(
                "음성 응답 시뮬레이션",
                SdkBackend.MOCK,
                if (!micGranted) "마이크 권한 없음" else "STT 엔진 없음",
            )
            // 빈 문자열 = 인식 불가 → 호출부(ViewModel)가 시뮬레이션 버튼을 안내한다
            mainHandler.postDelayed({ callback("") }, 400)
            return
        }

        var delivered = false
        fun deliver(text: String) {
            if (delivered) return
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

    private companion object {
        const val TAG = "HaegopaSpeechMock"
    }
}
