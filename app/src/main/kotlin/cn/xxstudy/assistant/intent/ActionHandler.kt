package cn.xxstudy.assistant.intent

import android.content.Context

/**
 * 意图能力处理器通用接口 (符合开闭原则 OCP)
 *
 * 任何新增的能力（无论是系统设置、App跳转、IoT联动，还是未来的音乐播放、路线导航、日程管理）
 * 均只需实现此接口并注册到 [ActionRegistry]，现有调度器与 UI 无需修改任何一行代码。
 */
interface ActionHandler {
    /**
     * 该 Handler 支持的主动作标识，如 "app_launch", "phone_call", "phone_settings" 等
     */
    val actionName: String

    /**
     * 是否能够处理该请求（支持基于参数进一步细分匹配，默认根据 actionName 判断）
     */
    fun canHandle(request: ActionRequest): Boolean = request.action.equals(actionName, ignoreCase = true)

    /**
     * 执行底层硬件或系统调度
     * @param context Android 上下文
     * @param request 结构化请求参数字典
     * @param overrideState 手动点击卡片切换状态时的覆盖状态（如 "on", "off"）
     */
    suspend fun execute(context: Context, request: ActionRequest, overrideState: String? = null): ExecutionResult

    /**
     * 生成自解释的自然中文语音播报文案
     */
    fun formatSpeech(request: ActionRequest): String

    /**
     * 生成通用 UI 卡片渲染描述符
     * @param request 请求数据
     * @param currentState 当前界面的状态（如 "on", "off"）
     */
    fun getUiDescriptor(request: ActionRequest, currentState: String? = null): ActionUiDescriptor
}
