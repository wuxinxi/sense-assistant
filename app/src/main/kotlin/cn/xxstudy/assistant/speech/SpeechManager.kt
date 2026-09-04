package cn.xxstudy.assistant.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * [SpeechManager]
 * 语音调度中枢：
 * 1. ASR（语音识别）：由底层的 [SenseVoiceAsrEngine] 纯端侧离线驱动，彻底摆脱系统 SpeechRecognizer 限制。
 * 2. TTS（语音播报）：目前由 TextToSpeech 驱动，预留无缝升级至 MOSS-TTS。
 */
class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // --- 端侧离线 ASR 引擎 ---
    private val asrEngine = SenseVoiceAsrEngine(context)

    val isListening: StateFlow<Boolean> = asrEngine.isListening
    val listeningRms: StateFlow<Float> = asrEngine.listeningRms

    // --- TTS 状态 ---
    private var tts: TextToSpeech? = null
    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentUtteranceId = MutableStateFlow<String?>(null)
    val currentUtteranceId: StateFlow<String?> = _currentUtteranceId.asStateFlow()

    private var onSpeechDoneCallback: (() -> Unit)? = null

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val result = engine.setLanguage(Locale.CHINESE)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Chinese TTS language missing or not supported, falling back to default locale")
                        engine.language = Locale.getDefault()
                    }
                    _isTtsReady.value = true
                }
            } else {
                Log.e(TAG, "TextToSpeech init failed with status: $status")
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                _currentUtteranceId.value = utteranceId
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                _currentUtteranceId.value = null
                mainHandler.post { onSpeechDoneCallback?.invoke() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                _currentUtteranceId.value = null
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
                _currentUtteranceId.value = null
                Log.e(TAG, "TTS utterance error: $errorCode for $utteranceId")
            }
        })
    }

    // ==========================================
    // 端侧 SenseVoice ASR 语音转文字逻辑
    // ==========================================

    fun startListening(
        language: String = "zh-CN",
        onPartial: (String) -> Unit = {},
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        asrEngine.startListening(
            language = language,
            onPartial = onPartial,
            onFinal = onFinal,
            onError = onError
        )
    }

    fun stopListening() {
        asrEngine.stopListening()
    }

    fun cancelListening() {
        asrEngine.cancelListening()
    }

    // ==========================================
    // TTS 文字转语音播报逻辑
    // ==========================================

    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        utteranceId: String = System.currentTimeMillis().toString(),
        onDone: (() -> Unit)? = null
    ) {
        if (!_isTtsReady.value || tts == null) {
            Log.w(TAG, "TTS not ready yet")
            return
        }

        // 清洗掉模型可能返回的标记（如 <|im_end|> 等）
        val cleanText = text.replace(Regex("<\\|.*?\\|>"), "").trim()
        if (cleanText.isEmpty()) return

        stopSpeaking()

        onSpeechDoneCallback = onDone
        tts?.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        if (_isSpeaking.value) {
            tts?.stop()
            _isSpeaking.value = false
            _currentUtteranceId.value = null
        }
    }

    fun destroy() {
        asrEngine.destroy()
        stopSpeaking()
        tts?.shutdown()
        tts = null
    }
}
