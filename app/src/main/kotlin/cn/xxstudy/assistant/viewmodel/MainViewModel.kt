package cn.xxstudy.assistant.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.xxstudy.assistant.data.AppSettings
import cn.xxstudy.assistant.repository.LlamaRepository
import cn.xxstudy.assistant.speech.SpeechManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Int,
    val isUser: Boolean,
    val text: String,
    val isThinking: Boolean = false,
    val metrics: String? = null
)

/**
 * [MainViewModel] (MVVM 架构核心)
 * 纯粹的 UI 状态管理器与业务调度中枢。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LlamaRepository()
    val speechManager = SpeechManager(application)

    private var messageCounter = 0

    // --- UI State 流 ---
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow("尚未启动")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    // 正在朗读的消息 ID
    private val _speakingMessageId = MutableStateFlow<Int?>(null)
    val speakingMessageId: StateFlow<Int?> = _speakingMessageId.asStateFlow()

    // 语音输入实时临时文本
    private val _voicePartialText = MutableStateFlow<String?>(null)
    val voicePartialText: StateFlow<String?> = _voicePartialText.asStateFlow()

    init {
        AppSettings.init(application)
    }

    fun loadLocalModel(absolutePath: String) {
        if (_isModelLoaded.value || _isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "引擎预热中..."
            val success = repository.loadModel(absolutePath)
            _isLoading.value = false
            if (success) {
                _isModelLoaded.value = true
                _statusMessage.value = "在线 (Qwen2.5-0.5B)"
                // 添加一句开场白
                _chatMessages.value = listOf(
                    ChatMessage(messageCounter++, false, "您好！我是部署在您本地终端的 AI，目前引擎已就绪，您的所有数据均不会上传云端。")
                )
                // 启动本地 Ktor 微服务，暴露接口供其他 App 跨进程调用
                cn.xxstudy.assistant.server.LlamaServer.start(repository)
            } else {
                _statusMessage.value = "加载失败"
            }
        }
    }

    fun sendMessage(prompt: String) {
        if (!_isModelLoaded.value || prompt.isBlank()) return

        // 停止之前的朗读
        speechManager.stopSpeaking()
        _speakingMessageId.value = null

        // 1. 插入用户消息
        val userMsg = ChatMessage(messageCounter++, true, prompt)
        // 2. 插入 AI "思考中" 占位
        val thinkingId = messageCounter++
        val thinkingMsg = ChatMessage(thinkingId, false, "...", isThinking = true)

        _chatMessages.value = _chatMessages.value + listOf(userMsg, thinkingMsg)

        viewModelScope.launch {
            var currentResponse = ""

            // 使用无界 Channel 将 C++ 的回调与 UI 渲染彻底解耦，防止 UI 渲染过慢反向阻塞 JNI 推理线程
            val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)

            val uiUpdaterJob = launch {
                // 限流策略：积累 token 批量更新，避免每秒触发几十足次 UI 重组
                var lastUpdateTime = System.currentTimeMillis()
                for (token in channel) {
                    currentResponse += token
                    val now = System.currentTimeMillis()
                    // 每 50ms 更新一次 UI 即可保持视觉流畅度，极大地节省 CPU 资源
                    if (now - lastUpdateTime > 50) {
                        lastUpdateTime = now
                        val newList = _chatMessages.value.toMutableList()
                        val idx = newList.indexOfLast { it.id == thinkingId }
                        if (idx != -1) {
                            newList[idx] = newList[idx].copy(text = currentResponse, isThinking = false)
                            _chatMessages.value = newList
                        }
                    }
                }
            }

            // 调用带有回调的推理方法
            val rawResponse = repository.generateText(prompt) { token ->
                channel.trySend(token)
            }

            // 推理结束，关闭 channel 并等待 UI 刷新完最后一批字符
            channel.close()
            uiUpdaterJob.join()

            // 推理完成后，附加上最终的性能指标
            val parts = rawResponse.split("<|metrics|>")
            val actualText = parts[0]
            val metricsInfo = if (parts.size > 1) {
                try {
                    val j = org.json.JSONObject(parts[1])
                    "首字: ${j.optLong("ttft_ms")} ms | 耗时: ${j.optLong("total_ms")} ms | 速度: ${j.optDouble("speed")} tk/s"
                } catch(e: Exception) { parts[1] }
            } else null

            val finalUpdatedList = _chatMessages.value.map {
                if (it.id == thinkingId) it.copy(text = actualText, isThinking = false, metrics = metricsInfo) else it
            }
            _chatMessages.value = finalUpdatedList

            // 检查设置：如果开启了自动朗读，且当前非空，则自动调用 TTS 播报
            if (AppSettings.ttsAutoPlay.value && actualText.isNotBlank()) {
                speakMessage(thinkingId, actualText)
            }
        }
    }

    // ==========================================
    // TTS 播放控制
    // ==========================================

    fun speakMessage(id: Int, text: String) {
        if (_speakingMessageId.value == id && speechManager.isSpeaking.value) {
            speechManager.stopSpeaking()
            _speakingMessageId.value = null
        } else {
            _speakingMessageId.value = id
            speechManager.speak(
                text = text,
                speechRate = AppSettings.ttsSpeechRate.value,
                pitch = AppSettings.ttsPitch.value,
                utteranceId = id.toString(),
                onDone = {
                    if (_speakingMessageId.value == id) {
                        _speakingMessageId.value = null
                    }
                }
            )
        }
    }

    // ==========================================
    // ASR 语音输入控制
    // ==========================================

    fun startVoiceRecording(onFinalTextReady: (String) -> Unit) {
        _voicePartialText.value = null
        val language = AppSettings.asrLanguage.value
        speechManager.startListening(
            language = language,
            onPartial = { partial ->
                _voicePartialText.value = partial
            },
            onFinal = { finalResult ->
                _voicePartialText.value = null
                onFinalTextReady(finalResult)
                if (AppSettings.asrAutoSend.value && finalResult.isNotBlank()) {
                    sendMessage(finalResult)
                }
            },
            onError = { _ ->
                _voicePartialText.value = null
            }
        )
    }

    fun stopVoiceRecording() {
        speechManager.stopListening()
        _voicePartialText.value = null
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
    }
}
