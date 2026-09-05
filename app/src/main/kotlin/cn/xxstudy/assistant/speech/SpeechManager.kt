package cn.xxstudy.assistant.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import cn.xxstudy.assistant.data.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.Locale

/**
 * [SpeechManager]
 * 语音调度中枢：
 * 1. ASR（语音识别）：由底层的 [SenseVoiceAsrEngine] 纯端侧离线驱动，彻底摆脱系统 SpeechRecognizer 限制。
 * 2. TTS（语音播报）：由轻量极速的 [VitsTtsEngine] + [TtsAudioTrackPlayer] 驱动（38MB VITS INT8，30~50ms 极速响应，标准清晰普通话），
 *    当离线 TTS 尚未就绪时自动无缝降级至系统 [TextToSpeech]。
 * 3. 全双工打断（Barge-in）：用户开口时立即打断当前语音播报并清空音频队列。
 */
class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechManager"
    }

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- 端侧离线 ASR 引擎 ---
    private val asrEngine = SenseVoiceAsrEngine(context)

    val isListening: StateFlow<Boolean> = asrEngine.isListening
    val listeningRms: StateFlow<Float> = asrEngine.listeningRms

    // --- VITS-TTS 离线引擎与播放器 ---
    private val vitsEngine = VitsTtsEngine(context)
    private val ttsPlayer = TtsAudioTrackPlayer(sampleRate = 44100)

    val isVitsTtsReady: StateFlow<Boolean> = vitsEngine.isReady
    val isVitsGenerating: StateFlow<Boolean> = vitsEngine.isGenerating
    val isBilingualTts: StateFlow<Boolean> = vitsEngine.isBilingual
    val ttsNumSpeakers: StateFlow<Int> = vitsEngine.numSpeakers

    // 兼容旧属性命名
    val isMossTtsReady: StateFlow<Boolean> = isVitsTtsReady
    val isMossGenerating: StateFlow<Boolean> = isVitsGenerating

    // --- 系统 TextToSpeech 降级保底 ---
    private var systemTts: TextToSpeech? = null
    private val _isSystemTtsReady = MutableStateFlow(false)

    // --- 综合 TTS 就绪与播报状态 ---
    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentUtteranceId = MutableStateFlow<String?>(null)
    val currentUtteranceId: StateFlow<String?> = _currentUtteranceId.asStateFlow()

    private var onSpeechDoneCallback: (() -> Unit)? = null
    private var activeSynthesisJob: Job? = null

    init {
        initSystemTtsFallback()
        observeTtsStates()
        observeAppSettings()
    }

    private fun observeAppSettings() {
        managerScope.launch {
            AppSettings.ttsPitch.collect { pitch ->
                ttsPlayer.updatePitch(pitch)
            }
        }
        managerScope.launch {
            AppSettings.asrLanguage.collect { lang ->
                asrEngine.updateLanguage(lang)
            }
        }
    }

    private fun observeTtsStates() {
        managerScope.launch {
            combine(vitsEngine.isReady, _isSystemTtsReady) { vitsReady, sysReady ->
                vitsReady || sysReady
            }.collect { ready ->
                _isTtsReady.value = ready
            }
        }

        managerScope.launch {
            combine(ttsPlayer.isPlaying, vitsEngine.isGenerating) { isPlaying, isGenerating ->
                isPlaying || isGenerating
            }.collect { busy ->
                if (vitsEngine.isReady.value) {
                    _isSpeaking.value = busy
                }
            }
        }
    }

    private fun initSystemTtsFallback() {
        systemTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                systemTts?.let { engine ->
                    val result = engine.setLanguage(Locale.CHINESE)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Chinese TTS language missing, falling back to default locale")
                        engine.language = Locale.getDefault()
                    }
                    _isSystemTtsReady.value = true
                }
            } else {
                Log.e(TAG, "System TextToSpeech init failed with status: $status")
            }
        }

        systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (!vitsEngine.isReady.value) {
                    _isSpeaking.value = true
                    _currentUtteranceId.value = utteranceId
                }
            }

            override fun onDone(utteranceId: String?) {
                if (!vitsEngine.isReady.value) {
                    _isSpeaking.value = false
                    _currentUtteranceId.value = null
                    mainHandler.post { onSpeechDoneCallback?.invoke() }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (!vitsEngine.isReady.value) {
                    _isSpeaking.value = false
                    _currentUtteranceId.value = null
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (!vitsEngine.isReady.value) {
                    _isSpeaking.value = false
                    _currentUtteranceId.value = null
                    Log.e(TAG, "System TTS utterance error: $errorCode for $utteranceId")
                }
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
        // 全双工打断（Barge-in）：用户一旦按住或开口说话，立即打断当前所有发音
        stopSpeaking()

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

    // --- 串行流式单 Worker 调度流水线 (双缓冲) ---
    private var streamingSentenceChannel: Channel<String>? = null
    private var streamingWorkerJob: Job? = null

    @Synchronized
    private fun ensureStreamingWorker(): Channel<String> {
        val existing = streamingSentenceChannel
        if (existing != null && !existing.isClosedForSend && streamingWorkerJob?.isActive == true) {
            return existing
        }

        val channel = Channel<String>(Channel.UNLIMITED)
        streamingSentenceChannel = channel

        streamingWorkerJob = managerScope.launch(Dispatchers.Default) {
            try {
                for (sentence in channel) {
                    if (!isActive) break
                    val rate = AppSettings.ttsSpeechRate.value
                    val sid = AppSettings.ttsSpeakerId.value
                    val pitch = AppSettings.ttsPitch.value
                    ttsPlayer.updatePitch(pitch)
                    vitsEngine.synthesize(sentence, sid = sid, speed = rate) { pcmFloats, sampleRate ->
                        if (isActive) {
                            ttsPlayer.enqueueAudio(pcmFloats, sampleRate)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 正常打断退出
            } catch (e: Exception) {
                Log.e(TAG, "Error in streaming synthesis worker: ${e.message}", e)
            }
        }
        return channel
    }

    /**
     * 手动触发单条消息朗读（支持历史消息点击）
     */
    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        utteranceId: String = System.currentTimeMillis().toString(),
        onDone: (() -> Unit)? = null
    ) {
        if (!_isTtsReady.value) {
            Log.w(TAG, "TTS not ready yet")
            return
        }

        val cleanText = text.replace(Regex("<\\|.*?\\|>"), "").trim()
        if (cleanText.isEmpty()) return

        stopSpeaking()
        onSpeechDoneCallback = onDone
        _currentUtteranceId.value = utteranceId

        val targetPitch = if (pitch != 1.0f) pitch else AppSettings.ttsPitch.value
        ttsPlayer.updatePitch(targetPitch)

        if (vitsEngine.isReady.value) {
            val channel = ensureStreamingWorker()
            val chunker = SentenceChunker { sentence ->
                channel.trySend(sentence)
            }
            chunker.onToken(cleanText)
            chunker.flush()
        } else {
            // 降级使用系统 TTS
            val targetRate = if (speechRate != 1.0f) speechRate else AppSettings.ttsSpeechRate.value
            systemTts?.setSpeechRate(targetRate.coerceIn(0.5f, 2.0f))
            systemTts?.setPitch(targetPitch.coerceIn(0.5f, 2.0f))
            systemTts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    /**
     * 流式切句播放接口：用于大模型边吐字边发音（豆包式无缝流水线）
     */
    fun speakChunk(chunk: String) {
        val cleanChunk = chunk.replace(Regex("<\\|.*?\\|>"), "").trim()
        if (cleanChunk.isEmpty()) return

        if (vitsEngine.isReady.value) {
            val channel = ensureStreamingWorker()
            channel.trySend(cleanChunk)
        } else {
            systemTts?.speak(cleanChunk, TextToSpeech.QUEUE_ADD, null, System.currentTimeMillis().toString())
        }
    }

    /**
     * 创建一个流式标点切句器，用于大模型流式 Token 回调
     */
    fun createSentenceChunker(): SentenceChunker {
        return SentenceChunker { sentence ->
            speakChunk(sentence)
        }
    }

    /**
     * 立即停止发音并打断所有待播放数据（毫秒级全链路刹车）
     */
    fun stopSpeaking() {
        // 1. 关闭并清空待合成 Channel
        streamingSentenceChannel?.close()
        while (streamingSentenceChannel?.tryReceive()?.isSuccess == true) {
            // 抛弃所有滞留分句
        }
        streamingSentenceChannel = null

        // 2. 取消工作协程
        streamingWorkerJob?.cancel()
        streamingWorkerJob = null
        activeSynthesisJob?.cancel()
        activeSynthesisJob = null

        // 3. 立即切断合成
        vitsEngine.cancelCurrent()

        // 4. 立即冲刷 AudioTrack 硬件缓冲与播放队列
        ttsPlayer.interrupt()

        if (systemTts?.isSpeaking == true) {
            systemTts?.stop()
        }

        _isSpeaking.value = false
        _currentUtteranceId.value = null
    }

    fun destroy() {
        managerScope.cancel()
        asrEngine.destroy()
        stopSpeaking()
        ttsPlayer.close()
        vitsEngine.close()
        systemTts?.shutdown()
        systemTts = null
    }
}
