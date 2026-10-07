package cn.xxstudy.assistant.viewmodel

import android.app.Application
import android.media.AudioManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.xxstudy.assistant.data.AppSettings
import cn.xxstudy.assistant.repository.LlamaRepository
import cn.xxstudy.assistant.conversation.AnswerKind
import cn.xxstudy.assistant.conversation.ConversationRecap
import cn.xxstudy.assistant.conversation.ConversationRecapResult
import cn.xxstudy.assistant.conversation.ConversationStore
import cn.xxstudy.assistant.conversation.TurnState
import cn.xxstudy.assistant.conversation.toDisplaySnapshot
import cn.xxstudy.assistant.conversation.toRestoredChatMessages
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import java.util.UUID
import cn.xxstudy.assistant.speech.SpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import cn.xxstudy.assistant.data.ModelType
import cn.xxstudy.assistant.engine.ThinkingStreamParser
import cn.xxstudy.assistant.ui.components.ActionExecutor
import cn.xxstudy.assistant.ui.components.IntentParser

private const val TAG = "MainViewModel"

data class ChatMessage(
    val id: Int,
    val isUser: Boolean,
    val text: String,
    val thinkingText: String? = null,
    val isThinkingActive: Boolean = false,
    val isThinkingCollapsed: Boolean = true,
    val isThinking: Boolean = false,
    val metrics: String? = null,
    /** Knowledge-base excerpts are source material, never executable intent JSON. */
    val isKnowledgeExcerpt: Boolean = false,
    /** Keeps streaming answers on the lightweight text renderer until completion. */
    val isStreaming: Boolean = false,
    /** References are never sent through intent execution or speech synthesis. */
    val knowledgeSources: List<cn.xxstudy.assistant.rag.RagSource> = emptyList(),
    val knowledgeStage: cn.xxstudy.assistant.rag.RagKnowledgeStage? = null,
    val knowledgeRetrievalInfo: String? = null,
    /** Restored excerpts are historical display snapshots, not freshly retrieved evidence. */
    val isHistoricalKnowledge: Boolean = false,
    /** Quoted user questions are literal history, not Markdown, sources or executable actions. */
    val isConversationRecap: Boolean = false
) {
    val isReadOnlyContent: Boolean get() = isKnowledgeExcerpt || isConversationRecap || knowledgeSources.isNotEmpty()
}

internal fun ChatMessage.applyConversationRecap(recap: ConversationRecapResult): ChatMessage = copy(
    text = recap.text, thinkingText = null, isThinking = false, isThinkingActive = false,
    isStreaming = false, isKnowledgeExcerpt = false, isConversationRecap = true,
    knowledgeSources = emptyList(), knowledgeStage = null, knowledgeRetrievalInfo = null,
    metrics = recap.metrics
)

/** Keep cancelled RAG drafts distinct from completed, citation-checked answers. */
internal fun ChatMessage.finishInterruptedGeneration(): ChatMessage = copy(
    text = if (text.isBlank() && thinkingText.isNullOrBlank()) "（已打断）" else text,
    isThinking = false,
    isThinkingActive = false,
    isStreaming = false,
    knowledgeStage = null,
    isKnowledgeExcerpt = isKnowledgeExcerpt || knowledgeSources.isNotEmpty(),
    metrics = if (knowledgeSources.isNotEmpty()) "生成已中止 · 引用未核对" else metrics
)

/** Current-source answers stay drafts until block validation; ordinary chat can stream. */
internal fun ChatMessage.applyGenerationSnapshot(snapshot: ThinkingStreamParser.Snapshot, hasEvidence: Boolean): ChatMessage = copy(
    text = if (hasEvidence) "" else snapshot.answerText,
    thinkingText = snapshot.thinkingText,
    isThinkingActive = snapshot.isThinkingActive,
    isThinking = false,
    isStreaming = true
)

