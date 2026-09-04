package cn.xxstudy.assistant.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cn.xxstudy.assistant.ui.components.ChatBubble
import cn.xxstudy.assistant.viewmodel.MainViewModel

@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val speakingMessageId by viewModel.speakingMessageId.collectAsState()

    val isListening by viewModel.speechManager.isListening.collectAsState()
    val voicePartialText by viewModel.voicePartialText.collectAsState()

    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }

    // 录音权限请求器
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startVoiceRecording { recognized ->
                inputText = recognized
            }
        } else {
            Toast.makeText(context, "请授予麦克风权限以使用语音输入", Toast.LENGTH_SHORT).show()
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

    // 自动滚动控制：仅在用户未用手指触摸滑动、且视口正停留在底部时跟随最新字数
    LaunchedEffect(chatMessages.size, chatMessages.lastOrNull()?.text?.length) {
        if (chatMessages.isNotEmpty() && !listState.isScrollInProgress && isAtBottom) {
            listState.scrollToItem(chatMessages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部状态栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isModelLoaded) Color(0xFF4CAF50) else Color(0xFFFF9800))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "系统状态: $statusMessage", color = MaterialTheme.colorScheme.onBackground)
            }

            if (!isModelLoaded) {
                Button(
                    onClick = {
                        val internalFile = java.io.File(context.filesDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf")
                        val externalFile = java.io.File(context.getExternalFilesDir(null), "qwen2.5-0.5b-instruct-q4_k_m.gguf")
                        val targetPath = when {
                            internalFile.exists() -> internalFile.absolutePath
                            externalFile.exists() -> externalFile.absolutePath
                            else -> "/sdcard/Android/data/cn.xxstudy.assistant/files/qwen2.5-0.5b-instruct-q4_k_m.gguf"
                        }
                        viewModel.loadLocalModel(targetPath)
                    },
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("启动引擎")
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

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
                    onSpeakClick = { viewModel.speakMessage(msg.id, msg.text) }
                )
            }
        }

        // 语音输入正在聆听动态浮条
        AnimatedVisibility(
            visible = isListening,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (!voicePartialText.isNullOrBlank()) "正在聆听: \"$voicePartialText\"" else "正在聆听语音输入...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1
                        )
                    }
                    TextButton(onClick = { viewModel.stopVoiceRecording() }) {
                        Text("完成", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 底部输入栏
        if (isModelLoaded) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入消息或点击语音...") },
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // ASR 语音输入按钮
                    FilledIconButton(
                        onClick = {
                            if (isListening) {
                                viewModel.stopVoiceRecording()
                            } else {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                if (hasPermission) {
                                    viewModel.startVoiceRecording { recognized ->
                                        inputText = recognized
                                    }
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        modifier = Modifier.size(46.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (isListening) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isListening) "停止录音" else "语音输入",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 发送按钮
                    FloatingActionButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
