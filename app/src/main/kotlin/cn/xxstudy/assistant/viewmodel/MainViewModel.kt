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

import cn.xxstudy.assistant.data.ModelType
import cn.xxstudy.assistant.engine.ThinkingStreamParser

data class ChatMessage(
    val id: Int,
    val isUser: Boolean,
    val text: String,
    val thinkingText: String? = null,
    val isThinkingActive: Boolean = false,
    val isThinkingCollapsed: Boolean = false,
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

    private val _currentLoadedModel = MutableStateFlow<ModelType?>(null)
    val currentLoadedModel: StateFlow<ModelType?> = _currentLoadedModel.asStateFlow()

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

    fun loadLocalModel(absolutePath: String, modelType: ModelType = AppSettings.currentModelType.value, force: Boolean = false) {
        if ((_isModelLoaded.value && !force) || _isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "引擎装载中 (${modelType.displayName})..."
            val success = repository.loadModel(absolutePath)
            _isLoading.value = false
            if (success) {
                _isModelLoaded.value = true
                _currentLoadedModel.value = modelType
                _statusMessage.value = "在线 (${modelType.displayName})"
                // 添加或更新开场白
                if (_chatMessages.value.isEmpty()) {
                    _chatMessages.value = listOf(
                        ChatMessage(
                            messageCounter++,
                            false,
                            "您好！我是部署在您本地终端的 AI，已装载 ${modelType.displayName} 模型。所有推理在设备芯片内部完成，数据绝不上云。"
                        )
                    )
                }
                // 启动本地 Ktor 微服务，暴露接口供其他 App 跨进程调用
                cn.xxstudy.assistant.server.LlamaServer.start(repository)
            } else {
                _statusMessage.value = "加载失败"
            }
        }

        viewModelScope.launch {
            cn.xxstudy.assistant.data.AppSettings.localServerEnabled.collect { enabled ->
                if (_isModelLoaded.value) {
                    if (enabled) {
                        cn.xxstudy.assistant.server.LlamaServer.start(repository)
                    } else {
                        cn.xxstudy.assistant.server.LlamaServer.stop()
                    }
                }
            }
        }
    }

    /**
     * 切换当前激活的大模型
     */
    fun switchModel(context: android.content.Context, targetModel: ModelType, onComplete: (Boolean, String) -> Unit) {
        val targetFile = AppSettings.resolveModelFile(context, targetModel)
        if (targetFile == null || !targetFile.exists()) {
            onComplete(false, "未在本地找到 ${targetModel.fileName}，请先推送或下载该模型")
            return
        }

        AppSettings.setCurrentModelType(targetModel)
        viewModelScope.launch {
            // 打断当前可能正在运行的推理
            if (currentGenerationJob?.isActive == true) {
                repository.stopGeneration()
                currentGenerationJob?.cancel()
            }
            _isModelLoaded.value = false
            loadLocalModel(targetFile.absolutePath, targetModel, force = true)
            onComplete(true, "已切换为 ${targetModel.displayName}")
        }
    }

    /**
     * 折叠/展开指定消息的思考卡片
     */
    fun toggleThinkingCollapsed(messageId: Int) {
        _chatMessages.value = _chatMessages.value.map {
            if (it.id == messageId) it.copy(isThinkingCollapsed = !it.isThinkingCollapsed) else it
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
                    if (it.id == oldId && (it.isThinking || it.isThinkingActive)) {
                        val finalMsg = if (it.text.isBlank() && it.thinkingText.isNullOrBlank()) "（已打断）" else it.text
                        it.copy(text = finalMsg, isThinking = false, isThinkingActive = false)
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
        val thinkingMsg = ChatMessage(thinkingId, false, "", isThinking = true)

        _chatMessages.value = _chatMessages.value + listOf(userMsg, thinkingMsg)

        currentGenerationJob = viewModelScope.launch {
            // 构造流式思考解析器
            val parser = ThinkingStreamParser()

            // 判断是否需要抑制思考输出
            val enableThinking = AppSettings.enableThinking.value
            val currentModel = _currentLoadedModel.value ?: AppSettings.currentModelType.value
            val finalPrompt = if (!enableThinking && currentModel.supportsThinking) {
                // 若模型支持思考但用户在设置中关闭了思考，注入指令直接回答
                "<|im_start|>system\n请直接给出最终回答，无需输出思考过程。<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
            } else {
                prompt
            }

            // 使用无界 Channel 将 C++ 的回调与 UI 渲染彻底解耦，防止 UI 渲染过慢反向阻塞 JNI 推理线程
            val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)

            val uiUpdaterJob = launch {
                // 限流策略：积累 token 批量更新，避免每秒触发几十足次 UI 重组
                var lastUpdateTime = System.currentTimeMillis()
                for (token in channel) {
                    parser.feed(token)
                    val now = System.currentTimeMillis()
                    // 每 40ms 更新一次 UI 即可保持视觉流畅度，极大地节省 CPU 资源
                    if (now - lastUpdateTime > 40) {
                        lastUpdateTime = now
                        val snapshot = parser.getSnapshot()
                        val newList = _chatMessages.value.toMutableList()
                        val idx = newList.indexOfLast { it.id == thinkingId }
                        if (idx != -1) {
                            newList[idx] = newList[idx].copy(
                                text = snapshot.answerText,
                                thinkingText = snapshot.thinkingText,
                                isThinkingActive = snapshot.isThinkingActive,
                                isThinking = false
                            )
                            _chatMessages.value = newList
                        }
                    }
                }
            }

            try {
                var chunker: cn.xxstudy.assistant.speech.SentenceChunker? = null
                if (cn.xxstudy.assistant.data.AppSettings.ttsAutoPlay.value) {
                    chunker = speechManager.createSentenceChunker()
                    _speakingMessageId.value = thinkingId
                }

                // 调用带有打断检测的推理方法
                val rawResponse = repository.generateText(finalPrompt) { token ->
                    channel.trySend(token)
                    chunker?.onToken(token)
                }

                chunker?.flush()

                // 推理结束，关闭 channel 并等待 UI 刷新完最后一批字符
                channel.close()
                uiUpdaterJob.join()

                // 推理彻底结束，通知解析器收口
                parser.finish()
                val finalSnapshot = parser.getSnapshot()

                // 推理完成后，附加上最终的性能指标
                val parts = rawResponse.split("<|metrics|>")
                val metricsInfo = if (parts.size > 1) {
                    try {
                        val j = org.json.JSONObject(parts[1])
                        "首字: ${j.optLong("ttft_ms")} ms | 耗时: ${j.optLong("total_ms")} ms | 速度: ${j.optDouble("speed")} tk/s"
                    } catch(e: Exception) { parts[1] }
                } else null

                val actualText = finalSnapshot.answerText.ifBlank {
                    if (finalSnapshot.thinkingText != null && !finalSnapshot.isThinkingActive) {
                        // 如果仅有思考无正文
                        ""
                    } else {
                        "（回复为空）"
                    }
                }

                val finalUpdatedList = _chatMessages.value.map {
                    if (it.id == thinkingId) {
                        it.copy(
                            text = actualText,
                            thinkingText = finalSnapshot.thinkingText,
                            isThinkingActive = false,
                            isThinking = false,
                            metrics = metricsInfo
                        )
                    } else it
                }
                _chatMessages.value = finalUpdatedList

                // 检查设置：如果开启了自动朗读，且正文非空，则自动调用 TTS 播报（仅朗读回答正文，绝不播报思考过程！）
                if (AppSettings.ttsAutoPlay.value && actualText.isNotBlank() && actualText != "（回复为空）") {
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
        cn.xxstudy.assistant.server.LlamaServer.stop()
        speechManager.destroy()
    }
}
