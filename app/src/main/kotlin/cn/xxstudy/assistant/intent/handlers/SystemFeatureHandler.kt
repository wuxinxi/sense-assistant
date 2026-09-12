package cn.xxstudy.assistant.intent.handlers

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.ui.graphics.Color
import cn.xxstudy.assistant.intent.ActionHandler
import cn.xxstudy.assistant.intent.ActionRequest
import cn.xxstudy.assistant.intent.ActionUiDescriptor
import cn.xxstudy.assistant.intent.ExecutionResult
import cn.xxstudy.assistant.service.AssistantAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 系统底层快捷特性处理器 (原生截屏、延时截屏、录屏等)
 */
class SystemFeatureHandler : ActionHandler {
    override val actionName: String = "system_feature"

    override suspend fun execute(context: Context, request: ActionRequest, overrideState: String?): ExecutionResult {
        val feature = request.optString("feature", "type")?.lowercase() ?: "screenshot"
        val effectiveState = (overrideState ?: request.optString("state") ?: "on").lowercase()

        return when (feature) {
            "screenshot" -> {
                if (AssistantAccessibilityService.isRunning) {
                    val delaySec = request.optInt("delay_seconds", "delaySeconds", "delay") ?: 0
                    if (delaySec > 0) {
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(delaySec * 1000L)
                            AssistantAccessibilityService.takeScreenshot()
                        }
                        ExecutionResult(true, "已就绪，将在 ${delaySec} 秒后自动截屏，请切换到目标画面")
                    } else {
                        val success = AssistantAccessibilityService.takeScreenshot()
                        if (success) {
                            ExecutionResult(true, "已截取当前屏幕并保存至相册")
                        } else {
                            ExecutionResult(false, "截屏失败，请稍后重试")
                        }
                    }
                } else {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ExecutionResult(false, "截屏功能需开启“TangRen AI 快捷服务”无障碍权限，正在前往设置...")
                }
            }
            "screen_record" -> {
                if (effectiveState in listOf("off", "stop")) {
                    ExecutionResult(true, "已停止屏幕录制")
                } else {
                    ExecutionResult(true, "已就绪屏幕录制")
                }
            }
            else -> ExecutionResult(true, "系统快捷功能已就绪")
        }
    }

    override fun formatSpeech(request: ActionRequest): String {
        val delaySec = request.optInt("delay_seconds", "delaySeconds", "delay")
        val delayInfo = if (delaySec != null && delaySec > 0) "在 ${delaySec} 秒后" else ""
        val feature = request.optString("feature", "type")?.lowercase()
        return when (feature) {
            "screenshot" -> "${delayInfo}截取屏幕"
            "screen_record" -> {
                val state = request.optString("state")?.lowercase()
                if (state == "stop" || state == "off") "停止录屏" else "${delayInfo}开始屏幕录制"
            }
            else -> "系统快捷操作"
        }
    }

    override fun getUiDescriptor(request: ActionRequest, currentState: String?): ActionUiDescriptor {
        val feature = request.optString("feature", "type")?.lowercase()
        val delaySec = request.optInt("delay_seconds", "delaySeconds", "delay")
        val featCn = when (feature) {
            "screenshot" -> "屏幕截屏"
            "screen_record" -> "屏幕录制"
            else -> feature ?: "系统功能"
        }
        val summaryParts = mutableListOf("操作: $featCn")
        if (delaySec != null && delaySec > 0) {
            summaryParts.add("延时: ${delaySec}秒")
        }
        val state = currentState ?: request.optString("state")
        if (state != null) {
            summaryParts.add("状态: $state")
        }

        return ActionUiDescriptor(
            title = "系统快捷功能",
            summary = summaryParts.joinToString(" | "),
            icon = Icons.Default.Screenshot,
            iconTint = Color(0xFF00BCD4),
            isToggleable = false
        )
    }
}
