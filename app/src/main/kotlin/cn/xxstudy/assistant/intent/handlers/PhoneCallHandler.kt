package cn.xxstudy.assistant.intent.handlers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
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

        val (dialNumber, feedback) = if (isDirectNumber) {
            rawTarget to "已填入拨号盘呼叫 $rawTarget"
        } else if (rawTarget.isNotBlank()) {
            val match = ContactHelper.findContact(context, rawTarget)
            if (match != null) {
                match.phoneNumber to "已找到联系人“${match.displayName}”并填入号码：${match.phoneNumber}"
            } else {
                "" to "通讯录未找到“$rawTarget”，已打开拨号盘"
            }
        } else {
            "" to "已打开拨号盘"
        }

        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = if (dialNumber.isNotBlank()) {
                    Uri.parse("tel:$dialNumber")
                } else {
                    Uri.parse("tel:")
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ExecutionResult(true, feedback, dialNumber)
        } catch (e: Exception) {
            ExecutionResult(false, "调起拨号盘失败：${e.localizedMessage}")
        }
    }

    override fun formatSpeech(request: ActionRequest): String {
        val tgt = request.optString("contact", "number", "target") ?: "联系人"
        return "拨打 $tgt 的电话"
    }

    override fun getUiDescriptor(request: ActionRequest, currentState: String?): ActionUiDescriptor {
        val who = request.optString("contact", "number", "target") ?: "联系人"
        val num = request.optString("number")
        val summary = if (!num.isNullOrBlank() && num != who) "呼叫: $who | 号码: $num" else "呼叫: $who"
        return ActionUiDescriptor(
            title = "电话呼叫",
            summary = summary,
            icon = Icons.Default.Call,
            iconTint = Color(0xFF00C853),
            isToggleable = false
        )
    }
}
