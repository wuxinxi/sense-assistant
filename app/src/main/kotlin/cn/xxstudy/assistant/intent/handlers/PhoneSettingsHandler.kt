package cn.xxstudy.assistant.intent.handlers

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import cn.xxstudy.assistant.intent.ActionHandler
import cn.xxstudy.assistant.intent.ActionRequest
import cn.xxstudy.assistant.intent.ActionUiDescriptor
import cn.xxstudy.assistant.intent.ExecutionResult

/**
 * 手机系统底层设置与硬件参数调节处理器
 */
class PhoneSettingsHandler : ActionHandler {
    override val actionName: String = "phone_settings"

    override suspend fun execute(context: Context, request: ActionRequest, overrideState: String?): ExecutionResult {
        val setting = request.optString("setting", "target")?.lowercase() ?: "general"
        val effectiveState = (overrideState ?: request.optString("state") ?: "on").lowercase()

        return when (setting) {
            "flashlight" -> {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                if (cameraManager != null) {
                    val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                        try {
                            cameraManager.getCameraCharacteristics(id)
                                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                        } catch (e: Exception) { false }
                    } ?: cameraManager.cameraIdList.firstOrNull()

                    if (cameraId != null) {
                        val turnOn = effectiveState !in listOf("off", "close")
                        try {
                            cameraManager.setTorchMode(cameraId, turnOn)
                            ExecutionResult(true, if (turnOn) "手电筒已开启" else "手电筒已关闭")
                        } catch (e: Exception) {
                            ExecutionResult(false, "手电筒控制异常：${e.localizedMessage}")
                        }
                    } else {
                        ExecutionResult(false, "未检测到设备闪光灯")
                    }
                } else {
                    ExecutionResult(false, "无法获取相机硬件服务")
                }
            }
            "ringer_mode" -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager != null) {
                    when (effectiveState) {
                        "silent" -> {
                            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                            ExecutionResult(true, "已开启静音模式")
                        }
                        "vibrate" -> {
                            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                            ExecutionResult(true, "已开启震动模式")
                        }
                        else -> {
                            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                            ExecutionResult(true, "已恢复正常响铃")
                        }
                    }
                } else ExecutionResult(false, "无法调节响铃模式")
            }
            "volume" -> {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager != null) {
                    val adj = request.optString("adjustment", "adjust") ?: ""
                    val v = request.optString("value") ?: ""
                    if (v == "0" || effectiveState == "silent") {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                        ExecutionResult(true, "已将媒体音量静音")
                    } else if (v.isNotBlank()) {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val targetVol = ((v.toIntOrNull() ?: 50) * maxVol / 100).coerceIn(0, maxVol)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                        ExecutionResult(true, "已将音量调整为 ${v}%")
                    } else if (adj.contains("+") || adj.contains("up")) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                        ExecutionResult(true, "已调大媒体音量")
                    } else if (adj.contains("-") || adj.contains("down")) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                        ExecutionResult(true, "已调小媒体音量")
                    } else {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                        ExecutionResult(true, "已调出音量控制面板")
                    }
                } else ExecutionResult(false, "无法获取音频管理器")
            }
            "bluetooth" -> {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                ExecutionResult(true, if (effectiveState in listOf("off", "close")) "已前往蓝牙设置关闭" else "已前往蓝牙设置开启")
            }
            "wifi" -> {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                ExecutionResult(true, if (effectiveState in listOf("off", "close")) "已前往无线局域网设置关闭" else "已前往无线局域网设置开启")
            }
            "location" -> {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                ExecutionResult(true, if (effectiveState in listOf("off", "close")) "已前往位置信息设置关闭" else "已前往位置信息设置开启")
            }
            "airplane_mode" -> {
                val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                ExecutionResult(true, "已打开飞行模式设置")
            }
            "brightness", "dark_mode", "eye_care" -> {
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                ExecutionResult(true, "已打开显示与亮度设置")
            }
            else -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                ExecutionResult(true, "已为您打开系统设置")
            }
        }
    }

    override fun formatSpeech(request: ActionRequest): String {
        val set = request.optString("setting", "target") ?: "系统设置"
        val setCn = getSettingChinese(set)
        val state = request.optString("state")
        return when (state) {
            "on" -> "打开$setCn"
            "off" -> "关闭$setCn"
            "silent" -> "开启静音模式"
            "vibrate" -> "开启震动模式"
            "normal" -> "恢复正常响铃"
            "show_ui" -> "调出音量控制面板"
            else -> {
                val adj = request.optString("adjustment", "adjust") ?: ""
                val v = request.optString("value") ?: ""
                if (v == "0") "开启静音"
                else if (v == "100") "将${setCn}调到最大"
                else if (v.isNotBlank()) "将${setCn}设为${v}%"
                else if (adj.contains("+")) "调大${setCn}"
                else if (adj.contains("-")) "调小${setCn}"
                else "${setCn} $adj $v".trim()
            }
        }
    }

    override fun getUiDescriptor(request: ActionRequest, currentState: String?): ActionUiDescriptor {
        val set = request.optString("setting", "target") ?: "系统设置"
        val setCn = getSettingChinese(set)
        val effectiveState = currentState ?: request.optString("state")
        val isToggleable = set in listOf("flashlight", "ringer_mode", "bluetooth", "wifi", "location", "dark_mode", "eye_care", "airplane_mode")
        val isOn = (effectiveState ?: "on").lowercase() !in listOf("off", "close", "silent")

        val parts = mutableListOf("设置项: $setCn")
        if (effectiveState != null) {
            val stCn = when (effectiveState) {
                "on" -> "开启"
                "off" -> "关闭"
                "silent" -> "静音"
                "vibrate" -> "震动"
                "normal" -> "正常响铃"
                else -> effectiveState
            }
            parts.add("动作: $stCn")
        }
        request.optString("adjustment", "adjust")?.let { parts.add("调节: $it") }
        request.optString("value")?.let { parts.add("设定值: $it") }

        return ActionUiDescriptor(
            title = "系统设置调节",
            summary = parts.joinToString(" | "),
            icon = Icons.Default.Settings,
            iconTint = Color(0xFF2196F3),
            isToggleable = isToggleable,
            isOn = isOn
        )
    }

    private fun getSettingChinese(setting: String): String = when (setting.lowercase()) {
        "flashlight" -> "手电筒"
        "ringer_mode" -> "响铃模式"
        "bluetooth" -> "蓝牙"
        "wifi" -> "无线局域网"
        "brightness" -> "屏幕亮度"
        "volume" -> "系统音量"
        "location" -> "定位服务"
        "airplane_mode" -> "飞行模式"
        "low_power_mode" -> "省电模式"
        "dark_mode" -> "深色模式"
        "eye_care" -> "护眼模式"
        else -> setting
    }
}
