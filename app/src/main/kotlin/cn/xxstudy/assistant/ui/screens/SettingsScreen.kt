package cn.xxstudy.assistant.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.xxstudy.assistant.data.AppSettings
import cn.xxstudy.assistant.data.ColorTheme
import cn.xxstudy.assistant.data.ThemeMode
import cn.xxstudy.assistant.speech.SpeechManager
import kotlin.math.roundToInt

data class CuratedSpeaker(
    val id: Int,
    val name: String,
    val role: String,
    val desc: String
)

data class MeloVoicePreset(
    val id: String,
    val name: String,
    val role: String,
    val pitch: Float,
    val speechRate: Float,
    val desc: String,
    val icon: ImageVector
)

val MELO_VOICE_PRESETS = listOf(
    MeloVoicePreset(
        id = "default",
        name = "标准助手",
        role = "原声",
        pitch = 1.0f,
        speechRate = 1.0f,
        desc = "清晰亲和 · 44.1kHz 母带原声女声",
        icon = Icons.Default.Face
    ),
    MeloVoicePreset(
        id = "male",
        name = "沉稳男声",
        role = "青年男声",
        pitch = 0.82f,
        speechRate = 0.96f,
        desc = "低沉磁性 · 极客青年男声音色",
        icon = Icons.Default.RecordVoiceOver
    ),
    MeloVoicePreset(
        id = "girl",
        name = "元气少女",
        role = "灵动少女",
        pitch = 1.20f,
        speechRate = 1.05f,
        desc = "清亮甜美 · 灵动少女音色",
        icon = Icons.Default.Mood
    ),
    MeloVoicePreset(
        id = "scholar",
        name = "知性儒雅",
        role = "沉静叙事",
        pitch = 0.90f,
        speechRate = 0.92f,
        desc = "温和从容 · 知性读书质感",
        icon = Icons.Default.AutoStories
    ),
    MeloVoicePreset(
        id = "anchor",
        name = "敏捷播报",
        role = "新闻播音",
        pitch = 1.05f,
        speechRate = 1.20f,
        desc = "干练利落 · 资讯播报快节奏",
        icon = Icons.Default.FlashOn
    )
)

