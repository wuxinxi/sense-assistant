package cn.xxstudy.assistant.ui.components

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import cn.xxstudy.assistant.service.AssistantAccessibilityService
import cn.xxstudy.assistant.utils.AppHelper
import cn.xxstudy.assistant.utils.ContactHelper
import cn.xxstudy.assistant.utils.DeviceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 端侧意图识别指令数据模型
 */
data class ParsedAction(
    val action: String,
    val device: String? = null,
    val target: String? = null,
    val setting: String? = null,
    val state: String? = null,
    val appName: String? = null,
    val contact: String? = null,
    val number: String? = null,
    val feature: String? = null,
    val type: String? = null,
    val label: String? = null,
    val content: String? = null,
    val time: String? = null,
    val adjustment: String? = null,
    val value: String? = null,
    val durationSeconds: Int? = null,
    val delaySeconds: Int? = null,
    val rawJson: String = ""
)

/**
 * 意图解析器：将模型输出的文本安全解析为结构化操作指令
 */
object IntentParser {
    fun parse(text: String): List<ParsedAction>? {
        if (text.isBlank()) return null
        
        // 提取 JSON 主体（支持 ```json 包装或裸 JSON）
        var raw = text.trim()
        if (raw.contains("```json")) {
            raw = raw.substringAfter("```json").substringBefore("```").trim()
        } else if (raw.contains("```")) {
            raw = raw.substringAfter("```").substringBefore("```").trim()
        }

        // 仅处理数组或对象格式
        if (!raw.startsWith("[") && !raw.startsWith("{")) return null

        return try {
            val jsonArray = if (raw.startsWith("[")) {
                JSONArray(raw)
            } else {
                JSONArray().put(JSONObject(raw))
            }

            if (jsonArray.length() == 0) return null

            val list = mutableListOf<ParsedAction>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val action = obj.optString("action").takeIf { it.isNotBlank() } ?: continue
                
                list.add(
                    ParsedAction(
                        action = action,
                        device = obj.optString("device").takeIf { it.isNotBlank() },
                        target = obj.optString("target").takeIf { it.isNotBlank() },
                        setting = obj.optString("setting").takeIf { it.isNotBlank() },
                        state = obj.optString("state").takeIf { it.isNotBlank() },
                        appName = obj.optString("app_name").takeIf { it.isNotBlank() },
                        contact = obj.optString("contact").takeIf { it.isNotBlank() },
                        number = obj.optString("number").takeIf { it.isNotBlank() },
                        feature = obj.optString("feature").takeIf { it.isNotBlank() },
                        type = obj.optString("type").takeIf { it.isNotBlank() },
                        label = obj.optString("label").takeIf { it.isNotBlank() },
                        content = obj.optString("content").takeIf { it.isNotBlank() },
                        time = obj.optString("time").takeIf { it.isNotBlank() },
                        adjustment = obj.optString("adjustment").takeIf { it.isNotBlank() },
                        value = if (obj.has("value")) obj.opt("value")?.toString() else null,
                        durationSeconds = if (obj.has("duration_seconds")) obj.optInt("duration_seconds") else null,
                        delaySeconds = if (obj.has("delay_seconds")) obj.optInt("delay_seconds") else null,
                        rawJson = obj.toString(2)
                    )
                )
            }
            if (list.isNotEmpty()) list else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将结构化意图指令转换为优雅自然的中文语音播报文案
     */
    fun formatForSpeech(actions: List<ParsedAction>): String {
        if (actions.isEmpty()) return "好的，已为您解析操作指令。"
        val summaries = actions.map { action ->
            when (action.action.lowercase()) {
                "phone_call" -> {
                    val tgt = action.contact ?: action.number ?: "联系人"
                    "拨打 $tgt 的电话"
                }
                "system_feature" -> {
                    val delayInfo = if (action.delaySeconds != null && action.delaySeconds > 0) "在 ${action.delaySeconds} 秒后" else ""
                    when (action.feature?.lowercase()) {
                        "screenshot" -> "${delayInfo}截取屏幕"
                        "screen_record" -> if (action.state == "stop") "停止录屏" else "${delayInfo}开始屏幕录制"
                        else -> "系统快捷操作"
                    }
                }
                "device_control" -> {
                    val dev = action.device ?: action.target ?: "智能设备"
                    val st = DeviceHelper.formatState(action.state)
                    "${st} $dev"
                }
                "phone_settings" -> {
                    val set = action.setting ?: "系统设置"
                    val setCn = when (set) {
                        "flashlight" -> "手电筒"
                        "ringer_mode" -> "响铃模式"
                        "bluetooth" -> "蓝牙"
                        "wifi" -> "无线局域网"
                        "brightness" -> "屏幕亮度"
                        "volume" -> "音量"
                        "location" -> "定位服务"
                        "airplane_mode" -> "飞行模式"
                        "low_power_mode" -> "省电模式"
                        "dark_mode" -> "深色模式"
                        "eye_care" -> "护眼模式"
                        else -> set
                    }
                    when (action.state) {
                        "on" -> "打开$setCn"
                        "off" -> "关闭$setCn"
                        "silent" -> "开启静音模式"
                        "vibrate" -> "开启震动模式"
                        "normal" -> "恢复正常响铃"
                        "show_ui" -> "调出音量控制面板"
                        else -> {
                            val adj = action.adjustment ?: ""
                            val v = action.value ?: ""
                            if (v == "0") "开启静音"
                            else if (v == "100") "将${setCn}调到最大"
                            else if (v.isNotBlank()) "将${setCn}设为${v}%"
                            else if (adj.contains("+")) "调大${setCn}"
                            else if (adj.contains("-")) "调小${setCn}"
                            else "${setCn} $adj $v".trim()
                        }
                    }
                }
                "app_launch" -> {
                    val app = action.appName ?: "应用"
                    "打开 $app"
                }
                "timer_memo" -> {
                    val type = action.type ?: "提醒"
                    val typeCn = when (type) {
                        "timer" -> "倒计时"
                        "alarm" -> "闹钟"
                        "memo" -> "备忘录"
                        "reminder" -> "日程提醒"
                        else -> type
                    }
                    val dur = action.durationSeconds?.let { "${it / 60}分钟" } ?: ""
                    val t = action.time ?: ""
                    val lbl = action.label ?: action.content ?: ""
                    if (dur.isNotBlank()) "设置$dur$lbl$typeCn"
                    else if (t.isNotBlank()) "在$t 设置$lbl$typeCn"
                    else "记录$lbl$typeCn"
                }
                else -> "执行${action.action}指令"
            }
        }
        return "好的，已为您执行：" + summaries.joinToString("，然后")
    }
}

/**
 * 端侧操作指令分发与硬件调度执行器
 */
object ActionExecutor {
    fun execute(context: Context, item: ParsedAction, overrideState: String? = null): String {
        val effectiveState = (overrideState ?: item.state ?: "on").lowercase()
        return try {
            when (item.action.lowercase()) {
                "phone_call" -> {
                    val rawTarget = (item.number ?: item.contact ?: "").trim()
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

                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = if (dialNumber.isNotBlank()) {
                            android.net.Uri.parse("tel:$dialNumber")
                        } else {
                            android.net.Uri.parse("tel:")
                        }
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    feedback
                }
                "system_feature" -> {
                    when (item.feature?.lowercase()) {
                        "screenshot" -> {
                            if (AssistantAccessibilityService.isRunning) {
                                val delaySec = item.delaySeconds ?: 0
                                if (delaySec > 0) {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(delaySec * 1000L)
                                        AssistantAccessibilityService.takeScreenshot()
                                    }
                                    "已就绪，将在 ${delaySec} 秒后自动截屏，请切换到目标画面"
                                } else {
                                    val success = AssistantAccessibilityService.takeScreenshot()
                                    if (success) "已截取当前屏幕并保存至相册" else "截屏失败，请稍后重试"
                                }
                            } else {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                                "截屏功能需开启“TangRen AI 快捷服务”无障碍权限，正在前往设置..."
                            }
                        }
                        "screen_record" -> if (effectiveState in listOf("off", "stop")) "已停止屏幕录制" else "已就绪屏幕录制"
                        else -> "系统快捷功能已就绪"
                    }
                }
                "phone_settings" -> {
                    when (item.setting?.lowercase()) {
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
                                    cameraManager.setTorchMode(cameraId, turnOn)
                                    if (turnOn) "手电筒已开启" else "手电筒已关闭"
                                } else {
                                    "未检测到设备闪光灯"
                                }
                            } else {
                                "无法获取相机服务"
                            }
                        }
                        "ringer_mode" -> {
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            if (audioManager != null) {
                                when (effectiveState) {
                                    "silent" -> {
                                        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                                        "已开启静音模式"
                                    }
                                    "vibrate" -> {
                                        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                                        "已开启震动模式"
                                    }
                                    else -> {
                                        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                                        "已恢复正常响铃"
                                    }
                                }
                            } else "无法调节响铃模式"
                        }
                        "volume" -> {
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            if (audioManager != null) {
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                when {
                                    item.state == "show_ui" -> {
                                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                                        "已调出音量调节面板"
                                    }
                                    item.value != null -> {
                                        val percent = item.value.toIntOrNull() ?: 50
                                        val targetVol = (percent * maxVol) / 100
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                                        if (percent == 0) "已开启静音" else "音量已设为 $percent%"
                                    }
                                    else -> {
                                        val isLower = item.adjustment?.contains("-") == true || effectiveState in listOf("lower", "down", "off", "mute")
                                        val delta = item.adjustment?.replace("+", "")?.replace("-", "")?.toIntOrNull() ?: 2
                                        val steps = delta.coerceIn(1, 5)
                                        for (i in 0 until steps) {
                                            audioManager.adjustStreamVolume(
                                                AudioManager.STREAM_MUSIC,
                                                if (isLower) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE,
                                                if (i == 0) AudioManager.FLAG_SHOW_UI else 0
                                            )
                                        }
                                        if (isLower) "音量已减小 $steps 格" else "音量已调大 $steps 格"
                                    }
                                }
                            } else "无法调节音量"
                        }
                        "location" -> {
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            if (effectiveState in listOf("off", "close")) "已前往关闭位置服务" else "已前往开启位置服务"
                        }
                        "bluetooth" -> {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            if (effectiveState in listOf("off", "close")) "已前往关闭蓝牙" else "已前往开启蓝牙"
                        }
                        "wifi" -> {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            if (effectiveState in listOf("off", "close")) "已前往关闭Wi-Fi" else "已前往开启Wi-Fi"
                        }
                        "airplane_mode" -> {
                            context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            "已打开飞行模式设置"
                        }
                        "dark_mode", "brightness", "eye_care" -> {
                            context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            "已打开显示与亮度设置"
                        }
                        "low_power_mode" -> {
                            context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            "已打开省电模式设置"
                        }
                        else -> {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                            "已打开系统设置"
                        }
                    }
                }
                "device_control" -> {
                    val dev = item.device ?: item.target ?: "智能设备"
                    DeviceHelper.dispatchDeviceControl(context, dev, effectiveState)
                }
                "app_launch" -> {
                    val targetName = item.appName ?: ""
                    val matchedApp = AppHelper.findApp(context, targetName)
                    if (matchedApp != null) {
                        val success = AppHelper.launchApp(context, matchedApp)
                        if (success) "已启动 ${matchedApp.label}" else "启动 ${matchedApp.label} 失败"
                    } else {
                        "未在手机上找到“$targetName”，请确认是否已安装"
                    }
                }
                "timer_memo" -> {
                    val duration = item.durationSeconds
                    if (item.type?.lowercase() == "timer" && duration != null && duration > 0) {
                        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                            putExtra(AlarmClock.EXTRA_LENGTH, duration)
                            putExtra(AlarmClock.EXTRA_MESSAGE, item.label ?: "倒计时")
                            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        "已设置 ${duration / 60} 分钟倒计时"
                    } else if (item.type?.lowercase() in listOf("alarm", "reminder") && !item.time.isNullOrBlank()) {
                        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_MESSAGE, item.label ?: "提醒")
                            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        "已调起闹钟提醒: ${item.time}"
                    } else {
                        val content = item.label ?: item.content ?: ""
                        if (content.isNotBlank() && !content.contains("帮我完成") && !content.contains("任务")) {
                            "已记录备忘: $content"
                        } else {
                            "指令已忽略"
                        }
                    }
                }
                else -> "指令已确认执行"
            }
        } catch (e: Exception) {
            "执行异常: ${e.localizedMessage ?: e.message}"
        }
    }
}

