package cn.xxstudy.assistant.intent.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.ui.graphics.Color
import cn.xxstudy.assistant.intent.ActionHandler
import cn.xxstudy.assistant.intent.ActionRequest
import cn.xxstudy.assistant.intent.ActionUiDescriptor
import cn.xxstudy.assistant.intent.ExecutionResult
import cn.xxstudy.assistant.utils.ContactHelper

/**
 * 电话呼叫与通讯录检索处理器
 */
class PhoneCallHandler : ActionHandler {
    override val actionName: String = "phone_call"

    override suspend fun execute(context: Context, request: ActionRequest, overrideState: String?): ExecutionResult {
        val rawTarget = request.optString("number", "contact", "target", "name")?.trim() ?: ""
        val isDirectNumber = rawTarget.isNotBlank() && rawTarget.matches(Regex("^[0-9+*#]+$"))

        if (overrideState == "dialer") {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return ExecutionResult(true, "已为您打开拨号盘")
        }

        if (isDirectNumber) {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$rawTarget")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return ExecutionResult(true, "已填入拨号盘呼叫 $rawTarget", rawTarget)
        } else if (rawTarget.isNotBlank()) {
            val match = ContactHelper.findContact(context, rawTarget)
            if (match != null) {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:${match.phoneNumber}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return ExecutionResult(true, "已找到联系人“${match.displayName}”并填入号码：${match.phoneNumber}", match.phoneNumber)
            } else {
                // 通讯录查无此人：坚决不自动跳转，在卡片上显示未找到联系人，按钮提供打开拨号盘
                return ExecutionResult(
                    isSuccess = false,
                    message = "找不到联系人“$rawTarget”",
                    data = mapOf("notFound" to true, "contact" to rawTarget)
                )
            }
        } else {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return ExecutionResult(true, "已为您打开拨号盘")
        }
    }

    override fun formatSpeech(request: ActionRequest): String {
        val tgt = request.optString("contact", "number", "target") ?: ""
        return if (tgt.isNotBlank()) "拨打 $tgt 的电话" else "打开拨号盘"
    }

    override fun getUiDescriptor(request: ActionRequest, currentState: String?): ActionUiDescriptor {
        return getUiDescriptor(request, currentState, null)
    }

    override fun getUiDescriptor(request: ActionRequest, currentState: String?, context: Context?): ActionUiDescriptor {
        val rawTarget = request.optString("number", "contact", "target", "name")?.trim() ?: ""
        val isDirectNumber = rawTarget.isNotBlank() && rawTarget.matches(Regex("^[0-9+*#]+$"))

        if (currentState == "dialer" || rawTarget.isBlank()) {
            return ActionUiDescriptor(
                title = "电话拨号盘",
                summary = "打开系统电话拨号盘",
                icon = Icons.Default.Dialpad,
                iconTint = Color(0xFF00C853),
                isToggleable = false,
                actionButtonText = "打开拨号盘",
                actionButtonIcon = Icons.Default.Dialpad,
                actionOverrideState = "dialer"
            )
        }

        if (isDirectNumber) {
            return ActionUiDescriptor(
                title = "呼叫号码",
                summary = rawTarget,
                icon = Icons.Default.Call,
                iconTint = Color(0xFF00C853),
                isToggleable = false,
                actionButtonText = "拨打电话",
                actionButtonIcon = Icons.Default.Call,
                actionOverrideState = null
            )
        }

        val match = if (context != null) ContactHelper.findContact(context, rawTarget) else null
        return if (context != null && match == null) {
            ActionUiDescriptor(
                title = "未找到联系人",
                summary = "通讯录查无“$rawTarget”",
                icon = Icons.Default.PhoneMissed,
                iconTint = Color(0xFFE53935),
                isToggleable = false,
                actionButtonText = "打开拨号盘",
                actionButtonIcon = Icons.Default.Dialpad,
                actionOverrideState = "dialer"
            )
        } else {
            val displayName = match?.displayName ?: rawTarget
            val num = match?.phoneNumber ?: request.optString("number")
            val (title, summary) = if (!num.isNullOrBlank() && num != displayName) {
                "呼叫 $displayName" to num
            } else {
                "呼叫 $displayName" to "点击拨打电话"
            }
            ActionUiDescriptor(
                title = title,
                summary = summary,
                icon = Icons.Default.Call,
                iconTint = Color(0xFF00C853),
                isToggleable = false,
                actionButtonText = "拨打电话",
                actionButtonIcon = Icons.Default.Call,
                actionOverrideState = null
            )
        }
    }
}
