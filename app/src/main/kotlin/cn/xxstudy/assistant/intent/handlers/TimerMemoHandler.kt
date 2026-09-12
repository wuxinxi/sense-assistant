package cn.xxstudy.assistant.intent.handlers

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.graphics.Color
import cn.xxstudy.assistant.intent.ActionHandler
import cn.xxstudy.assistant.intent.ActionRequest
import cn.xxstudy.assistant.intent.ActionUiDescriptor
import cn.xxstudy.assistant.intent.ExecutionResult

/**
 * 倒计时、闹钟与日程备忘处理器
 */
class TimerMemoHandler : ActionHandler {
    override val actionName: String = "timer_memo"

    override suspend fun execute(context: Context, request: ActionRequest, overrideState: String?): ExecutionResult {
        val type = request.optString("type", "mode")?.lowercase() ?: "timer"
        val duration = request.optInt("duration_seconds", "durationSeconds", "duration")
        val label = request.optString("label", "content", "message") ?: "智能提醒"

        return try {
            when (type) {
                "timer" -> {
                    val sec = duration ?: 300
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, sec)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ExecutionResult(true, "已为您创建 ${sec / 60} 分钟“$label”倒计时")
                }
                "alarm" -> {
                    val timeStr = request.optString("time") ?: "08:00"
                    val parts = timeStr.split(":").mapNotNull { it.toIntOrNull() }
                    val hour = if (parts.isNotEmpty()) parts[0] else 8
                    val minute = if (parts.size > 1) parts[1] else 0

                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ExecutionResult(true, "已为您设置 ${String.format("%02d:%02d", hour, minute)} “$label”闹钟")
                }
                else -> {
                    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ExecutionResult(true, "已为您打开时钟与备忘应用")
                }
            }
        } catch (e: Exception) {
            ExecutionResult(false, "设置提醒失败：${e.localizedMessage}")
        }
    }

    override fun formatSpeech(request: ActionRequest): String {
        val type = request.optString("type", "mode") ?: "提醒"
        val typeCn = when (type) {
            "timer" -> "倒计时"
            "alarm" -> "闹钟"
            "memo" -> "备忘录"
            "reminder" -> "日程提醒"
            else -> type
        }
        val dur = request.optInt("duration_seconds", "durationSeconds", "duration")?.let { "${it / 60}分钟" } ?: ""
        val t = request.optString("time") ?: ""
        val lbl = request.optString("label", "content") ?: ""
        return if (dur.isNotBlank()) "设置$dur$lbl$typeCn"
        else if (t.isNotBlank()) "在$t 设置$lbl$typeCn"
        else "记录$lbl$typeCn"
    }

    override fun getUiDescriptor(request: ActionRequest, currentState: String?): ActionUiDescriptor {
        val type = request.optString("type", "mode") ?: "提醒"
        val typeCn = when (type) {
            "timer" -> "倒计时"
            "alarm" -> "闹钟"
            "memo" -> "备忘录"
            "reminder" -> "日程提醒"
            else -> type
        }
        val summaryParts = mutableListOf("类型: $typeCn")
        request.optInt("duration_seconds", "durationSeconds", "duration")?.let { summaryParts.add("时长: ${it / 60} 分钟") }
        request.optString("time")?.let { summaryParts.add("时间: $it") }
        request.optString("label", "content")?.let { summaryParts.add("备注: $it") }

        return ActionUiDescriptor(
            title = "倒计时与备忘",
            summary = summaryParts.joinToString(" | "),
            icon = Icons.Default.Timer,
            iconTint = Color(0xFF9C27B0),
            isToggleable = false
        )
    }
}
