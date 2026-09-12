package cn.xxstudy.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
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
    val type: String? = null,
    val label: String? = null,
    val content: String? = null,
    val time: String? = null,
    val adjustment: String? = null,
    val value: String? = null,
    val durationSeconds: Int? = null,
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
                        type = obj.optString("type").takeIf { it.isNotBlank() },
                        label = obj.optString("label").takeIf { it.isNotBlank() },
                        content = obj.optString("content").takeIf { it.isNotBlank() },
                        time = obj.optString("time").takeIf { it.isNotBlank() },
                        adjustment = obj.optString("adjustment").takeIf { it.isNotBlank() },
                        value = if (obj.has("value")) obj.opt("value")?.toString() else null,
                        durationSeconds = if (obj.has("duration_seconds")) obj.optInt("duration_seconds") else null,
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
                "device_control" -> {
                    val dev = action.device ?: action.target ?: "设备"
                    val devCn = when (dev) {
                        "living_room_light" -> "客厅大灯"
                        "bedroom_ac" -> "卧室空调"
                        "ac" -> "空调"
                        "all_lights" -> "全屋灯光"
                        "living_room_curtain" -> "客厅窗帘"
                        "vacuum_robot" -> "扫地机器人"
                        "air_purifier" -> "空气净化器"
                        else -> dev
                    }
                    when (action.state) {
                        "on" -> "打开$devCn"
                        "off" -> "关闭$devCn"
                        "open" -> "拉开$devCn"
                        "close" -> "合上$devCn"
                        "start" -> "启动$devCn"
                        "dock" -> "$devCn 回充"
                        else -> "$devCn ${action.state ?: "操作"}"
                    }
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
                        "airplane_mode" -> "飞行模式"
                        "low_power_mode" -> "省电模式"
                        else -> set
                    }
                    when (action.state) {
                        "on" -> "打开$setCn"
                        "off" -> "关闭$setCn"
                        "silent" -> "开启静音模式"
                        "vibrate" -> "开启震动模式"
                        "normal" -> "恢复正常响铃"
                        else -> {
                            val adj = action.adjustment ?: ""
                            val v = action.value ?: ""
                            "$setCn $adj $v".trim()
                        }
                    }
                }
                "app_launch" -> {
                    val app = action.appName ?: "应用"
                    val appCn = when (app) {
                        "WeChat" -> "微信"
                        "Camera" -> "系统相机"
                        "NetEaseMusic" -> "网易云音乐"
                        "Settings" -> "设置"
                        "Gallery" -> "相册"
                        "Amap" -> "高德地图"
                        "Calculator" -> "计算器"
                        "Notes" -> "备忘录"
                        "Taobao" -> "淘宝"
                        "Browser" -> "浏览器"
                        else -> app
                    }
                    "打开$appCn"
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
 * 现代 Material3 意图操作展示胶囊卡片
 */
@Composable
fun IntentActionCard(
    actions: List<ParsedAction>,
    rawText: String,
    modifier: Modifier = Modifier
) {
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
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "${actions.size} 项指令",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
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
    val (title, icon, iconBg) = getActionMeta(item.action)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconBg,
                    modifier = Modifier.size(18.dp)
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
                    text = formatActionSummary(item),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

private fun getActionMeta(action: String): Triple<String, ImageVector, Color> {
    return when (action.lowercase()) {
        "device_control" -> Triple("智能家居控制", Icons.Default.Home, Color(0xFF4CAF50))
        "phone_settings" -> Triple("系统设置调节", Icons.Default.Settings, Color(0xFF2196F3))
        "app_launch" -> Triple("应用调度启闭", Icons.Default.Apps, Color(0xFFFF9800))
        "timer_memo" -> Triple("倒计时与备忘", Icons.Default.Timer, Color(0xFF9C27B0))
        else -> Triple("端侧操作指令", Icons.Default.SmartToy, Color(0xFF607D8B))
    }
}

private fun formatActionSummary(item: ParsedAction): String {
    val parts = mutableListOf<String>()

    when (item.action.lowercase()) {
        "device_control" -> {
            val dev = item.device ?: item.target ?: "未知设备"
            val devCn = when (dev) {
                "living_room_light" -> "客厅大灯"
                "bedroom_ac" -> "卧室空调"
                "ac" -> "空调"
                "all_lights" -> "全屋灯光"
                "living_room_curtain" -> "客厅窗帘"
                "vacuum_robot" -> "扫地机器人"
                "air_purifier" -> "空气净化器"
                else -> dev
            }
            parts.add("设备: $devCn")
            item.state?.let {
                val stCn = when (it) {
                    "on" -> "开启"
                    "off" -> "关闭"
                    "open" -> "拉开"
                    "close" -> "合上"
                    "start" -> "启动清扫"
                    "dock" -> "回充"
                    else -> it
                }
                parts.add("状态: $stCn")
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
                "airplane_mode" -> "飞行模式"
                "low_power_mode" -> "省电模式"
                else -> set
            }
            parts.add("设置项: $setCn")
            item.state?.let {
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
            val appCn = when (app) {
                "WeChat" -> "微信"
                "Camera" -> "系统相机"
                "NetEaseMusic" -> "网易云音乐"
                "Settings" -> "系统设置"
                "Gallery" -> "系统相册"
                "Amap" -> "高德地图"
                "Calculator" -> "计算器"
                "Notes" -> "备忘录"
                "Taobao" -> "淘宝"
                "Browser" -> "浏览器"
                else -> app
            }
            parts.add("启动: $appCn")
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
            item.state?.let { parts.add("状态: $it") }
        }
    }

    return if (parts.isNotEmpty()) parts.joinToString(" | ") else item.rawJson.replace("\n", " ")
}
