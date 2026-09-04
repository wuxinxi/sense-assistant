package cn.xxstudy.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.xxstudy.assistant.ui.components.ChatBubble
import cn.xxstudy.assistant.viewmodel.MainViewModel

@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()

    val listState = rememberLazyListState()

    // 智能判断用户当前是否正停留在列表底部附近（如果在最底部才自动跟随，向上翻看历史时绝不打断）
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
            
            val context = androidx.compose.ui.platform.LocalContext.current
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
                ChatBubble(msg)
            }
        }

        // 底部输入框
        if (isModelLoaded) {
            var inputText by remember { mutableStateOf("") }
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
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("发送消息给端侧大模型...") },
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    FloatingActionButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.size(50.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
