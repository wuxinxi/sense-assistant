package cn.xxstudy.assistant.intent.handlers

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.Color
import cn.xxstudy.assistant.intent.ActionHandler
import cn.xxstudy.assistant.intent.ActionRequest
import cn.xxstudy.assistant.intent.ActionUiDescriptor
import cn.xxstudy.assistant.intent.ExecutionResult

/**
 * 兜底处理器：当模型输出未知或尚未编写专属 Handler 的 Action 时，提供优雅的降级处理与卡片展示
 */
class FallbackActionHandler : ActionHandler {
    override val actionName: String = "*"

    override fun canHandle(request: ActionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ActionRequest, overrideState: String?): ExecutionResult {
        return ExecutionResult(true, "已收到“${request.action}”指令（通用意图通道）")
    }

    override fun formatSpeech(request: ActionRequest): String {
        return "好的，为您执行${request.action}指令。"
    }

    override fun getUiDescriptor(request: ActionRequest, currentState: String?): ActionUiDescriptor {
        val parts = mutableListOf<String>()
        val target = request.optString("target", "name")
        if (target != null) parts.add("目标: $target")
        val state = currentState ?: request.optString("state")
        if (state != null) parts.add("状态: $state")

        val summary = if (parts.isNotEmpty()) parts.joinToString(" | ") else request.rawJson.replace("\n", " ").take(40)

        return ActionUiDescriptor(
            title = "扩展意图指令",
            summary = summary,
            icon = Icons.Default.SmartToy,
            iconTint = Color(0xFF607D8B),
            isToggleable = false
        )
    }
}
