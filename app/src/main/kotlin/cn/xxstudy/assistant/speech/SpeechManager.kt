package cn.xxstudy.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // --- ASR 状态 ---
    private var speechRecognizer: SpeechRecognizer? = null
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _listeningRms = MutableStateFlow(0f)
    val listeningRms: StateFlow<Float> = _listeningRms.asStateFlow()

    private var onPartialResultCallback: ((String) -> Unit)? = null
    private var onFinalResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

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
    // ASR 语音转文字逻辑
    // ==========================================

    fun startListening(
        language: String = "zh-CN",
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("系统无可用语音识别服务，请安装或开启语音引擎")
                return@post
            }

            stopListening()

            onPartialResultCallback = onPartial
            onFinalResultCallback = onFinal
            onErrorCallback = onError

            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _isListening.value = true
                        }

                        override fun onBeginningOfSpeech() {}

                        override fun onRmsChanged(rmsdB: Float) {
                            _listeningRms.value = rmsdB
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            _isListening.value = false
                        }

                        override fun onError(error: Int) {
                            _isListening.value = false
                            _listeningRms.value = 0f
                            val errorMsg = when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                                SpeechRecognizer.ERROR_CLIENT -> "客户端内部错误"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风录音权限"
                                SpeechRecognizer.ERROR_NETWORK -> "网络连接异常"
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                                SpeechRecognizer.ERROR_NO_MATCH -> "未识别到清晰语音"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别器正忙"
                                SpeechRecognizer.ERROR_SERVER -> "服务端错误"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "长时间未检测到说话"
                                else -> "识别错误 (Code $error)"
                            }
                            Log.w(TAG, "Speech recognition error: $errorMsg")
                            onErrorCallback?.invoke(errorMsg)
                        }

                        override fun onResults(results: Bundle?) {
                            _isListening.value = false
                            _listeningRms.value = 0f
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull() ?: ""
                            if (text.isNotEmpty()) {
                                onFinalResultCallback?.invoke(text)
                            } else {
                                onErrorCallback?.invoke("未识别到有效内容")
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            matches?.firstOrNull()?.let { partialText ->
                                if (partialText.isNotEmpty()) {
                                    onPartialResultCallback?.invoke(partialText)
                                }
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                speechRecognizer?.startListening(intent)
                _isListening.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognizer", e)
                _isListening.value = false
                onError("启动语音识别失败: ${e.message}")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recognizer", e)
            } finally {
                speechRecognizer = null
                _isListening.value = false
                _listeningRms.value = 0f
            }
        }
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

        // 清洗掉模型可能返回的标记（如 <|im_end|> 或性能指标信息）
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
        stopListening()
        stopSpeaking()
        tts?.shutdown()
        tts = null
    }
}
