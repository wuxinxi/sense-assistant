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

    // 当前正在进行的推理协程与消息 ID (支持即时打断)
    private var currentGenerationJob: kotlinx.coroutines.Job? = null
    private var currentThinkingId: Int? = null

    fun sendMessage(prompt: String) {
        if (!_isModelLoaded.value || prompt.isBlank()) return

        // 1. 如果上一轮模型还在推理输出，立即打断 C++ 循环并取消旧协程
        if (currentGenerationJob?.isActive == true) {
            repository.stopGeneration()
            currentGenerationJob?.cancel()
            // 将上一条被打断的 AI 消息封口（停止转圈动效）
            currentThinkingId?.let { oldId ->
                val list = _chatMessages.value.map {
                    if (it.id == oldId && it.isThinking) {
                        val finalMsg = if (it.text == "...") "（已打断）" else it.text
                        it.copy(text = finalMsg, isThinking = false)
                    } else it
                }
                _chatMessages.value = list
            }
        }

        // 停止之前的朗读
        speechManager.stopSpeaking()
        _speakingMessageId.value = null

        // 2. 插入用户消息
        val userMsg = ChatMessage(messageCounter++, true, prompt)
        // 3. 插入新 AI "思考中" 占位
        val thinkingId = messageCounter++
        currentThinkingId = thinkingId
        val thinkingMsg = ChatMessage(thinkingId, false, "...", isThinking = true)

        _chatMessages.value = _chatMessages.value + listOf(userMsg, thinkingMsg)

        currentGenerationJob = viewModelScope.launch {
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

            try {
                // 调用带有打断检测的推理方法
                val rawResponse = repository.generateText(prompt) { token ->
                    channel.trySend(token)
                }

                // 推理结束，关闭 channel 并等待 UI 刷新完最后一批字符
                channel.close()
                uiUpdaterJob.join()

                // 推理完成后，附加上最终的性能指标
                val parts = rawResponse.split("<|metrics|>")
                val actualText = if (parts[0].isNotBlank()) parts[0] else currentResponse
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 收到新消息打断时，安全退出
                channel.close()
                uiUpdaterJob.cancel()
            } finally {
                if (currentThinkingId == thinkingId) {
                    currentThinkingId = null
                }
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

    // 麦克风实时输入音量分贝 (供 HUD 波动动效)
    val listeningRms: StateFlow<Float> = speechManager.listeningRms

    // ==========================================
    // ASR 语音输入控制
    // ==========================================

    fun startVoiceRecording(
        autoSend: Boolean = true,
        onFinalTextReady: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
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
                // 如果开启了自动发送或者当前为按住发送模式且识别内容非空，则直接发送
                if ((autoSend || AppSettings.asrAutoSend.value) && finalResult.isNotBlank()) {
                    sendMessage(finalResult)
                }
            },
            onError = { err ->
                _voicePartialText.value = null
                onError(err)
            }
        )
    }

    fun stopVoiceRecording() {
        speechManager.stopListening()
        _voicePartialText.value = null
    }

    fun cancelVoiceRecording() {
        speechManager.cancelListening()
        _voicePartialText.value = null
    }

    fun clearChatHistory() {
        speechManager.stopSpeaking()
        _speakingMessageId.value = null
        currentGenerationJob?.cancel()
        currentThinkingId = null
        repository.stopGeneration()
        _chatMessages.value = listOf(
            ChatMessage(messageCounter++, false, "上下文已重置。我是部署在您本地终端的 AI，所有交互在端侧封闭运行。")
        )
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
    }
}
