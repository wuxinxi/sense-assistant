package cn.xxstudy.assistant.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import cn.xxstudy.assistant.viewmodel.ChatMessage
import cn.xxstudy.assistant.rag.RagCitationLinks
import cn.xxstudy.assistant.rag.RagSource
import cn.xxstudy.assistant.rag.RagSourceReader
import com.halilibo.richtext.markdown.Markdown
import kotlinx.coroutines.launch

@Composable
fun ChatBubble(
    msg: ChatMessage,
    isSpeakingThis: Boolean = false,
    onSpeakClick: (() -> Unit)? = null,
    onToggleThinkingCollapsed: (() -> Unit)? = null
) {
    val isUser = msg.isUser
    val context = LocalContext.current
    var selectedSourceIndex by remember(msg.id) { mutableStateOf<Int?>(null) }
    val canSpeak = onSpeakClick != null && msg.text.isNotBlank() && !msg.isKnowledgeExcerpt &&
        (msg.knowledgeSources.isEmpty() || (!msg.isStreaming && msg.knowledgeStage == null))
    val renderAsPlainText = msg.isStreaming ||
        (msg.isKnowledgeExcerpt && msg.text.length > 16_000)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "AI",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        val displayText = if (!msg.thinkingText.isNullOrBlank()) msg.text.trimStart() else msg.text
        val markdownDisplayText = if (!isUser && !msg.isStreaming && msg.knowledgeSources.isNotEmpty()) {
            remember(displayText, msg.knowledgeSources) { RagCitationLinks.linkify(displayText, msg.knowledgeSources) }
        } else displayText
        val isKnowledgeAnswer = msg.isKnowledgeExcerpt || msg.knowledgeSources.isNotEmpty()
        val parsedActions = if (!isUser && !isKnowledgeAnswer && displayText.isNotBlank()) {
            IntentParser.parse(displayText)
        } else null
        val trimmed = displayText.trim()
        val isPotentialJson = !isUser && !isKnowledgeAnswer && (
            trimmed.startsWith("[") || 
            trimmed.startsWith("{") || 
            trimmed.startsWith("```json") || 
            (trimmed.startsWith("```") && (trimmed.contains("\"action\"") || trimmed.contains("action")))
        )

        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 20.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = if (parsedActions != null || isPotentialJson) {
                Modifier.weight(1f, fill = false).widthIn(min = 280.dp, max = 420.dp)
            } else {
                Modifier.widthIn(
                    max = if (isUser) 280.dp 
                          else if (!msg.thinkingText.isNullOrBlank()) 340.dp 
                          else 420.dp
                )
            }
        ) {
            Box(modifier = Modifier.padding(if (parsedActions != null) 6.dp else 12.dp)) {
                if (msg.isThinking && msg.thinkingText.isNullOrBlank()) {
                    // 初始冷启动等待态（尚未返回任何 Token 或开始标记）
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(msg.knowledgeStage?.label ?: "思考中...", fontSize = 14.sp)
                        }
                        // Retrieved sources are already usable while waiting for the first token.
                        if (msg.knowledgeSources.isNotEmpty()) {
                            KnowledgeSourceCard(msg, onOpenSource = { selectedSourceIndex = it })
                        } else if (msg.knowledgeRetrievalInfo != null) {
                            Text(msg.knowledgeRetrievalInfo, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Column {
                        if (msg.knowledgeStage != null && !msg.isThinking) {
                            Text(msg.knowledgeStage.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        // 1. 如果存在思考链文本，优先渲染极客折叠卡片
                        if (!msg.thinkingText.isNullOrBlank()) {
                            ThinkingCard(
                                thinkingText = msg.thinkingText,
                                isThinkingActive = msg.isThinkingActive,
                                isCollapsed = msg.isThinkingCollapsed,
                                onToggleCollapse = { onToggleThinkingCollapsed?.invoke() }
                            )
                            if (msg.text.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }

                        // 2. 正式回答正文（支持意图指令胶囊卡片与标准 Markdown 文本）
                        if (displayText.isNotBlank()) {
                            if (parsedActions != null) {
                                IntentActionCard(
                                    actions = parsedActions,
                                    rawText = displayText
                                )
                            } else if (isPotentialJson) {
                                if (msg.metrics == null) {
                                    // 正在流式生成或正在解析 JSON 指令中，屏蔽生硬代码块，显示优雅的状态提示
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(14.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "正在解析并准备执行操作指令...",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                } else {
                                    // 生成完毕但解析失败时才降级显示
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(
                                            text = "⚠️ 未能识别的指令报文",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = displayText,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                if (renderAsPlainText) {
                                    // Streaming Markdown is intentionally rendered as plain text. Parsing the
                                    // whole growing document on every snapshot, or parsing a very large RAG
                                    // excerpt at completion, causes long answers to jank.
                                    Text(
                                        text = displayText,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp,
                                        color = if (isUser) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    androidx.compose.runtime.CompositionLocalProvider(
                                        androidx.compose.material3.LocalContentColor provides if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(
                                            fontSize = 15.sp,
                                            lineHeight = 22.sp
                                        )
                                    ) {
                                        val customRichTextStyle = com.halilibo.richtext.ui.RichTextStyle(
                                            codeBlockStyle = com.halilibo.richtext.ui.CodeBlockStyle(
                                                modifier = Modifier
                                                    .background(
                                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(12.dp),
                                                textStyle = androidx.compose.ui.text.TextStyle(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            ),
                                            stringStyle = com.halilibo.richtext.ui.string.RichTextStringStyle(
                                                codeStyle = androidx.compose.ui.text.SpanStyle(
                                                    background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            )
                                        )
                                        
                                        com.halilibo.richtext.ui.material3.Material3RichText(
                                            modifier = Modifier.padding(vertical = 4.dp),
                                            style = customRichTextStyle
                                        ) {
                                            Markdown(
                                                content = markdownDisplayText,
                                                onLinkClicked = { url ->
                                                    val sourceIndex = RagCitationLinks.sourceIndex(url, msg.knowledgeSources)
                                                    if (sourceIndex != null) selectedSourceIndex = sourceIndex
                                                    else if (!url.startsWith("rag-source:")) openMarkdownLink(context, url)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (msg.thinkingText != null && msg.isThinkingActive) {
                            // 正在深度思考中，且正文尚未开始输出
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    text = "整理回答中",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                CircularProgressIndicator(
                                    strokeWidth = 1.5.dp,
                                    modifier = Modifier.size(10.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (msg.knowledgeSources.isNotEmpty()) {
                            KnowledgeSourceCard(msg, onOpenSource = { selectedSourceIndex = it })
                        } else if (msg.knowledgeRetrievalInfo != null) {
                            Text(msg.knowledgeRetrievalInfo, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // 3. 性能指标与 TTS 语音播放按钮栏
                        if (!isUser && (!msg.metrics.isNullOrEmpty() || canSpeak)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!msg.metrics.isNullOrEmpty()) {
                                    Text(
                                        text = msg.metrics,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }

                                if (canSpeak) {
                                    IconButton(
                                        onClick = { onSpeakClick?.invoke() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isSpeakingThis) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = if (isSpeakingThis) "停止朗读" else "语音朗读",
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isSpeakingThis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selectedSourceIndex?.takeIf { it in msg.knowledgeSources.indices }?.let { index ->
        KnowledgeSourceSheet(
            sources = msg.knowledgeSources,
            selectedIndex = index,
            onSelectSource = { selectedSourceIndex = it },
            onDismiss = { selectedSourceIndex = null }
        )
    }
}

@Composable
private fun KnowledgeSourceCard(msg: ChatMessage, onOpenSource: (Int) -> Unit) {
    var expanded by remember(msg.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "知识库原文 · ${msg.knowledgeSources.size} 个章节",
                    modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起知识库原文" else "展开知识库原文"
                )
            }
            msg.knowledgeRetrievalInfo?.let { info ->
                Text(info, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Only list section headings here. Render a single selected source in the reader.
            if (expanded) {
                msg.knowledgeSources.forEachIndexed { index, source ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        source.referenceIds.joinToString(" ") { "[$it]" } + " ${source.docName} · ${source.sectionTitle}",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onOpenSource(index) }.padding(vertical = 8.dp)
                    )
                }
            } else {
                TextButton(onClick = { onOpenSource(0) }) { Text("查看原文") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeSourceSheet(
    sources: List<RagSource>,
    selectedIndex: Int,
    onSelectSource: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copySource(text: String, label: String) {
        scope.launch {
            val clip = ClipData.newPlainText("知识库原文", text)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            try {
                clipboard.setClipEntry(ClipEntry(clip))
                Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                Toast.makeText(context, "无法复制，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val source = sources[selectedIndex]
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 16.dp)) {
            Text("知识库原文", style = MaterialTheme.typography.titleMedium)
            Text("以下为本地资料，不是模型生成的内容", style = MaterialTheme.typography.bodySmall)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEachIndexed { index, item ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { onSelectSource(index) },
                        label = { Text(item.referenceIds.joinToString(" ") { "[$it]" } + " " + item.sectionTitle, maxLines = 1) }
                    )
                }
            }
            key(selectedIndex) {
                val codeBlocks = remember(source.content) { RagSourceReader.codeBlocks(source.content) }
                val obsidianLink = remember(source.docName) { cn.xxstudy.assistant.rag.RagGroundingGuard.buildObsidianLink(source.docName) }
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Text("${source.docName} · ${source.sectionTitle}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            copySource(source.content, "已复制本章节原文")
                        }) { Text("复制原文") }
                        if (obsidianLink != null) {
                            TextButton(onClick = { openMarkdownLink(context, obsidianLink) }) { Text("打开 Obsidian") }
                        }
                    }
                    if (codeBlocks.isNotEmpty()) {
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            codeBlocks.forEachIndexed { index, block ->
                                OutlinedButton(onClick = {
                                    copySource(block.code, "已复制代码 ${index + 1}")
                                }) { Text("复制代码 ${index + 1}" + block.language.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()) }
                            }
                        }
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        if (source.content.length > 16_000) {
                            Text(source.content, fontSize = 13.sp, lineHeight = 20.sp)
                        } else {
                            com.halilibo.richtext.ui.material3.Material3RichText {
                                Markdown(content = source.content, onLinkClicked = { openMarkdownLink(context, it) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * [ThinkingCard] 深度思考折叠卡片组件
 */
@Composable
private fun ThinkingCard(
    thinkingText: String,
    isThinkingActive: Boolean,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggleCollapse() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = "思考链",
                        tint = if (isThinkingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isThinkingActive) "深度思考中..." else "已深度思考",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isThinkingActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                    if (isThinkingActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        CircularProgressIndicator(
                            strokeWidth = 1.5.dp,
                            modifier = Modifier.size(10.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Icon(
                    imageVector = if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = if (isCollapsed) "展开思考" else "收起思考",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = !isCollapsed,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        thickness = 0.5.dp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    ) {
                        // 替换过深的缩进，缓解手机屏幕宽度被过度挤压的问题
                        val optimizedText = thinkingText
                            .replace(Regex("^ {8,}", RegexOption.MULTILINE), "    ")
                            .replace(Regex("^ {4,7}", RegexOption.MULTILINE), "  ")
                            .ifBlank { "正在组织思考链路..." }
                            
                        val customRichTextStyle = com.halilibo.richtext.ui.RichTextStyle(
                            codeBlockStyle = com.halilibo.richtext.ui.CodeBlockStyle(
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(10.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                )
                            ),
                            stringStyle = com.halilibo.richtext.ui.string.RichTextStringStyle(
                                codeStyle = androidx.compose.ui.text.SpanStyle(
                                    background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        )

                        com.halilibo.richtext.ui.material3.Material3RichText(
                            modifier = Modifier.padding(vertical = 4.dp),
                            style = customRichTextStyle
                        ) {
                            Markdown(
                                content = optimizedText,
                                onLinkClicked = { url -> openMarkdownLink(context, url) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Avoid ActivityNotFoundException for Obsidian/custom or relative links. */
private fun openMarkdownLink(context: Context, rawUrl: String) {
    val url = rawUrl.trim()
    if (url.isEmpty()) return

    try {
        val uri = Uri.parse(url)
        if (uri.scheme.isNullOrBlank()) {
            // 笔记内链（Obsidian wiki-link 等），无法在 Android 中打开，静默忽略
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            Toast.makeText(context, "设备没有可打开此链接的应用", Toast.LENGTH_SHORT).show()
            return
        }
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "设备没有可打开此链接的应用", Toast.LENGTH_SHORT).show()
    } catch (_: RuntimeException) {
        Toast.makeText(context, "链接格式无效", Toast.LENGTH_SHORT).show()
    }
}
