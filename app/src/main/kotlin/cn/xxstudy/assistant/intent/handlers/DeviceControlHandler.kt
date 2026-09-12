package cn.xxstudy.assistant.intent.handlers

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.Color
import cn.xxstudy.assistant.intent.ActionHandler
import cn.xxstudy.assistant.intent.ActionRequest
import cn.xxstudy.assistant.intent.ActionUiDescriptor
import cn.xxstudy.assistant.intent.ExecutionResult
import cn.xxstudy.assistant.utils.DeviceHelper

/**
 * 开放式通用 IoT 智能家居设备控制处理器 (纯动态总线，无硬编码白名单)
 */
class DeviceControlHandler : ActionHandler {
    override val actionName: String = "device_control"

    override suspend fun execute(context: Context, request: ActionRequest, overrideState: String?): ExecutionResult {
        val deviceName = request.optString("device", "target", "name") ?: "智能设备"
        val state = overrideState ?: request.optString("state", "status") ?: "on"
        val feedback = DeviceHelper.dispatchDeviceControl(context, deviceName, state)
        return ExecutionResult(true, feedback)
    }

    override fun formatSpeech(request: ActionRequest): String {
        val dev = request.optString("device", "target", "name") ?: "智能设备"
        val st = DeviceHelper.formatState(request.optString("state"))
        return "$st $dev"
    }

    override fun getUiDescriptor(request: ActionRequest, currentState: String?): ActionUiDescriptor {
        val dev = request.optString("device", "target", "name") ?: "智能设备"
        val effectiveState = currentState ?: request.optString("state") ?: "on"
        val isOn = effectiveState.lowercase() !in listOf("off", "close", "dock", "stop")

        return ActionUiDescriptor(
            title = "智能家居控制",
            summary = "设备: $dev | 动作: ${DeviceHelper.formatState(effectiveState)}",
            icon = Icons.Default.Home,
            iconTint = Color(0xFF4CAF50),
            isToggleable = true,
            isOn = isOn
        )
    }
}
