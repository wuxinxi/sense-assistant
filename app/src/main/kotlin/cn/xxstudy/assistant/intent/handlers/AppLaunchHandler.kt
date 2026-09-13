package cn.xxstudy.assistant.intent.handlers

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.ui.graphics.Color
import cn.xxstudy.assistant.intent.ActionHandler
import cn.xxstudy.assistant.intent.ActionRequest
import cn.xxstudy.assistant.intent.ActionUiDescriptor
import cn.xxstudy.assistant.intent.ExecutionResult
import cn.xxstudy.assistant.utils.AppHelper

/**
 * 纯动态应用启闭调度处理器 (基于 PackageManager 反射，无硬编码包名)
 */
class AppLaunchHandler : ActionHandler {
    override val actionName: String = "app_launch"

    override suspend fun execute(context: Context, request: ActionRequest, overrideState: String?): ExecutionResult {
        val appName = request.optString("app_name", "appName", "target", "name")
        if (appName.isNullOrBlank()) {
            return ExecutionResult(false, "未识别到需要启动的应用名称")
        }

        val app = AppHelper.findApp(context, appName)
        return if (app != null) {
            val launched = AppHelper.launchApp(context, app)
            if (launched) {
                ExecutionResult(true, "已为您打开应用“${app.label}”")
            } else {
                ExecutionResult(false, "无法启动“${app.label}”，请检查应用权限")
            }
        } else {
            ExecutionResult(false, "未找到名为“$appName”的应用，请确认是否已安装")
        }
    }

    override fun formatSpeech(request: ActionRequest): String {
        val appName = request.optString("app_name", "appName", "target", "name") ?: "应用"
        return "打开 $appName"
    }

    override fun getUiDescriptor(request: ActionRequest, currentState: String?): ActionUiDescriptor {
        val appName = request.optString("app_name", "appName", "target", "name") ?: "应用"
        return ActionUiDescriptor(
            title = "打开应用",
            summary = appName,
            icon = Icons.Default.Apps,
            iconTint = Color(0xFFFF9800),
            isToggleable = false,
            actionButtonText = "打开"
        )
    }
}
