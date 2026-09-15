package cn.xxstudy.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.animation.*

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cn.xxstudy.assistant.data.AppSettings
import cn.xxstudy.assistant.data.ModelFileStatus
import cn.xxstudy.assistant.data.ModelType
import cn.xxstudy.assistant.ui.components.ChatBubble
import cn.xxstudy.assistant.ui.components.DoubaoInputBar
import cn.xxstudy.assistant.ui.components.DoubaoVoicePanel
import cn.xxstudy.assistant.utils.DeviceMetrics
import cn.xxstudy.assistant.utils.PerformanceMonitor
import cn.xxstudy.assistant.viewmodel.MainViewModel
import java.io.File

@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val speakingMessageId by viewModel.speakingMessageId.collectAsState()

    val hapticEnabled by AppSettings.hapticEnabled.collectAsState()
    val showPerformanceOverlay by AppSettings.showPerformanceOverlay.collectAsState()
    val isLlmEngineEnabled by AppSettings.isLlmEngineEnabled.collectAsState()
    val isPhoneModeEnabled by AppSettings.isPhoneModeEnabled.collectAsState()
    val isActiveCall by viewModel.isActiveCall.collectAsState()
    var isCallScreenVisible by remember { mutableStateOf(false) }


    val deviceMetrics by PerformanceMonitor.metrics.collectAsState()

    DisposableEffect(showPerformanceOverlay) {
        if (showPerformanceOverlay) {
            PerformanceMonitor.start(context)
        } else {
            PerformanceMonitor.stop()
        }
        onDispose {
            PerformanceMonitor.stop()
        }
    }

    val listeningRms by viewModel.listeningRms.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val voicePartialText by viewModel.voicePartialText.collectAsState()

    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var isPressingVoice by remember { mutableStateOf(false) }
    var isCancelVoice by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val cancelThresholdPx = remember(density) { with(density) { 60.dp.toPx() } }

    // 自动探测并预热本地大模型引擎（仅在启用大模型时执行）
    LaunchedEffect(isLlmEngineEnabled) {
        if (!isLlmEngineEnabled) return@LaunchedEffect
        if (!isModelLoaded && !isLoading) {
            val preferredModel = AppSettings.currentModelType.value
            val primaryCheck = AppSettings.checkModelFile(context, preferredModel)
            if (primaryCheck.status == ModelFileStatus.READY && primaryCheck.file != null) {
                viewModel.loadLocalModel(primaryCheck.file.absolutePath, preferredModel)
            } else {
                val fallbackModel = if (preferredModel == ModelType.MINICPM5_2B) ModelType.QWEN_0_5B else ModelType.MINICPM5_2B
                val fallbackCheck = AppSettings.checkModelFile(context, fallbackModel)
                if (fallbackCheck.status == ModelFileStatus.READY && fallbackCheck.file != null) {
                    AppSettings.setCurrentModelType(fallbackModel)
                    viewModel.loadLocalModel(fallbackCheck.file.absolutePath, fallbackModel)
                }
            }
        }
    }

    // 录音权限请求器
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "已授予麦克风权限，可按住说话", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "请授予麦克风权限以使用语音功能", Toast.LENGTH_SHORT).show()
        }
    }

    // 用户手势交互与自动吸底状态追踪
    var autoScrollToBottom by remember { mutableStateOf(true) }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()

    // 监听用户手势拖动
    LaunchedEffect(isDragged) {
        if (isDragged) {
            // 用户主动拖拽时，若向上滑动（下方尚有内容），暂停自动吸底
            if (listState.canScrollForward) {
                autoScrollToBottom = false
            }
        } else {
            // 手指松开时，若已在最底部，恢复自动吸底
            if (!listState.canScrollForward) {
                autoScrollToBottom = true
            }
        }
    }

    // 监听滑动彻底静止（包含惯性滚动结束）
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            if (!listState.canScrollForward) {
                autoScrollToBottom = true
            }
        }
    }

    // 1. 新消息入列时（用户发送或 AI 消息创建）：恢复吸底并平滑滚动至最底
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            autoScrollToBottom = true
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    // 2. 流式文本吐字或思考过程展开/折叠时：稳定持续吸底，防止高频 Token 触发动画中断卡死
    LaunchedEffect(
        chatMessages.lastOrNull()?.text,
        chatMessages.lastOrNull()?.thinkingText,
        chatMessages.lastOrNull()?.isThinkingCollapsed
    ) {
        if (chatMessages.isNotEmpty() && autoScrollToBottom) {
            val targetIndex = chatMessages.size - 1
            val lastItem = listState.layoutInfo.visibleItemsInfo.find { it.index == targetIndex }
            val offset = if (lastItem != null) {
                val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
                (lastItem.size - viewportHeight + 100).coerceAtLeast(0)
            } else 0
            listState.scrollToItem(targetIndex, offset)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部状态与性能状态栏
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        !isLlmEngineEnabled -> Color(0xFF2196F3)
                                        isModelLoaded -> Color(0xFF4CAF50)
                                        else -> Color(0xFFFF9800)
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (!isLlmEngineEnabled) "系统状态: 纯语音测试 (大模型未启用)" else "系统状态: $statusMessage",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    if (!isModelLoaded && isLlmEngineEnabled) {
                        Button(
                            onClick = {
                                val currentModel = AppSettings.currentModelType.value
                                val check = AppSettings.checkModelFile(context, currentModel)
                                if (check.status == ModelFileStatus.READY && check.file != null) {
                                    viewModel.loadLocalModel(check.file.absolutePath, currentModel)
                                } else if (check.status == ModelFileStatus.INCOMPLETE) {
                                    Toast.makeText(context, "⚠️ ${currentModel.displayName} 正在写入中 (${check.currentBytes / 1024 / 1024}MB / ${check.expectedBytes / 1024 / 1024}MB - ${check.progressPercent}%)，请等待传输完成", Toast.LENGTH_LONG).show()
                                } else {
                                    val fallbackModel = if (currentModel == ModelType.MINICPM5_2B) ModelType.QWEN_0_5B else ModelType.MINICPM5_2B
                                    val fallbackCheck = AppSettings.checkModelFile(context, fallbackModel)
                                    if (fallbackCheck.status == ModelFileStatus.READY && fallbackCheck.file != null) {
                                        AppSettings.setCurrentModelType(fallbackModel)
                                        viewModel.loadLocalModel(fallbackCheck.file.absolutePath, fallbackModel)
                                        Toast.makeText(context, "${currentModel.displayName} 未就绪，已切换为备用模型 ${fallbackModel.displayName}", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "未找到完整的模型文件，请前往【设置】或通过脚本推送", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = !isLoading,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("启动引擎", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                if (showPerformanceOverlay) {
                    Spacer(modifier = Modifier.height(6.dp))
                    PerformanceBadge(metrics = deviceMetrics)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))

            // 对话流区域
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(chatMessages, key = { it.id }) { msg ->
                    ChatBubble(
                        msg = msg,
                        isSpeakingThis = (speakingMessageId == msg.id),
                        onSpeakClick = { viewModel.speakMessage(msg.id, msg.text) },
                        onToggleThinkingCollapsed = { viewModel.toggleThinkingCollapsed(msg.id) }
                    )
                }
            }

            // 豆包一体化白色胶囊输入栏 (始终优雅常驻底部)
            DoubaoInputBar(
                inputText = inputText,
                onInputTextChange = { inputText = it },
                onCameraClick = {
                    Toast.makeText(context, "拍照功能暂未开放", Toast.LENGTH_SHORT).show()
                },
                onPhoneClick = if (isPhoneModeEnabled) {
                    { 
                        if (isActiveCall) {
                            isCallScreenVisible = false
                            viewModel.stopPhoneMode()
                        } else {
                            isCallScreenVisible = true
                            viewModel.startPhoneMode() 
                        }
                    }
                } else null,
                isActiveCall = isActiveCall,
                onPlusClick = {
                    Toast.makeText(context, "更多功能暂未开放", Toast.LENGTH_SHORT).show()
                },
                onSendClick = {
                    if (!isModelLoaded) {
                        Toast.makeText(context, "大模型引擎加载中，请稍候...", Toast.LENGTH_SHORT).show()
                        return@DoubaoInputBar
                    }
                    if (inputText.isNotBlank()) {
                        autoScrollToBottom = true
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                isPressingVoice = isPressingVoice,
                onVoiceDown = {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@DoubaoInputBar
                    }

                    isPressingVoice = true
                    isCancelVoice = false
                    if (hapticEnabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }

                    viewModel.startVoiceRecording(
                        autoSend = false,
                        onFinalTextReady = {
                            if (!AppSettings.asrAutoSend.value) {
                                inputText = it
                            }
                        },
                        onError = { errMsg ->
                            Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                onVoiceMove = { deltaY ->
                    val nowCancel = deltaY < -cancelThresholdPx
                    if (nowCancel != isCancelVoice) {
                        isCancelVoice = nowCancel
                        if (hapticEnabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                },
                onVoiceUp = {
                    if (isPressingVoice) {
                        isPressingVoice = false
                        if (isCancelVoice) {
                            viewModel.cancelVoiceRecording()
                            Toast.makeText(context, "已取消发送", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.stopVoiceRecording()
                        }
                        isCancelVoice = false
                    }
                },
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding()
            )
        }

        // 豆包沉浸式科技蓝声浪面板 (按住说话时由底部升起展开)
        DoubaoVoicePanel(
            visible = isPressingVoice || isListening,
            isCancel = isCancelVoice,
            rms = listeningRms,
            partialText = voicePartialText,
            isHandsFree = !isPressingVoice && isListening,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        AnimatedVisibility(
            visible = isCallScreenVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            CallScreen(
                viewModel = viewModel,
                onClose = {
                    isCallScreenVisible = false
                    viewModel.stopPhoneMode()
                },
                onMinimize = {
                    isCallScreenVisible = false
                }
            )
        }
    }
}

@Composable
private fun PerformanceBadge(metrics: DeviceMetrics, modifier: Modifier = Modifier) {
    val isHighLoad = metrics.cpuPercent > 80 || (metrics.totalRamMb > 0 && metrics.availRamMb < 250)
    val bgColor = if (isHighLoad) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val contentColor = if (isHighLoad) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "CPU 占用: ${metrics.cpuPercent}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = contentColor
                )
            }
            Text(
                text = "本应用: ${metrics.appRamMb}MB  |  系统剩余: ${metrics.availRamMb}MB / ${metrics.totalRamMb}MB",
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = contentColor
            )
        }
    }
}
