package cn.xxstudy.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cn.xxstudy.assistant.data.AppSettings
import cn.xxstudy.assistant.data.ModelFileStatus
import cn.xxstudy.assistant.data.ModelType
import cn.xxstudy.assistant.ui.components.ChatBubble
import cn.xxstudy.assistant.ui.components.DoubaoInputBar
import cn.xxstudy.assistant.ui.components.DoubaoVoicePanel
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

    val listeningRms by viewModel.listeningRms.collectAsState()
    val voicePartialText by viewModel.voicePartialText.collectAsState()

    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var isPressingVoice by remember { mutableStateOf(false) }
    var isCancelVoice by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val cancelThresholdPx = remember(density) { with(density) { 60.dp.toPx() } }

    // 自动探测并预热本地大模型引擎
    LaunchedEffect(Unit) {
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

    // 智能判断用户当前是否停留在列表底部附近
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) true
            else {
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleIndex >= totalItems - 1
            }
        }
    }

    // 监听消息列表新增或更新：仅在用户本就在底部时自动滚到底部，绝不干扰用户向上翻看历史
    LaunchedEffect(chatMessages.size, chatMessages.lastOrNull()?.text, chatMessages.lastOrNull()?.thinkingText) {
        if (chatMessages.isNotEmpty() && isAtBottom) {
            listState.animateScrollToItem(chatMessages.size - 1)
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
            // 顶部状态栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isModelLoaded) Color(0xFF4CAF50) else Color(0xFFFF9800))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "系统状态: $statusMessage",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (!isModelLoaded) {
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
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("启动引擎", style = MaterialTheme.typography.labelMedium)
                    }
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
                onPlusClick = {
                    Toast.makeText(context, "更多功能暂未开放", Toast.LENGTH_SHORT).show()
                },
                onSendClick = {
                    if (!isModelLoaded) {
                        Toast.makeText(context, "大模型引擎加载中，请稍候...", Toast.LENGTH_SHORT).show()
                        return@DoubaoInputBar
                    }
                    if (inputText.isNotBlank()) {
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
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                    viewModel.startVoiceRecording(
                        autoSend = true,
                        onError = { errMsg ->
                            Toast.makeText(context, errMsg, Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                onVoiceMove = { deltaY ->
                    val nowCancel = deltaY < -cancelThresholdPx
                    if (nowCancel != isCancelVoice) {
                        isCancelVoice = nowCancel
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
            visible = isPressingVoice,
            isCancel = isCancelVoice,
            rms = listeningRms,
            partialText = voicePartialText,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