val OFFICIAL_CURATED_SPEAKERS = listOf(
    CuratedSpeaker(0, "官方默认", "标准女声", "Default · 规范播音"),
    CuratedSpeaker(10, "清亮少女", "Liliana", "轻快生动 · 少女感"),
    CuratedSpeaker(21, "正气男声", "刘备", "沉稳正气 · 经典男声"),
    CuratedSpeaker(33, "温和青年", "自然男声", "亲和叙事 · 自然沉静"),
    CuratedSpeaker(45, "儒雅博学", "诸葛亮", "富有哲思 · 讲解质感"),
    CuratedSpeaker(66, "播音主持", "Rule 女声", "标准普通话 · 规范清晰"),
    CuratedSpeaker(103, "沉稳长读", "数符示范", "平稳耐听 · 长文本/数字")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    speechManager: SpeechManager,
    onClearChatHistory: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val themeMode by AppSettings.themeMode.collectAsState()
    val colorTheme by AppSettings.colorTheme.collectAsState()
    val asrLanguage by AppSettings.asrLanguage.collectAsState()
    val asrAutoSend by AppSettings.asrAutoSend.collectAsState()
    val ttsAutoPlay by AppSettings.ttsAutoPlay.collectAsState()
    val ttsSpeechRate by AppSettings.ttsSpeechRate.collectAsState()
    val ttsPitch by AppSettings.ttsPitch.collectAsState()
    val ttsSpeakerId by AppSettings.ttsSpeakerId.collectAsState()
    val hapticEnabled by AppSettings.hapticEnabled.collectAsState()
    val localServerEnabled by AppSettings.localServerEnabled.collectAsState()

    val isSpeaking by speechManager.isSpeaking.collectAsState()
    val isBilingualTts by speechManager.isBilingualTts.collectAsState()
    val ttsNumSpeakers by speechManager.ttsNumSpeakers.collectAsState()

    // Dialog 状态控制
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showCreditsDialog by remember { mutableStateOf(false) }
    var isTtsTuningExpanded by remember { mutableStateOf(false) }

    fun triggerHaptic() {
        if (hapticEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置与个人中心",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        triggerHaptic()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ========================================================
            // 顶端: 节点身份与端侧安全卡片 (Header Card)
            // ========================================================
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TangRen 边缘 AI 节点",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "100% 物理断网隔离 · 本地沙盒保障",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ========================================================
            // 模块 1: AI 语音与交互体验
            // ========================================================
            SettingSectionGroup(title = "AI 语音与交互体验") {
                // 1. 识别偏好语言
                val langLabel = when (asrLanguage) {
                    "zh-CN" -> "中文普通话"
                    "en-US" -> "英语 (English)"
                    else -> "自动识别检测"
                }
                SettingItemRow(
                    icon = Icons.Default.Language,
                    iconBgColor = Color(0xFF2196F3),
                    title = "语音识别语言 (ASR)",
                    subtitle = "SenseVoice Small 多语种端侧即时转写",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(langLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        }
                    },
                    onClick = {
                        triggerHaptic()
                        showLanguageDialog = true
                    }
                )

                SettingRowDivider()

                // 2. 语音输入松手发送
                SettingItemRow(
                    icon = Icons.Default.Mic,
                    iconBgColor = Color(0xFF00BCD4),
                    title = "松手自动提问",
                    subtitle = "长按语音胶囊松开后，直接唤醒大模型作答",
                    trailing = {
                        Switch(
                            checked = asrAutoSend,
                            onCheckedChange = {
                                triggerHaptic()
                                AppSettings.setAsrAutoSend(it)
                            }
                        )
                    }
                )

                SettingRowDivider()

                // 3. 交互触觉反馈
                SettingItemRow(
                    icon = Icons.Default.Vibration,
                    iconBgColor = Color(0xFF9C27B0),
                    title = "触觉震动反馈",
                    subtitle = "长按录音、切换键盘与功能触发时伴随轻度震动",
                    trailing = {
                        Switch(
                            checked = hapticEnabled,
                            onCheckedChange = {
                                triggerHaptic()
                                AppSettings.setHapticEnabled(it)
                            }
                        )
                    }
                )

                SettingRowDivider()

                // 4. 语音回答自动播报
                SettingItemRow(
                    icon = Icons.Default.VolumeUp,
                    iconBgColor = Color(0xFFFF9800),
                    title = "答案语音朗读 (TTS)",
                    subtitle = if (isBilingualTts) "MeloTTS 44.1kHz 录音棚高保真 · 原生中英双语混读" else "AI 完整生成回答后，自动使用离线语音引擎发音",
                    trailing = {
                        Switch(
                            checked = ttsAutoPlay,
                            onCheckedChange = {
                                triggerHaptic()
                                AppSettings.setTtsAutoPlay(it)
                            }
                        )
                    }
                )

                SettingRowDivider()

                // 5. 展开/收起语音参数调优
                SettingItemRow(
                    icon = Icons.Default.Speed,
                    iconBgColor = Color(0xFFFF5722),
                    title = "声音参数与试听微调",
                    subtitle = "调整离线朗读语速、音调并即时试听声音",
                    trailing = {
                        TextButton(onClick = {
                            triggerHaptic()
                            isTtsTuningExpanded = !isTtsTuningExpanded
                        }) {
                            Text(if (isTtsTuningExpanded) "收起" else "展开微调", fontSize = 13.sp)
                        }
                    },
                    onClick = {
                        triggerHaptic()
                        isTtsTuningExpanded = !isTtsTuningExpanded
                    }
                )

                AnimatedVisibility(visible = isTtsTuningExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 1. 发音人音色选择 (MeloTTS 专属超清卡片 或 AISHELL-3 多音色选择)
                        if (isBilingualTts && ttsNumSpeakers <= 1) {
                            val activeMeloPreset = MELO_VOICE_PRESETS.find {
                                kotlin.math.abs(it.pitch - ttsPitch) < 0.04f && kotlin.math.abs(it.speechRate - ttsSpeechRate) < 0.05f
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("精选角色音色 (44.1kHz)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    SuggestionChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = if (activeMeloPreset != null) "${activeMeloPreset.name} · ${activeMeloPreset.role}" else "自定义微调",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                        },
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            labelColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        border = null,
                                        modifier = Modifier.height(26.dp)
                                    )
                                }

                                // 角色音色横向滑动胶囊
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    MELO_VOICE_PRESETS.forEach { preset ->
                                        val isSelected = (activeMeloPreset?.id == preset.id)
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                triggerHaptic()
                                                AppSettings.setTtsPitch(preset.pitch)
                                                AppSettings.setTtsSpeechRate(preset.speechRate)
                                            },
                                            label = {
                                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                                    Text(
                                                        text = preset.name,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                    )
                                                    Text(
                                                        text = preset.role,
                                                        fontSize = 10.sp,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (isSelected) Icons.Default.Check else preset.icon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        )
                                    }
                                }

                                Text(
                                    text = if (activeMeloPreset != null) activeMeloPreset.desc else "已手动微调语速和音调",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // 标题栏：发音人音色与当前选中标签
                                val activeCurated = OFFICIAL_CURATED_SPEAKERS.find { it.id == ttsSpeakerId }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.RecordVoiceOver,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("发音人音色", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    SuggestionChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = if (activeCurated != null) "#$ttsSpeakerId · ${activeCurated.name}" else "Speaker #$ttsSpeakerId",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        },
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        border = null,
                                        modifier = Modifier.height(28.dp)
                                    )
                                }

                                // 官方精选推荐 (横向滑动胶囊)
                                Text(
                                    text = "官方精选推荐 (基于 AISHELL-3 官方语料)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OFFICIAL_CURATED_SPEAKERS.forEach { speaker ->
                                        val isSelected = (ttsSpeakerId == speaker.id)
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                triggerHaptic()
                                                AppSettings.setTtsSpeakerId(speaker.id)
                                            },
                                            label = {
                                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                                    Text(
                                                        text = "#${speaker.id} ${speaker.name}",
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                    )
                                                    Text(
                                                        text = speaker.role,
                                                        fontSize = 10.sp,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            leadingIcon = if (isSelected) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            } else null,
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        )
                                    }
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    thickness = 0.5.dp
                                )

                                // 极客全量微调 (0 ~ 173 滑杆与 +/- 按钮)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "全量微调 (0 ~ 173)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = if (activeCurated != null) activeCurated.desc else "极客自定义音色",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            triggerHaptic()
                                            AppSettings.setTtsSpeakerId(ttsSpeakerId - 1)
                                        },
                                        enabled = ttsSpeakerId > 0,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = "上一音色", modifier = Modifier.size(16.dp))
                                    }

                                    Slider(
                                        value = ttsSpeakerId.toFloat(),
                                        onValueChange = {
                                            AppSettings.setTtsSpeakerId(it.roundToInt())
                                        },
                                        valueRange = 0f..173f,
                                        steps = 172,
                                        modifier = Modifier.weight(1f)
                                    )

                                    IconButton(
                                        onClick = {
                                            triggerHaptic()
                                            AppSettings.setTtsSpeakerId(ttsSpeakerId + 1)
                                        },
                                        enabled = ttsSpeakerId < 173,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "下一音色", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        // 语速滑杆
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("朗读语速", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("${((ttsSpeechRate * 10).roundToInt() / 10.0)}x", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            Slider(
                                value = ttsSpeechRate,
                                onValueChange = { AppSettings.setTtsSpeechRate(it) },
                                valueRange = 0.5f..2.0f,
                                steps = 14
                            )
                        }

                        // 音调滑杆
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("朗读音调", fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
                        val activeMeloPreset = if (isBilingualTts && ttsNumSpeakers <= 1) {
                            MELO_VOICE_PRESETS.find {
                                kotlin.math.abs(it.pitch - ttsPitch) < 0.04f && kotlin.math.abs(it.speechRate - ttsSpeechRate) < 0.05f
                            }
                        } else null
                        val activeSpeaker = OFFICIAL_CURATED_SPEAKERS.find { it.id == ttsSpeakerId }
                        val speakerLabel = if (activeMeloPreset != null) {
                            "MeloTTS · ${activeMeloPreset.name}"
                        } else if (isBilingualTts) {
                            "MeloTTS 自定义微调"
                        } else if (activeSpeaker != null) {
                            "${activeSpeaker.name} (${activeSpeaker.role})"
                        } else {
                            "${ttsSpeakerId} 号发音人"
                        }
                        Button(
                            onClick = {
                                triggerHaptic()
                                if (isSpeaking) {
                                    speechManager.stopSpeaking()
                                } else {
                                    val trialText = if (activeMeloPreset?.id == "male") {
                                        "你好！我是本地部署的 AI 大模型，沉稳男声音色测试就绪。"
                                    } else if (activeMeloPreset?.id == "girl") {
                                        "你好呀！我是本地部署的 AI 大模型，元气少女音色测试就绪！"
                                    } else if (activeMeloPreset?.id == "scholar") {
                                        "学而不思则罔，思而不学则殆。我是本地部署的 AI 大模型。"
                                    } else if (isBilingualTts) {
                                        "我是本地部署的 AI 大模型，随时为你解答 Python 和 Android 开发问题。"
                                    } else {
                                        "您好！这是当前的 ${speakerLabel} 音效测试。我正在本地离线运行。"
                                    }
                                    speechManager.speak(
                                        text = trialText,
                                        speechRate = ttsSpeechRate,
                                        pitch = ttsPitch
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
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
                            Text(if (isSpeaking) "停止试听" else "试听当前声音设置 ($speakerLabel)")
                        }
                    }
                }
            }

            // ========================================================
            // 模块 2: 端侧模型与计算底座
            // ========================================================
            SettingSectionGroup(title = "端侧模型与计算底座") {
                SettingItemRow(
                    icon = Icons.Default.Memory,
                    iconBgColor = Color(0xFF673AB7),
                    title = "大语言模型 (LLM)",
                    subtitle = "Qwen2.5-0.5B-Instruct (Q4_K_M)",
                    trailing = {
                        SettingTagBadge(text = "ARMv8.2-A", isSuccess = true)
                    }
                )

                SettingRowDivider()

                SettingItemRow(
                    icon = Icons.Default.Speed,
                    iconBgColor = Color(0xFF3F51B5),
                    title = "推理计算加速引擎",
                    subtitle = "llama.cpp C++17 原生编译 (4 线程 DotProd)",
                    trailing = {
                        SettingTagBadge(text = "4-Thread", isSuccess = true)
                    }
                )

                SettingRowDivider()

                SettingItemRow(
                    icon = Icons.Default.Mic,
                    iconBgColor = Color(0xFF009688),
                    title = "离线语音模型 (ASR)",
                    subtitle = "SenseVoice Small INT8 (sherpa-onnx 引擎)",
                    trailing = {
                        SettingTagBadge(text = "~1.3s 预热", isSuccess = true)
                    }
                )

                SettingRowDivider()

                SettingItemRow(
                    icon = Icons.Default.Security,
                    iconBgColor = Color(0xFF4CAF50),
                    title = "局域网微服务 (OpenAI API)",
                    subtitle = "对外开放 0.0.0.0:8989 端口，允许局域网设备调用",
                    trailing = {
                        Switch(
                            checked = localServerEnabled,
                            onCheckedChange = {
                                triggerHaptic()
                                AppSettings.setLocalServerEnabled(it)
                                Toast.makeText(
                                    context,
                                    if (it) "微服务已开启: 0.0.0.0:8989" else "微服务已停止",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                )
            }

            // ========================================================
            // 模块 3: 外观与个性化
            // ========================================================
            SettingSectionGroup(title = "外观与个性化") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingIconBox(icon = Icons.Default.Palette, bgColor = Color(0xFFE91E63))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("明暗显示模式", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text("适配系统深色或强制定制", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // 选项 Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            val selected = mode == themeMode
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    triggerHaptic()
                                    AppSettings.setThemeMode(mode)
                                },
                                label = { Text(mode.label, fontSize = 12.sp) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Text(
                        text = "主题强调配色",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

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
                                    .clickable {
                                        triggerHaptic()
                                        AppSettings.setColorTheme(theme)
                                    }
                                    .padding(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
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
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = theme.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            // ========================================================
            // 模块 4: 数据存储与物理隐私
            // ========================================================
            SettingSectionGroup(title = "数据存储与物理隐私") {
                SettingItemRow(
                    icon = Icons.Default.Storage,
                    iconBgColor = Color(0xFF607D8B),
                    title = "本地沙盒存储空间",
                    subtitle = "模型权重缓存 ~540 MB · 零临时垃圾产生",
                    trailing = {
                        Text("540 MB", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )

                SettingRowDivider()

                SettingItemRow(
                    icon = Icons.Default.Delete,
                    iconBgColor = Color(0xFFF44336),
                    title = "清空当前会话历史",
                    subtitle = "重置大模型上下文记忆，开启新对话",
                    trailing = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    },
                    onClick = {
                        triggerHaptic()
                        showClearConfirmDialog = true
                    }
                )

                SettingRowDivider()

                SettingItemRow(
                    icon = Icons.Default.Shield,
                    iconBgColor = Color(0xFF4CAF50),
                    title = "物理隔离保障承诺",
                    subtitle = "绝不上传任何音频或提问数据至任何外部云服务",
                    trailing = {
                        SettingTagBadge(text = "100% 封闭安全", isSuccess = true)
                    }
                )
            }

            // ========================================================
            // 模块 5: 关于与技术底座
            // ========================================================
            SettingSectionGroup(title = "关于与技术底座") {
                SettingItemRow(
                    icon = Icons.Default.Info,
                    iconBgColor = Color(0xFF795548),
                    title = "软件版本",
                    subtitle = "TangRen AI Assistant 商业边缘版",
                    trailing = {
                        Text("v1.2.0 (Build 120)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )

                SettingRowDivider()

                SettingItemRow(
                    icon = Icons.Default.Refresh,
                    iconBgColor = Color(0xFF03A9F4),
                    title = "检查固件与引擎更新",
                    subtitle = "当前已是最新商业稳定版",
                    trailing = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    },
                    onClick = {
                        triggerHaptic()
                        Toast.makeText(context, "🎉 当前已是最新商业稳定版本 (v1.2.0)", Toast.LENGTH_SHORT).show()
                    }
                )

                SettingRowDivider()

                SettingItemRow(
                    icon = Icons.Default.Check,
                    iconBgColor = Color(0xFF8BC34A),
                    title = "开源引擎与技术致谢",
                    subtitle = "llama.cpp, Alibaba SenseVoice, sherpa-onnx",
                    trailing = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    },
                    onClick = {
                        triggerHaptic()
                        showCreditsDialog = true
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // ============================================================
    // 弹窗 1: 语音偏好语言选择器
    // ============================================================
    if (showLanguageDialog) {
        val languages = listOf(
            "zh-CN" to "中文普通话 (Mandarin)",
            "en-US" to "英语 (English)",
            "auto" to "自动检测语言 (Auto Detect)"
        )
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("选择语音识别语言", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    languages.forEach { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    triggerHaptic()
                                    AppSettings.setAsrLanguage(code)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (code == asrLanguage),
                                onClick = {
                                    triggerHaptic()
                                    AppSettings.setAsrLanguage(code)
                                    showLanguageDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ============================================================
    // 弹窗 2: 清空对话历史确认
    // ============================================================
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            icon = {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("清空会话上下文？", fontWeight = FontWeight.Bold) },
            text = {
                Text("此操作将重置大模型的对话历史，开启全新上下文记忆。历史交互数据将被完全销毁。", fontSize = 14.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        triggerHaptic()
                        onClearChatHistory()
                        showClearConfirmDialog = false
                        Toast.makeText(context, "已重置本地会话上下文", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ============================================================
    // 弹窗 3: 开源致谢弹窗
    // ============================================================
    if (showCreditsDialog) {
        AlertDialog(
            onDismissRequest = { showCreditsDialog = false },
            title = { Text("开源生态与技术致谢", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TangRen 边缘 AI 助手由以下开源技术基石驱动：", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• llama.cpp: 高性能纯 C/C++ LLM 端侧推理框架 (Georgi Gerganov & 社区)", fontSize = 12.sp)
                    Text("• SenseVoice: 阿里通义多语言高精度离线流式语音识别模型", fontSize = 12.sp)
                    Text("• sherpa-onnx: 新一代 ONNX 跨平台嵌入式语音转写运行时 (k2-fsa)", fontSize = 12.sp)
                    Text("• Qwen2.5: 阿里巴巴通义千问端侧大语言模型", fontSize = 12.sp)
                    Text("• Jetpack Compose: 现代化声明式 Android 原生 UI 框架", fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(onClick = { showCreditsDialog = false }) {
                    Text("我知道了")
                }
            }
        )
    }
}

// ============================================================
// 商业级组件库: SettingSectionGroup, SettingItemRow, SettingBadge
// ============================================================

@Composable
fun SettingSectionGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
        )

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            ),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun SettingItemRow(
    icon: ImageVector,
    iconBgColor: Color,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingIconBox(icon = icon, bgColor = iconBgColor)

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        trailing()
    }
}

@Composable
fun SettingIconBox(
    icon: ImageVector,
    bgColor: Color
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
fun SettingRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

@Composable
fun SettingTagBadge(
    text: String,
    isSuccess: Boolean = true
) {
    val bgColor = if (isSuccess) Color(0xFF4CAF50).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isSuccess) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}
