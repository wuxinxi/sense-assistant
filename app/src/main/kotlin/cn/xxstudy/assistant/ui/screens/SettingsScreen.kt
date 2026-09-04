package cn.xxstudy.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.xxstudy.assistant.data.AppSettings
import cn.xxstudy.assistant.data.ColorTheme
import cn.xxstudy.assistant.data.ThemeMode
import cn.xxstudy.assistant.speech.SpeechManager
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    speechManager: SpeechManager,
    onBack: () -> Unit
) {
    val themeMode by AppSettings.themeMode.collectAsState()
    val colorTheme by AppSettings.colorTheme.collectAsState()
    val asrLanguage by AppSettings.asrLanguage.collectAsState()
    val asrAutoSend by AppSettings.asrAutoSend.collectAsState()
    val ttsAutoPlay by AppSettings.ttsAutoPlay.collectAsState()
    val ttsSpeechRate by AppSettings.ttsSpeechRate.collectAsState()
    val ttsPitch by AppSettings.ttsPitch.collectAsState()

    val isSpeaking by speechManager.isSpeaking.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置与个性化", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ==========================================
            // 卡片 1: 外观与主题
            // ==========================================
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "🎨 外观与主题",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(text = "明暗显示模式", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            val selected = mode == themeMode
                            FilterChip(
                                selected = selected,
                                onClick = { AppSettings.setThemeMode(mode) },
                                label = { Text(mode.label) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(text = "主题强调配色", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        ColorTheme.entries.forEach { theme ->
                            val selected = theme == colorTheme
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { AppSettings.setColorTheme(theme) }
                                    .padding(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(theme.primaryHex))
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = theme.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // 卡片 2: 语音识别 (ASR)
            // ==========================================
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "🎙️ 语音输入 (ASR)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(text = "识别偏好语言", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val languages = listOf("zh-CN" to "中文普通话", "en-US" to "英语 (English)", "auto" to "自动检测")
                        languages.forEach { (code, name) ->
                            val selected = code == asrLanguage
                            FilterChip(
                                selected = selected,
                                onClick = { AppSettings.setAsrLanguage(code) },
                                label = { Text(name) }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("语音输入后自动发送", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("说话结束后直接触发大模型提问，无需手动点击发送", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = asrAutoSend,
                            onCheckedChange = { AppSettings.setAsrAutoSend(it) }
                        )
                    }
                }
            }

            // ==========================================
            // 卡片 3: 语音播报 (TTS)
            // ==========================================
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "🔊 语音朗读播报 (TTS)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("回复完毕后自动朗读", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text("大模型生成结束后，自动通过系统离线语音引擎朗读回答", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = ttsAutoPlay,
                            onCheckedChange = { AppSettings.setTtsAutoPlay(it) }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 语速调节
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("朗读语速", fontSize = 13.sp)
                            Text("${((ttsSpeechRate * 10).roundToInt() / 10.0)}x", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = ttsSpeechRate,
                            onValueChange = { AppSettings.setTtsSpeechRate(it) },
                            valueRange = 0.5f..2.0f,
                            steps = 14
                        )
                    }

                    // 语调调节
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("朗读音调", fontSize = 13.sp)
                            Text("${((ttsPitch * 10).roundToInt() / 10.0)}x", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = ttsPitch,
                            onValueChange = { AppSettings.setTtsPitch(it) },
                            valueRange = 0.5f..2.0f,
                            steps = 14
                        )
                    }

                    // 试听按钮
                    Button(
                        onClick = {
                            if (isSpeaking) {
                                speechManager.stopSpeaking()
                            } else {
                                speechManager.speak(
                                    text = "您好！这是当前的语音播报音效测试。我正在本地离线运行。",
                                    speechRate = ttsSpeechRate,
                                    pitch = ttsPitch
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSpeaking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            if (isSpeaking) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isSpeaking) "停止试听" else "试听当前声音设置")
                    }
                }
            }

            // ==========================================
            // 卡片 4: 节点系统信息
            // ==========================================
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "ℹ️ 端侧节点参数", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("• 推理引擎: llama.cpp (C++17 / ARMv8.2-A dotprod 4线程并发)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• 默认权重: Qwen2.5-0.5B-Instruct-Q4_K_M (~491MB)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• 微服务端口: 0.0.0.0:8989 (OpenAI Compatible)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• 数据隐私: 100% 物理断网可用，所有交互在本地沙盒封闭运行", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
