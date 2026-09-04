package cn.xxstudy.assistant.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.xxstudy.assistant.repository.LlamaRepository
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
 * 纯粹的 UI 状态管理器，绝不允许在这里直接触碰 Context 或 View 实例。
 */
class MainViewModel : ViewModel() {

    private val repository = LlamaRepository()
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
        if (!_isModelLoaded.value) return
        
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
        }
    }
}
