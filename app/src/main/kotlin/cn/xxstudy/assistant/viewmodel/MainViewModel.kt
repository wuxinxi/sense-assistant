package cn.xxstudy.assistant.viewmodel

import android.app.Application
import android.media.AudioManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.xxstudy.assistant.data.AppSettings
import cn.xxstudy.assistant.repository.LlamaRepository
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
    val knowledgeRetrievalInfo: String? = null
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

    val isListening: StateFlow<Boolean> = speechManager.isListening

    init {
        AppSettings.init(application)
        observeKwsState()
        observeLlmEngineState()
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
                repository.stopGeneration()
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
    private var _isActiveCall = MutableStateFlow(false)
    val isActiveCall: StateFlow<Boolean> = _isActiveCall.asStateFlow()


    fun sendMessage(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return

        // 停止之前的朗读
        speechManager.stopSpeaking()
        _speakingMessageId.value = null

        // 检查是否关闭大模型（纯语音测试 / 复读回显模式）
        if (!AppSettings.isLlmEngineEnabled.value) {
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
            return
        }

        if (!_isModelLoaded.value) return

        // 1. 如果上一轮模型还在推理输出，立即打断 C++ 循环并取消旧协程
        if (currentGenerationJob?.isActive == true) {
            repository.stopGeneration()
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
        val thinkingMsg = ChatMessage(thinkingId, false, "", isThinking = true, isStreaming = true,
            knowledgeStage = if (AppSettings.isObsidianRagEnabled.value)
                cn.xxstudy.assistant.rag.RagKnowledgeStage.RETRIEVING else null)

        _chatMessages.value = _chatMessages.value + listOf(userMsg, thinkingMsg)

        // “你了解我的知识库吗”没有可供向量检索的主题。直接用本地索引状态回答，
        // 避免检索 0 命中后让小模型猜测，进而错误声称“无法访问本地知识库”。
        if (cn.xxstudy.assistant.rag.RagQueryNormalizer.isKnowledgeBaseStatusQuery(trimmed)) {
            val statusAnswer = cn.xxstudy.assistant.rag.RagStatusResponse.build(
                enabled = AppSettings.isObsidianRagEnabled.value,
                vaultName = AppSettings.obsidianVaultName.value,
                docCount = AppSettings.ragDocCount.value,
                chunkCount = AppSettings.ragChunkCount.value
            )
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
            if (AppSettings.ttsAutoPlay.value) {
                speakMessage(thinkingId, statusAnswer)
            }
            return
        }

        // 如果是全双工电话模式，在发送消息后立即再次开启录音，实现随时打断
        if (_isActiveCall.value) startPhoneMode()


        currentGenerationJob = viewModelScope.launch {
            // 构造流式思考解析器
            val parser = ThinkingStreamParser()

            // 判断是否为新对话的第一轮
            val isFirstTurn = _chatMessages.value.count { it.isUser } <= 1
            val enableThinking = AppSettings.enableThinking.value
            val currentModel = _currentLoadedModel.value ?: AppSettings.currentModelType.value
            
            var ragContextPrompt = ""
            var ragMatches: List<cn.xxstudy.assistant.rag.KnowledgeMatch> = emptyList()
            var ragSources: List<cn.xxstudy.assistant.rag.RagSource> = emptyList()
            var ragRetrievalInfo: String? = null
            val isExplicitRagLookup = cn.xxstudy.assistant.rag.RagQueryNormalizer
                .isExplicitKnowledgeLookup(prompt)
            val ragQuery = cn.xxstudy.assistant.rag.RagQueryNormalizer.normalize(prompt)
            if (AppSettings.isObsidianRagEnabled.value) {
                try {
                    val matches = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        // 默认只检索当前问题，避免切换话题时被上一轮内容污染。
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
                        ragRetrievalInfo = "$modeName · ${seeds.size} 个命中 · ${retrievalMillis} ms"
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
                        if (!isExplicitRagLookup) {
                            ragContextPrompt = cn.xxstudy.assistant.rag.RagPromptBuilder.build(matches, prompt)
                        }
                        android.util.Log.i("MainViewModel", "RAG: Injected ${matches.size} chunks into prompt")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "RAG retrieval error", e)
                }
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
            if (isExplicitRagLookup && ragMatches.isEmpty()) {
                val noMatchAnswer = cn.xxstudy.assistant.rag.RagStatusResponse.noReliableMatch(
                    query = ragQuery,
                    docCount = AppSettings.ragDocCount.value,
                    threshold = AppSettings.ragScoreThreshold.value
                )
                _chatMessages.value = _chatMessages.value.map { message ->
                    if (message.id == thinkingId) {
                        message.copy(
                            text = noMatchAnswer,
                            isThinking = false,
                            isThinkingActive = false,
                            metrics = "知识库未命中",
                            isStreaming = false,
                            knowledgeStage = null
                        )
                    } else {
                        message
                    }
                }
                if (currentThinkingId == thinkingId) {
                    currentThinkingId = null
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
                }
                return@launch
            }

            val systemPrompt = AppSettings.getEffectiveSystemPrompt(currentModel)
            // The repository decides when native KV is fresh, including RAG -> zero-hit
            // chat transitions; UI message count alone cannot identify that boundary.
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
                            newList[idx] = newList[idx].copy(
                                text = snapshot.answerText,
                                thinkingText = snapshot.thinkingText,
                                isThinkingActive = snapshot.isThinkingActive,
                                isThinking = false,
                                isStreaming = true
                            )
                            _chatMessages.value = newList
                        }
                    }
                }
            }

            try {
                // 调用带有打断检测的推理方法
                val rawResponse = repository.generateConversation(
                    mode = conversationMode,
                    isFirstTurn = isFirstTurn,
                    buildPrompt = { includeSystem ->
                        cn.xxstudy.assistant.repository.ConversationPromptBuilder.build(
                            systemPrompt = systemPrompt, question = prompt, ragContext = ragContextPrompt,
                            includeSystem = includeSystem, disableThinking = disableThinkingForRequest
                        )
                    },
                    onToken = { token -> channel.trySend(token) }
                )

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
                        matches = ragMatches
                    )
                }
                var groundedText = ragAnswer.text
                if (ragAnswer.isFallback) {
                    android.util.Log.w(
                        "MainViewModel",
                        "RAG answer failed grounding check; offering source panel"
                    )
                }

                // 后处理：将模型回答中的笔记引用（如 [Fluro.md]）转为 Obsidian 可点击深链接
                if (ragMatches.isNotEmpty()) {
                    val docNames = ragMatches.map { it.docName }.distinct()
                    for (doc in docNames) {
                        val link = cn.xxstudy.assistant.rag.RagGroundingGuard.buildObsidianLink(doc)
                        if (link != null) {
                            // 替换 [docName] 格式的引用为 [docName](obsidian://...)
                            groundedText = groundedText.replace("[$doc]", "[$doc]($link)")
                            // 也处理不带 .md 后缀的引用
                            val noExt = doc.removeSuffix(".md")
                            groundedText = groundedText.replace("[$noExt]", "[$noExt]($link)")
                        }
                    }
                }

                val parsedActions = if (ragMatches.isEmpty()) {
                    IntentParser.parse(groundedText)
                } else {
                    null
                }
                if (parsedActions != null) {
                    var executionSpeech: String? = null
                    // 1. 言出法随：后台静默/自动执行识别出的硬件或系统指令，无需用户手动点击
                    try {
                        for (action in parsedActions) {
                            val result = cn.xxstudy.assistant.intent.ActionRegistry.execute(getApplication(), action)
                            if (!result.isSuccess) {
                                // 执行失败或未找到联系人：使用执行结果中的具体提示作为语音播报
                                executionSpeech = result.message
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // 2. 意图识别结果：使用优美自然的中文语音播报替代生硬冷冰冰的原始 JSON
                    if (AppSettings.ttsAutoPlay.value) {
                        val speechText = executionSpeech ?: IntentParser.formatForSpeech(parsedActions)
                        speakMessage(thinkingId, speechText)
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

                // 推理完成后，附加上最终的性能指标
                val parts = rawResponse.split("<|metrics|>")
                val metricsInfo = if (parts.size > 1) {
                    try {
                        val j = org.json.JSONObject(parts[1])
                        "首字: ${j.optLong("ttft_ms")} ms | 耗时: ${j.optLong("total_ms")} ms | 速度: ${j.optDouble("speed")} tk/s"
                    } catch(e: Exception) { parts[1] }
                } else null

                val finalUpdatedList = _chatMessages.value.map {
                    if (it.id == thinkingId) {
                        it.copy(
                            text = groundedText,
                            thinkingText = finalSnapshot.thinkingText,
                            isThinkingActive = false,
                            isThinking = false,
                            metrics = metricsInfo,
                            isKnowledgeExcerpt = ragAnswer.isFallback,
                            isStreaming = false,
                            knowledgeStage = null
                        )
                    } else it
                }
                _chatMessages.value = finalUpdatedList

                // 兜底朗读：仅在未开启流式切句且非意图识别消息时执行整句播报
                if (chunker == null && parsedActions == null && !ragAnswer.isFallback && AppSettings.ttsAutoPlay.value && groundedText.isNotBlank() && groundedText != "（回复为空）") {
                    speakMessage(thinkingId, groundedText)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 收到新消息打断时，安全退出并停止当前朗读
                channel.close()
                uiUpdaterJob.cancel()
                speechManager.stopSpeaking()
                _speakingMessageId.value = null
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Answer generation failed", e)
                channel.close()
                uiUpdaterJob.cancel()
                speechManager.stopSpeaking()
                _speakingMessageId.value = null
                _chatMessages.value = _chatMessages.value.map { message ->
                    if (message.id == thinkingId) message.copy(
                        text = if (ragSources.isNotEmpty()) "本次摘要生成失败，可查看下方知识库原文，或重试。" else "本次回答生成失败，请重试。",
                        isKnowledgeExcerpt = ragSources.isNotEmpty()
                    ) else message
                }
            } finally {
                _chatMessages.value = _chatMessages.value.map { message ->
                    if (message.id == thinkingId && (message.isStreaming || message.knowledgeStage != null)) {
                        message.finishInterruptedGeneration()
                    } else message
                }
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
            val parsedActions = IntentParser.parse(text)
            val speechContent = if (parsedActions != null) {
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
            repository.stopGeneration()
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
        speechManager.stopSpeaking()
        _speakingMessageId.value = null
        currentGenerationJob?.cancel()
        currentThinkingId = null
        repository.stopGeneration()
        viewModelScope.launch {
            repository.resetSession()
        }
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