/**
 * [MainViewModel] (MVVM 架构核心)
 * 纯粹的 UI 状态管理器与业务调度中枢。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LlamaRepository()
    private val conversationStore = ConversationStore.shared(application)
    private val _historyReady = MutableStateFlow(false)
    val historyReady: StateFlow<Boolean> = _historyReady.asStateFlow()
    private val _historyNotice = MutableStateFlow<String?>("正在恢复本地会话…")
    val historyNotice: StateFlow<String?> = _historyNotice.asStateFlow()
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

    val isListening: StateFlow<Boolean> = speechManager.isListening

    init {
        AppSettings.init(application)
        restoreConversation()
        observeKwsState()
        observeLlmEngineState()
    }

    private fun restoreConversation() {
        viewModelScope.launch {
            try {
                val turns = withContext(Dispatchers.IO) { conversationStore.restore() }
                if (turns.isNotEmpty()) {
                    _chatMessages.value = turns.flatMap { it.toRestoredChatMessages() }
                    messageCounter = (_chatMessages.value.maxOfOrNull { it.id } ?: -1) + 1
                }
                _historyReady.value = true
                _historyNotice.value = "本地会话保留最近100轮；模型按窗口记忆近期完整问答。"
            } catch (_: Exception) {
                _historyNotice.value = "本地会话读取失败，已暂停发送，避免覆盖历史。请重启后重试。"
            }
        }
    }

    private fun observeLlmEngineState() {
        viewModelScope.launch {
            AppSettings.isLlmEngineEnabled.collect { enabled ->
                if (!enabled && _isModelLoaded.value) {
                    unloadModel()
                } else if (!enabled) {
                    _statusMessage.value = "纯语音测试模式 (大模型未启用)"
                }
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "正在卸载大模型..."
            repository.unloadModel()
            _isModelLoaded.value = false
            _currentLoadedModel.value = null
            _isLoading.value = false
            _statusMessage.value = if (AppSettings.isLlmEngineEnabled.value) "尚未启动" else "纯语音测试模式 (大模型未启用)"
        }
    }

    // Obsidian 知识库 (RAG) 同步引擎
    val obsidianSyncManager by lazy { cn.xxstudy.assistant.rag.ObsidianSyncManager(getApplication()) }
    val obsidianSyncProgress = obsidianSyncManager.progress
    private val knowledgeRetriever by lazy { cn.xxstudy.assistant.rag.KnowledgeRetriever(getApplication()) }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            obsidianSyncManager.refreshStats()
        }
    }

    fun syncObsidianVault(treeUri: android.net.Uri) {
        viewModelScope.launch {
            obsidianSyncManager.syncVault(treeUri)
        }
    }

    fun clearObsidianKnowledgeBase() {
        viewModelScope.launch(Dispatchers.IO) {
            obsidianSyncManager.clearKnowledgeBase()
        }
    }

    private fun observeKwsState() {
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.flow.combine(
                AppSettings.isKwsEnabled,
                AppSettings.kwsKeyword,
                AppSettings.kwsThreshold
            ) { enabled, keyword, _ ->
                enabled to keyword
            }.collect { (enabled, _) ->
                if (enabled) {
                    startKwsListener()
                } else {
                    speechManager.stopKws()
                }
            }
        }
    }

    fun startKwsListener() {
        if (!AppSettings.isKwsEnabled.value) return
        viewModelScope.launch(Dispatchers.IO) {
            speechManager.startKws { keyword ->
                android.util.Log.i(TAG, "🎯 [KWS] 捕获唤醒词: $keyword，平滑启动 SenseVoice ASR 倾听...")
                viewModelScope.launch(Dispatchers.Main) {
                    startVoiceRecording(autoSend = true)
                }
            }
        }
    }

    fun loadLocalModel(absolutePath: String, modelType: ModelType = AppSettings.currentModelType.value, force: Boolean = false) {
        if ((_isModelLoaded.value && !force) || _isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "引擎装载中 (${modelType.displayName})..."
            val success = repository.loadModel(absolutePath, AppSettings.contextSize.value, AppSettings.gpuInferenceEnabled.value)
            _isLoading.value = false
            if (success) {
                _isModelLoaded.value = true
                _currentLoadedModel.value = modelType
                _statusMessage.value = "在线 (${modelType.displayName} · ${repository.getBackendName()})"
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
     * 切换当前激活的大模型并立即重载底层引擎
     */
    fun switchModel(context: android.content.Context, targetModel: ModelType, onComplete: ((Boolean, String) -> Unit)? = null) {
        val targetFile = AppSettings.resolveModelFile(context, targetModel)
        if (targetFile == null || !targetFile.exists()) {
            onComplete?.invoke(false, "未在本地找到 ${targetModel.fileName}，请先推送或下载该模型")
            return
        }

        AppSettings.setCurrentModelType(targetModel)
        viewModelScope.launch {
            // 打断当前可能正在运行的推理
            if (currentGenerationJob?.isActive == true) {
                repository.stopGeneration(currentRequestId)
                currentGenerationJob?.cancel()
            }
            _isModelLoaded.value = false
            _isLoading.value = true
            _statusMessage.value = "引擎切换中 (${targetModel.displayName})..."
            
            val success = repository.loadModel(targetFile.absolutePath, AppSettings.contextSize.value, AppSettings.gpuInferenceEnabled.value)
            _isLoading.value = false
            if (success) {
                _isModelLoaded.value = true
                _currentLoadedModel.value = targetModel
                _statusMessage.value = "在线 (${targetModel.displayName} · ${repository.getBackendName()})"
                
                // 对话流提示：若已有消息则追加切换通告，若无消息则显示新模型开场白
                if (_chatMessages.value.isEmpty()) {
                    _chatMessages.value = listOf(
                        ChatMessage(
                            messageCounter++,
                            false,
                            "您好！我是部署在您本地终端的 AI，已装载 ${targetModel.displayName} 模型。所有推理在设备芯片内部完成，数据绝不上云。"
                        )
                    )
                } else {
                    _chatMessages.value = _chatMessages.value + listOf(
                        ChatMessage(
                            id = messageCounter++,
                            isUser = false,
                            text = "💡 模型已动态切换为【${targetModel.displayName}】。接下来的对话将由新引擎为您解答。"
                        )
                    )
                }
                
                // 重启 Ktor 微服务
                cn.xxstudy.assistant.server.LlamaServer.start(repository)
                onComplete?.invoke(true, "已成功切换为 ${targetModel.displayName}")
            } else {
                _statusMessage.value = "加载失败"
                onComplete?.invoke(false, "加载 ${targetModel.displayName} 失败")
            }
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
    private var currentRequestId: String? = null
    private var _isActiveCall = MutableStateFlow(false)
    val isActiveCall: StateFlow<Boolean> = _isActiveCall.asStateFlow()


    /** True means synchronously accepted; asynchronous generation may still fail. */
    fun sendMessage(prompt: String): Boolean {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return false
        if (!_historyReady.value) return false
        val isLocalRecap = ConversationRecap.isRequest(trimmed)

        // 停止之前的朗读
        speechManager.stopSpeaking()
        _speakingMessageId.value = null

        // 检查是否关闭大模型（纯语音测试 / 复读回显模式）
        if (!AppSettings.isLlmEngineEnabled.value && !isLocalRecap) {
            val userMsg = ChatMessage(messageCounter++, true, trimmed)
            val echoId = messageCounter++
            val echoMsg = ChatMessage(echoId, false, trimmed)
            _chatMessages.value = _chatMessages.value + listOf(userMsg, echoMsg)

            if (AppSettings.ttsAutoPlay.value) {
                _speakingMessageId.value = echoId
                speechManager.speak(trimmed) {
                    _speakingMessageId.value = null
                    speechManager.resumeKws()
                }
            } else {
                speechManager.resumeKws()
            }
            return true
        }

        if (!_isModelLoaded.value && !isLocalRecap) return false

        // 1. 如果上一轮模型还在推理输出，立即打断 C++ 循环并取消旧协程
        val previousGeneration = currentGenerationJob
        if (currentGenerationJob?.isActive == true) {
            repository.stopGeneration(currentRequestId)
            currentGenerationJob?.cancel()
            // 将上一条被打断的 AI 消息封口（停止转圈动效）
            currentThinkingId?.let { oldId ->
                val list = _chatMessages.value.map {
                    if (it.id == oldId && (it.isThinking || it.isThinkingActive || it.isStreaming || it.knowledgeStage != null)) {
                        it.finishInterruptedGeneration()
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
        val requestId = UUID.randomUUID().toString()
        currentRequestId = requestId
        val thinkingMsg = ChatMessage(thinkingId, false, "", isThinking = true, isStreaming = true,
            knowledgeStage = if (AppSettings.isObsidianRagEnabled.value)
                cn.xxstudy.assistant.rag.RagKnowledgeStage.RETRIEVING else null)

        _chatMessages.value = _chatMessages.value + listOf(userMsg, thinkingMsg)

        // “你了解我的知识库吗”没有可供向量检索的主题。直接用本地索引状态回答，
        // 避免检索 0 命中后让小模型猜测，进而错误声称“无法访问本地知识库”。
        currentGenerationJob = viewModelScope.launch {
            var ticket: cn.xxstudy.assistant.conversation.TurnTicket? = null
            var requestFailed = false
            try {
                // Wait for old ledger cleanup too: a cancelled INSERT must not run
                // after this request's BEGIN and accidentally supersede the new turn.
                previousGeneration?.join()
                ensureActive()
                // Once an INSERT starts it must finish and expose its ticket, even on cancellation.
                ticket = withContext(NonCancellable + Dispatchers.IO) {
                    conversationStore.begin(requestId, userMsg.id, thinkingId, trimmed)
                }
                ensureActive()
                val history = withContext(Dispatchers.IO) { conversationStore.history() }
                suspend fun commit(answer: String, kind: AnswerKind,
                    display: cn.xxstudy.assistant.conversation.ConversationDisplaySnapshot =
                        _chatMessages.value.first { it.id == thinkingId }.toDisplaySnapshot()) {
                    ensureActive()
                    check(withContext(Dispatchers.IO) { conversationStore.complete(requireNotNull(ticket), answer, kind, display) }) {
                        "Request was superseded"
                    }
                    ensureActive()
                }

                if (cn.xxstudy.assistant.rag.RagQueryNormalizer.isKnowledgeBaseStatusQuery(trimmed)) {
                    val statusAnswer = cn.xxstudy.assistant.rag.RagStatusResponse.build(
                        enabled = AppSettings.isObsidianRagEnabled.value,
                        vaultName = AppSettings.obsidianVaultName.value,
                        docCount = AppSettings.ragDocCount.value,
                        chunkCount = AppSettings.ragChunkCount.value
                    )
                    commit(statusAnswer, AnswerKind.STATUS,
                        cn.xxstudy.assistant.conversation.ConversationDisplaySnapshot(metrics = "知识库状态"))
                    _chatMessages.value = _chatMessages.value.map { message ->
                        if (message.id == thinkingId) {
                            message.copy(
                                text = statusAnswer,
                                isThinking = false,
                                isThinkingActive = false,
                                metrics = "知识库状态",
                                isStreaming = false,
                                knowledgeStage = null
                            )
                        } else {
                            message
                        }
                    }
                    currentThinkingId = null
                    currentRequestId = null
                    if (AppSettings.ttsAutoPlay.value) {
                        speakMessage(thinkingId, statusAnswer)
                    }
                    return@launch
                }

                // 如果是全双工电话模式，在发送消息后立即再次开启录音，实现随时打断
                if (_isActiveCall.value) startPhoneMode()

                val resolved = cn.xxstudy.assistant.rag.RagQueryResolver.resolve(trimmed, history)
                check(withContext(Dispatchers.IO) {
                    conversationStore.setRetrievalScope(requireNotNull(ticket), resolved.topic, resolved.sourceRequired)
                }) { "Request was superseded" }
                if (resolved.route == cn.xxstudy.assistant.rag.RagQueryRoute.RECAP) {
                    val recap = ConversationRecap.build(trimmed, history, requestId)
                    commit(recap.text, AnswerKind.USER_RECAP,
                        cn.xxstudy.assistant.conversation.ConversationDisplaySnapshot(metrics = recap.metrics))
                    _chatMessages.value = _chatMessages.value.map { message ->
                        if (message.id == thinkingId) message.applyConversationRecap(recap) else message
                    }
                    if (AppSettings.ttsAutoPlay.value) speakMessage(thinkingId, recap.text)
                    return@launch
                }

                // 构造流式思考解析器
                val parser = ThinkingStreamParser()

                val enableThinking = AppSettings.enableThinking.value
                val currentModel = _currentLoadedModel.value ?: AppSettings.currentModelType.value

                var ragMatches: List<cn.xxstudy.assistant.rag.KnowledgeMatch> = emptyList()
                var ragSources: List<cn.xxstudy.assistant.rag.RagSource> = emptyList()
                var ragRetrievalInfo: String? = null
                var retrievalFailed = false
                val isExplicitRagLookup = cn.xxstudy.assistant.rag.RagQueryNormalizer
                    .isExplicitKnowledgeLookup(prompt)
                if (resolved.route == cn.xxstudy.assistant.rag.RagQueryRoute.CLARIFY) {
                    val clarification = requireNotNull(resolved.clarification)
                    commit(clarification, AnswerKind.CLARIFICATION,
                        cn.xxstudy.assistant.conversation.ConversationDisplaySnapshot(metrics = "待澄清 · 未检索，未生成模型草稿"))
                    _chatMessages.value = _chatMessages.value.map { message ->
                        if (message.id == thinkingId) message.copy(text = clarification, isThinking = false,
                            isStreaming = false, knowledgeStage = null, metrics = "待澄清 · 未检索，未生成模型草稿") else message
                    }
                    if (AppSettings.ttsAutoPlay.value) speakMessage(thinkingId, clarification)
                    return@launch
                }
                val ragQuery = resolved.query
                val answerPolicy = resolved.answerPolicy
                val ragEnabled = AppSettings.isObsidianRagEnabled.value
                if (ragEnabled && resolved.shouldRetrieve) {
                    try {
                        val matches = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            // Only explicit, unique follow-ups borrow a user-owned topic.
                            android.util.Log.d(
                                "MainViewModel",
                                "RAG query prepared: inputChars=${prompt.trim().length}, searchChars=${ragQuery.length}"
                            )
                            val retrievalStart = android.os.SystemClock.elapsedRealtime()
                            val result = knowledgeRetriever.retrieveDetailed(ragQuery)
                            val seeds = result.matches
                            val retrievalMillis = android.os.SystemClock.elapsedRealtime() - retrievalStart
                            val modeName = when {
                                seeds.isEmpty() -> "本轮未引用知识库"
                                result.mode == cn.xxstudy.assistant.rag.RagSearchMode.KEYWORD -> "关键词检索"
                                else -> "混合检索"
                            }
                            val followup = if (resolved.route == cn.xxstudy.assistant.rag.RagQueryRoute.FOLLOWUP)
                                "承接：${resolved.topic} · " else ""
                            ragRetrievalInfo = "$followup$modeName · ${seeds.size} 个命中 · ${retrievalMillis} ms"
                            if (isExplicitRagLookup) {
                                knowledgeRetriever.expandMatchedSections(seeds, maxChars = 64_000)
                            } else {
                                ragSources = cn.xxstudy.assistant.rag.RagSourcePresenter.sources(
                                    seeds,
                                    knowledgeRetriever.expandMatchedSections(seeds, maxChars = 32_000)
                                )
                                seeds
                            }
                        }
                        if (matches.isNotEmpty()) {
                            ragMatches = matches
                            android.util.Log.i("MainViewModel", "RAG: Injected ${matches.size} chunks into prompt")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        retrievalFailed = true
                        ragRetrievalInfo = "本轮检索失败 · 未取得资料依据"
                        android.util.Log.e("MainViewModel", "RAG retrieval failed")
                    }
                } else if (ragEnabled) {
                    ragRetrievalInfo = "普通聊天 · 本轮无需知识库检索"
                }

                _chatMessages.value = _chatMessages.value.map { message ->
                    if (message.id == thinkingId) message.copy(
                        knowledgeSources = ragSources,
                        knowledgeRetrievalInfo = ragRetrievalInfo,
                        knowledgeStage = if (ragMatches.isNotEmpty()) cn.xxstudy.assistant.rag.RagKnowledgeStage.GENERATING else null
                    ) else message
                }

                val disableThinkingForRequest = cn.xxstudy.assistant.rag.RagGenerationPolicy
                    .shouldDisableThinking(
                        userEnabledThinking = enableThinking,
                        modelSupportsThinking = currentModel.supportsThinking,
                        hasKnowledgeMatches = ragMatches.isNotEmpty()
                    )

                // 显式要求查询知识库时，0 命中也必须由 RAG 链路给出可诊断的结果。
                // 不再回落给大模型，否则它无法区分“未连接”和“阈值过高”。
                if (resolved.sourceRequired && ragMatches.isEmpty()) {
                    val noMatchAnswer = if (!ragEnabled) "本轮需要知识库依据，但知识库检索未启用。请先开启并同步；我不会用通用知识猜测你的笔记或配置。"
                        else if (retrievalFailed) "本轮知识库检索失败，未取得资料依据。请重试；我不会用通用知识猜测你的笔记或配置。"
                        else cn.xxstudy.assistant.rag.RagStatusResponse.noReliableMatch(
                        query = ragQuery,
                        docCount = AppSettings.ragDocCount.value,
                        threshold = AppSettings.ragScoreThreshold.value
                    )
                    commit(noMatchAnswer, AnswerKind.FALLBACK,
                        cn.xxstudy.assistant.conversation.ConversationDisplaySnapshot(
                            retrievalInfo = ragRetrievalInfo,
                            metrics = if (!ragEnabled) "知识库未启用" else if (retrievalFailed) "检索失败" else "知识库未命中"))
                    _chatMessages.value = _chatMessages.value.map { message ->
                        if (message.id == thinkingId) {
                            message.copy(
                                text = noMatchAnswer,
                                isThinking = false,
                                isThinkingActive = false,
                                metrics = if (!ragEnabled) "知识库未启用" else if (retrievalFailed) "检索失败" else "知识库未命中",
                                isStreaming = false,
                                knowledgeStage = null
                            )
                        } else {
                            message
                        }
                    }
                    if (currentThinkingId == thinkingId) {
                        currentThinkingId = null
                        currentRequestId = null
                    }
                    if (AppSettings.ttsAutoPlay.value) {
                        speakMessage(thinkingId, noMatchAnswer)
                    }
                    return@launch
                }

                // “通过知识库查询 X”表达的是查阅原文，而不是让小模型二次改写。
                // 直接返回完整命中章节，既不会遗漏后续分块，也不会引入模型幻觉。
                if (isExplicitRagLookup && ragMatches.isNotEmpty()) {
                    val extractiveAnswer = cn.xxstudy.assistant.rag.RagGroundingGuard
                        .buildExtractiveFallback(ragMatches, maxChars = 64_000)
                    commit(extractiveAnswer, AnswerKind.SOURCE_LOOKUP,
                        cn.xxstudy.assistant.conversation.ConversationDisplaySnapshot(
                            retrievalInfo = ragRetrievalInfo,
                            metrics = "知识库原文 · ${ragMatches.size} 个分块", isKnowledgeExcerpt = true))
                    _chatMessages.value = _chatMessages.value.map { message ->
                        if (message.id == thinkingId) {
                            message.copy(
                                text = extractiveAnswer,
                                isThinking = false,
                                isThinkingActive = false,
                                metrics = "知识库原文 · ${ragMatches.size} 个分块",
                                isKnowledgeExcerpt = true,
                                isStreaming = false,
                                knowledgeStage = null
                            )
                        } else {
                            message
                        }
                    }
                    android.util.Log.i(
                        "MainViewModel",
                        "RAG explicit lookup: expandedChunks=${ragMatches.size}, chars=${extractiveAnswer.length}"
                    )
                    if (currentThinkingId == thinkingId) {
                        currentThinkingId = null
                        currentRequestId = null
                    }
                    return@launch
                }

                val systemPrompt = AppSettings.getEffectiveSystemPrompt(currentModel)
                // Native KV is reset and reconstructed from finalized application history.
                val conversationMode = if (ragMatches.isNotEmpty()) cn.xxstudy.assistant.repository.ConversationMode.RAG
                    else cn.xxstudy.assistant.repository.ConversationMode.CHAT

                // 初始化 TTS 流式切句器（仅当开启答案语音朗读时）
                var chunker: cn.xxstudy.assistant.speech.SentenceChunker? = null
                var isJsonStream = false
                var hasDecidedStreamType = false
                val bufferedPrefix = StringBuilder()

                if (AppSettings.ttsAutoPlay.value && ragMatches.isEmpty()) {
                    chunker = speechManager.createSentenceChunker()
                    _speakingMessageId.value = thinkingId
                    // 核心安全绑定：
                    // 1. 仅在思维链解析器识别出真正的回答正文 (Answer) 时，才考虑送入流式切句器合成语音，彻底隔绝 <|thought_begin|>
                    // 2. 检测到模型输出意图控制指令（以 JSON 符号或代码块开头）时，阻断流式切句，防止 TTS 朗读大括号、引号等生硬代码
                    parser.onAnswerChunk = { answerChunk ->
                        if (!hasDecidedStreamType) {
                            bufferedPrefix.append(answerChunk)
                            val textSoFar = bufferedPrefix.toString().trimStart()
                            if (textSoFar.startsWith("[") || textSoFar.startsWith("{") || textSoFar.startsWith("```")) {
                                isJsonStream = true
                                hasDecidedStreamType = true
                            } else if (textSoFar.length >= 6) {
                                hasDecidedStreamType = true
                                chunker.onToken(bufferedPrefix.toString())
                                bufferedPrefix.clear()
                            }
                        } else if (!isJsonStream) {
                            chunker.onToken(answerChunk)
                        }
                    }
                }

                // 使用无界 Channel 将 C++ 的回调与 UI 渲染彻底解耦，防止 UI 渲染过慢反向阻塞 JNI 推理线程
                val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)

                val uiUpdaterJob = launch {
                    // 限流策略：积累 token 批量更新，避免每秒触发几十足次 UI 重组
                    var lastUpdateTime = System.currentTimeMillis()
                    for (token in channel) {
                        parser.feed(token)
                        val now = System.currentTimeMillis()
                        // 每 60ms 更新一次 UI 即可保持视觉流畅度，极大地节省 CPU 资源
                        if (now - lastUpdateTime > 60) {
                            lastUpdateTime = now
                            val snapshot = parser.getSnapshot()
                            val newList = _chatMessages.value.toMutableList()
                            val idx = newList.indexOfLast { it.id == thinkingId }
                            if (idx != -1) {
                                newList[idx] = newList[idx].applyGenerationSnapshot(snapshot, ragMatches.isNotEmpty())
                                _chatMessages.value = newList
                            }
                        }
                    }
                }

                try {
                    // 调用带有打断检测的推理方法
                    var contextInfo = ""
                    val selectedMatches = ragMatches
                    val debugRag = selectedMatches.isNotEmpty() &&
                        (getApplication<Application>().applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    val preparationStarted = android.os.SystemClock.elapsedRealtime()
                    var generationStarted = preparationStarted
                    val rawResponse = repository.generateReplay(
                        mode = conversationMode,
                        history = history,
                        evidenceCount = selectedMatches.size,
                        buildPrompt = { plannedHistory, evidenceCount ->
                            cn.xxstudy.assistant.repository.ConversationPromptBuilder.build(
                                systemPrompt = systemPrompt, question = resolved.question,
                                ragContext = cn.xxstudy.assistant.rag.RagPromptBuilder.build(selectedMatches.take(evidenceCount), trimmed, answerPolicy),
                                includeSystem = true, disableThinking = disableThinkingForRequest,
                                history = plannedHistory,
                                requestPolicy = resolved.systemInstruction(evidenceCount > 0, ragEnabled)
                            )
                        },
                        onPlan = { plan ->
                            generationStarted = android.os.SystemClock.elapsedRealtime()
                            ragMatches = selectedMatches.take(selectedMatches.size - plan.droppedEvidence)
                            if (debugRag) android.util.Log.i("RagInferenceDiagnostics",
                                "RAG plan: input_tokens=${plan.inputTokens} evidence=${ragMatches.size} " +
                                    "history_turns=${cn.xxstudy.assistant.conversation.ConversationMemory.completed(history).size - plan.droppedTurns} " +
                                    "dropped_turns=${plan.droppedTurns} dropped_evidence=${plan.droppedEvidence} " +
                                    "thinking_disabled=$disableThinkingForRequest policy=${answerPolicy.name} " +
                                    "preparation_ms=${generationStarted - preparationStarted}")
                            ragSources = ragSources.mapNotNull { source ->
                                val ids = source.referenceIds.filter { it <= ragMatches.size }
                                if (ids.isEmpty()) null else source.copy(referenceIds = ids)
                            }
                            contextInfo = "输入 ${plan.inputTokens} tokens" +
                                (if (plan.droppedTurns > 0) " · ${plan.droppedTurns} 轮未进入模型窗口" else "") +
                                (if (plan.droppedEvidence > 0) " · ${plan.droppedEvidence} 条资料未进入模型窗口" else "")
                            _chatMessages.value = _chatMessages.value.map { message ->
                                if (message.id == thinkingId) message.copy(knowledgeSources = ragSources) else message
                            }
                        },
                        onToken = { token -> channel.trySend(token) },
                        requestId = requestId
                    )
                    val generationWallMs = android.os.SystemClock.elapsedRealtime() - generationStarted

                    // 推理结束，关闭 channel 并等待 UI 刷新完最后一批字符
                    channel.close()
                    uiUpdaterJob.join()

                    // 推理彻底结束，通知解析器收口（触发 pending 中残留的 answerChunk）
                    parser.finish()
                    val finalSnapshot = parser.getSnapshot()

                    val actualText = cn.xxstudy.assistant.engine.GenerationOutputResolver.resolve(
                        answerText = finalSnapshot.answerText,
                        thinkingText = finalSnapshot.thinkingText
                    )

                    if (ragMatches.isNotEmpty()) {
                        _chatMessages.value = _chatMessages.value.map { message ->
                            if (message.id == thinkingId) message.copy(knowledgeStage = cn.xxstudy.assistant.rag.RagKnowledgeStage.CHECKING) else message
                        }
                    }

                    val ragAnswer = kotlinx.coroutines.withContext(Dispatchers.Default) {
                        cn.xxstudy.assistant.rag.RagSourcePresenter.present(
                            generated = actualText,
                            matches = ragMatches,
                            policy = answerPolicy,
                            hasFinalAnswer = finalSnapshot.answerText.isNotBlank()
                        )
                    }
                    val groundedText = ragAnswer.text
                    if (debugRag) {
                        val measurement = cn.xxstudy.assistant.rag.RagGenerationDiagnostics.measure(
                            rawResponse.substringBefore("<|metrics|>"), finalSnapshot.answerText, finalSnapshot.thinkingText)
                        android.util.Log.i("RagInferenceDiagnostics",
                            "RAG output: ${measurement.event()} generation_wall_ms=$generationWallMs " +
                                "general_rejections=${ragAnswer.generalRejections.joinToString(",") { it.name }.ifEmpty { "NONE" }}")
                    }
                    ragAnswer.validationEvent?.let { event -> android.util.Log.w("MainViewModel", event) }

                    val parsedActions = if (ragMatches.isEmpty()) {
                        IntentParser.parse(groundedText)
                    } else {
                        null
                    }
                    val parts = rawResponse.split("<|metrics|>")
                    val metricsInfo = if (parts.size > 1) {
                        try {
                            val j = org.json.JSONObject(parts[1])
                            "首字: ${j.optLong("ttft_ms")} ms | 耗时: ${j.optLong("total_ms")} ms | 速度: ${j.optDouble("speed")} tk/s"
                        } catch(e: Exception) { parts[1] }
                    } else null
                    val finalMessage = _chatMessages.value.first { it.id == thinkingId }.copy(
                        text = groundedText,
                        thinkingText = finalSnapshot.thinkingText,
                        isThinkingActive = false, isThinking = false,
                        metrics = listOfNotNull(metricsInfo, contextInfo).joinToString(" | "),
                        knowledgeSources = ragSources,
                        knowledgeRetrievalInfo = listOfNotNull(ragRetrievalInfo, ragAnswer.validationNotice)
                            .joinToString(" · ").ifBlank { null },
                        isKnowledgeExcerpt = ragAnswer.isFallback,
                        isStreaming = false, knowledgeStage = null
                    )
                    // Atomically commit final text and its exact display snapshot before publishing.
                    commit(groundedText, when {
                        parsedActions != null -> AnswerKind.ACTION_PROPOSAL
                        ragAnswer.isFallback || finalSnapshot.answerText.isBlank() -> AnswerKind.FALLBACK
                        ragMatches.isNotEmpty() -> requireNotNull(ragAnswer.kind)
                        else -> AnswerKind.GENERAL
                    }, finalMessage.toDisplaySnapshot())
                    if (parsedActions != null) {
                        // Model output is a proposal, not authorization to execute device actions.
                        if (AppSettings.ttsAutoPlay.value) {
                            speakMessage(thinkingId, "已生成操作建议，请核对下方内容，确认后执行。")
                        }
                    } else {
                        // 普通文本：冲刷切句器完成最后一句流式播报
                        if (!hasDecidedStreamType && bufferedPrefix.isNotEmpty()) {
                            chunker?.onToken(bufferedPrefix.toString())
                        }
                        chunker?.flush()

                        if (AppSettings.ttsAutoPlay.value && chunker != null) {
                            speechManager.finishStreamingSpeech(
                                onDone = {
                                    if (_speakingMessageId.value == thinkingId) {
                                        _speakingMessageId.value = null
                                    }
                                }
                            )
                        }
                    }

                    _chatMessages.value = _chatMessages.value.map { if (it.id == thinkingId) finalMessage else it }

                    // 兜底朗读：仅在未开启流式切句且非意图识别消息时执行整句播报
                    if (chunker == null && parsedActions == null && !ragAnswer.isFallback && AppSettings.ttsAutoPlay.value && groundedText.isNotBlank() && groundedText != "（回复为空）") {
                        speakMessage(thinkingId, groundedText)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 收到新消息打断时，安全退出并停止当前朗读
                    channel.close()
                    uiUpdaterJob.cancel()
                    if (currentThinkingId == thinkingId) {
                        speechManager.stopSpeaking()
                        _speakingMessageId.value = null
                    }
                } catch (e: Exception) {
                    requestFailed = true
                    android.util.Log.e(TAG, "Answer generation failed", e)
                    channel.close()
                    uiUpdaterJob.cancel()
                    if (currentThinkingId == thinkingId) {
                        speechManager.stopSpeaking()
                        _speakingMessageId.value = null
                    }
                    _chatMessages.value = _chatMessages.value.map { message ->
                        if (message.id == thinkingId) message.copy(
                            text = if (e is cn.xxstudy.assistant.conversation.ContextBudgetExceeded) e.message.orEmpty()
                                else if (ragSources.isNotEmpty()) "本次摘要生成失败，可查看下方知识库原文，或重试。" else "本次回答生成失败，请重试。",
                            isKnowledgeExcerpt = ragSources.isNotEmpty()
                        ) else message
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Covers cancellation during retrieval or ledger access, not only JNI.
                throw e
            } catch (_: Exception) {
                requestFailed = true
                _chatMessages.value = _chatMessages.value.map { message ->
                    if (message.id == thinkingId) message.copy(text = "本次会话保存或处理失败，请重试。") else message
                }
            } finally {
                ticket?.let { pending ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { conversationStore.interrupt(pending, requestFailed) }
                        catch (_: Exception) { /* Pending requests recover as interrupted at restart. */ }
                    }
                }
                _chatMessages.value = _chatMessages.value.takeLast(200).map { message ->
                    if (message.id == thinkingId && (message.isStreaming || message.knowledgeStage != null)) {
                        message.finishInterruptedGeneration()
                    } else message
                }
                if (currentThinkingId == thinkingId) {
                    currentThinkingId = null
                    currentRequestId = null
                }
            }
        }
        return true
    }

    // ==========================================
    // TTS 播放控制
    // ==========================================

    fun speakMessage(id: Int, text: String) {
        if (_speakingMessageId.value == id && speechManager.isSpeaking.value) {
            speechManager.stopSpeaking()
            _speakingMessageId.value = null
        } else {
            val isRecap = _chatMessages.value.firstOrNull { it.id == id }?.isConversationRecap == true
            val parsedActions = if (isRecap) null else IntentParser.parse(text)
            val speechContent = if (isRecap) {
                text // Quoted action JSON / Markdown is history, not an action suggestion.
            } else if (parsedActions != null) {
                IntentParser.formatForSpeech(parsedActions)
            } else {
                sanitizeSpeechText(text)
            }
            _speakingMessageId.value = id

            speechManager.speak(
                text = speechContent,
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

    /** Remove Markdown/source-code noise before sending a normal answer to TTS. */
    private fun sanitizeSpeechText(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " ")
        .replace(Regex("[ *_>#]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    // 麦克风实时输入音量分贝 (供 HUD 波动动效)
    val listeningRms: StateFlow<Float> = speechManager.listeningRms

    // ==========================================
    // ASR 语音输入控制
    // ==========================================

    fun stopPhoneMode() {
        _isActiveCall.value = false
        
        val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false

        cancelVoiceRecording()
        speechManager.stopSpeaking()
    }


    fun startPhoneMode() {
        _isActiveCall.value = true
        
        val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        if (_isActiveCall.value) {
            startVoiceRecording(autoSend = true, interruptTts = false)
        }
    }

    fun startVoiceRecording(
        interruptTts: Boolean = true,

        autoSend: Boolean = true,
        onFinalTextReady: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        _voicePartialText.value = null
        val language = AppSettings.asrLanguage.value
        speechManager.startListening(
            interruptTts = interruptTts,

            language = language,
            autoStop = autoSend, // 如果是免提自动发送（KWS唤醒），则开启VAD自动停止；如果是按住说话，则由松手触发停止
            onVoiceStart = {
                if (_isActiveCall.value) {
                    interruptAIAndStartListening()
                }
            },

            onPartial = { partial ->
                _voicePartialText.value = partial
            },
            onFinal = { finalResult ->
                _voicePartialText.value = null
                onFinalTextReady(finalResult)
                // 如果开启了自动发送或者当前为按住发送模式且识别内容非空，则直接发送
                if ((autoSend || AppSettings.asrAutoSend.value) && finalResult.isNotBlank()) {
                    sendMessage(finalResult)
                } else if (finalResult.isBlank()) {
                    speechManager.resumeKws()
                }
            },
            onError = { err ->
                _voicePartialText.value = null
                onError(err)
                speechManager.resumeKws()
            }
        )
    }

    fun stopVoiceRecording() {
        speechManager.stopListening()
        _voicePartialText.value = null
    }

    fun interruptAIAndStartListening() {
        speechManager.stopSpeaking()
        _speakingMessageId.value = null
        if (currentGenerationJob?.isActive == true) {
            repository.stopGeneration(currentRequestId)
            currentGenerationJob?.cancel()
            currentThinkingId?.let { oldId ->
                val list = _chatMessages.value.map {
                    if (it.id == oldId && (it.isThinking || it.isThinkingActive || it.isStreaming || it.knowledgeStage != null)) {
                        it.finishInterruptedGeneration()
                    } else it
                }
                _chatMessages.value = list
            }
        }
    }

    fun cancelVoiceRecording() {
        speechManager.cancelListening()
        _voicePartialText.value = null
    }

    fun clearChatHistory() {
        if (!_historyReady.value) return
        _historyReady.value = false
        _historyNotice.value = "正在清空本地会话…"
        speechManager.stopSpeaking()
        _speakingMessageId.value = null
        currentGenerationJob?.cancel()
        val interruptedJob = currentGenerationJob
        repository.stopGeneration(currentRequestId)
        currentThinkingId = null
        currentRequestId = null
        viewModelScope.launch {
            try {
                interruptedJob?.join()
                withContext(NonCancellable + Dispatchers.IO) { conversationStore.clear() }
                repository.resetSession()
                _chatMessages.value = listOf(
                    ChatMessage(messageCounter++, false, "本地会话和模型上下文已清空。知识库原文未被删除。")
                )
                _historyReady.value = true
                _historyNotice.value = "本地会话已清空，知识库未删除。后续最多保存100轮。"
            } catch (_: Exception) {
                _historyNotice.value = "清空会话未完成，已暂停发送，请重启后重试。"
            }
        }
    }

    override fun onCleared() {
        repository.stopGeneration(currentRequestId)
        currentGenerationJob?.cancel()
        super.onCleared()
        cn.xxstudy.assistant.server.LlamaServer.stop()
        speechManager.destroy()
    }
}
