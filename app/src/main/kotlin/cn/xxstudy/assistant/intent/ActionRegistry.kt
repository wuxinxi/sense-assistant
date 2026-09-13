package cn.xxstudy.assistant.intent

import android.content.Context
import cn.xxstudy.assistant.intent.handlers.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 意图能力注册中心与总线路由 (ActionRegistry)
 *
 * 核心特性：
 * 1. 动态插件化：支持任何模块或插件调用 register() 注入新能力（如音乐、导航、发信息）；
 * 2. 彻底解耦：execute()、formatSpeech() 与 getUiDescriptor() 均通过注册表分发，彻底消除了所有硬编码 when 分支；
 * 3. 健壮兜底：内置 FallbackActionHandler，未知指令自适应降级，永不抛异常。
 */
object ActionRegistry {
    private val handlers = ConcurrentHashMap<String, ActionHandler>()
    private val fallbackHandler = FallbackActionHandler()

    init {
        // 注册系统预置核心能力处理器
        register(AppLaunchHandler())
        register(PhoneCallHandler())
        register(SystemFeatureHandler())
        register(PhoneSettingsHandler())
        register(DeviceControlHandler())
        register(TimerMemoHandler())
    }

    /**
     * 动态注册能力处理器
     */
    fun register(handler: ActionHandler) {
        handlers[handler.actionName.lowercase()] = handler
    }

    /**
     * 注销指定能力处理器
     */
    fun unregister(actionName: String) {
        handlers.remove(actionName.lowercase())
    }

    /**
     * 查找匹配的处理器，未找到时返回通用兜底处理器
     */
    fun getHandler(actionName: String): ActionHandler {
        return handlers[actionName.lowercase()] ?: fallbackHandler
    }

    /**
     * 执行意图调度
     */
    suspend fun execute(context: Context, request: ActionRequest, overrideState: String? = null): ExecutionResult {
        val handler = getHandler(request.action)
        return try {
            handler.execute(context, request, overrideState)
        } catch (e: Exception) {
            ExecutionResult(false, "执行异常：${e.localizedMessage}")
        }
    }

    /**
     * 批量生成自然语音播报文案
     */
    fun formatSpeech(requests: List<ActionRequest>): String {
        if (requests.isEmpty()) return "好的，已为您解析操作指令。"
        val summaries = requests.map { request ->
            getHandler(request.action).formatSpeech(request)
        }
        return "好的，已为您执行：" + summaries.joinToString("，然后")
    }

    /**
     * 获取对应的 UI 描述符
     */
    fun getUiDescriptor(request: ActionRequest, currentState: String? = null, context: Context? = null): ActionUiDescriptor {
        return getHandler(request.action).getUiDescriptor(request, currentState, context)
    }
}