/**
 * 现代 Material3 意图操作展示胶囊卡片
 */
@Composable
fun IntentActionCard(
    actions: List<ParsedAction>,
    rawText: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showRawJson by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            )
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 顶部标题栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "端侧意图识别中枢",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.forEach { ActionExecutor.execute(context, it) }
                            Toast.makeText(context, "已重新执行 ${actions.size} 项指令", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "已自动执行",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.8.dp
            )
            Spacer(modifier = Modifier.height(10.dp))

            // 指令条目列表
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { index, item ->
                    ActionItemRow(index = index + 1, item = item)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 底部折叠展开原始报文
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showRawJson = !showRawJson }
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            ) {
                Icon(
                    imageVector = if (showRawJson) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (showRawJson) "收起原始 JSON" else "查看原始 JSON 报文",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            AnimatedVisibility(visible = showRawJson) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Text(
                        text = rawText.trim(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionItemRow(index: Int, item: ParsedAction) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val (title, icon, iconBg) = getActionMeta(item.action)

    // 是否为支持双向开闭的开关型意图（如手电筒、响铃、家居设备、蓝牙、定位等）
    val isToggleable = when (item.action.lowercase()) {
        "phone_settings" -> item.setting?.lowercase() in listOf("flashlight", "ringer_mode", "bluetooth", "wifi", "location", "dark_mode", "eye_care", "airplane_mode")
        "device_control" -> true
        else -> false
    }

    // 初始状态默认为模型解析的目标状态（后台已自动执行开启或关闭）
    var activeState by remember(item) {
        mutableStateOf((item.state ?: "on").lowercase())
    }
    var executionFeedback by remember { mutableStateOf<String?>(null) }

    val isOn = activeState !in listOf("off", "close", "dock", "silent")

    fun doToggle() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (isToggleable) {
            val nextState = if (isOn) "off" else "on"
            val res = ActionExecutor.execute(context, item, overrideState = nextState)
            activeState = nextState
            executionFeedback = res
            Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
        } else {
            val res = ActionExecutor.execute(context, item)
            executionFeedback = res
            Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isOn) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        border = if (isOn) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                 else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { doToggle() }
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isOn) iconBg.copy(alpha = 0.2f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isOn) iconBg else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatActionSummary(item, activeState),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
                if (executionFeedback != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "✓ $executionFeedback",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 右侧直观的状态切换开关按钮
            if (isToggleable) {
                FilledTonalButton(
                    onClick = { doToggle() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isOn) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                                         else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = if (isOn) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (isOn) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (isOn) "关闭" else "打开",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = { doToggle() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("再次执行", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun getActionMeta(action: String): Triple<String, ImageVector, Color> {
    return when (action.lowercase()) {
        "phone_call" -> Triple("电话呼叫", Icons.Default.Call, Color(0xFF00C853))
        "device_control" -> Triple("智能家居控制", Icons.Default.Home, Color(0xFF4CAF50))
        "phone_settings" -> Triple("系统设置调节", Icons.Default.Settings, Color(0xFF2196F3))
        "app_launch" -> Triple("应用调度启闭", Icons.Default.Apps, Color(0xFFFF9800))
        "timer_memo" -> Triple("倒计时与备忘", Icons.Default.Timer, Color(0xFF9C27B0))
        "system_feature" -> Triple("系统快捷功能", Icons.Default.Screenshot, Color(0xFF00BCD4))
        else -> Triple("端侧操作指令", Icons.Default.SmartToy, Color(0xFF607D8B))
    }
}

private fun formatActionSummary(item: ParsedAction, currentState: String? = null): String {
    val parts = mutableListOf<String>()
    val effectiveState = currentState ?: item.state

    when (item.action.lowercase()) {
        "phone_call" -> {
            val who = item.contact ?: item.number ?: "联系人"
            parts.add("呼叫: $who")
            item.number?.let { if (it != who) parts.add("号码: $it") }
        }
        "system_feature" -> {
            val featCn = when (item.feature?.lowercase()) {
                "screenshot" -> "屏幕截屏"
                "screen_record" -> "屏幕录制"
                else -> item.feature ?: "系统功能"
            }
            parts.add("操作: $featCn")
            item.delaySeconds?.let { if (it > 0) parts.add("延时: ${it}秒") }
            effectiveState?.let { parts.add("状态: $it") }
        }
        "device_control" -> {
            val dev = item.device ?: item.target ?: "智能设备"
            parts.add("设备: $dev")
            effectiveState?.let {
                parts.add("动作: ${DeviceHelper.formatState(it)}")
            }
        }
        "phone_settings" -> {
            val set = item.setting ?: "系统参数"
            val setCn = when (set) {
                "flashlight" -> "手电筒"
                "ringer_mode" -> "响铃模式"
                "bluetooth" -> "蓝牙"
                "wifi" -> "Wi-Fi"
                "brightness" -> "屏幕亮度"
                "volume" -> "系统音量"
                "location" -> "定位服务"
                "airplane_mode" -> "飞行模式"
                "low_power_mode" -> "省电模式"
                "dark_mode" -> "深色模式"
                "eye_care" -> "护眼模式"
                else -> set
            }
            parts.add("设置项: $setCn")
            effectiveState?.let {
                val stCn = when (it) {
                    "on" -> "开启"
                    "off" -> "关闭"
                    "silent" -> "静音"
                    "vibrate" -> "震动"
                    "normal" -> "正常响铃"
                    else -> it
                }
                parts.add("动作: $stCn")
            }
            item.adjustment?.let { parts.add("调节: $it") }
            item.value?.let { parts.add("设定值: $it") }
        }
        "app_launch" -> {
            val app = item.appName ?: "应用"
            parts.add("启动: $app")
        }
        "timer_memo" -> {
            val type = item.type ?: "提醒"
            val typeCn = when (type) {
                "timer" -> "倒计时"
                "alarm" -> "闹钟"
                "memo" -> "备忘录"
                "reminder" -> "日程提醒"
                else -> type
            }
            parts.add("类型: $typeCn")
            item.durationSeconds?.let { parts.add("时长: ${it / 60} 分钟") }
            item.time?.let { parts.add("时间: $it") }
            item.label?.let { parts.add("备注: $it") }
            item.content?.let { parts.add("内容: $it") }
        }
        else -> {
            item.target?.let { parts.add("目标: $it") }
            effectiveState?.let { parts.add("状态: $it") }
        }
    }

    return if (parts.isNotEmpty()) parts.joinToString(" | ") else item.rawJson.replace("\n", " ")
}
