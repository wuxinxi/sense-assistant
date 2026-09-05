package cn.xxstudy.assistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.xxstudy.assistant.data.AppSettings
import cn.xxstudy.assistant.ui.screens.MainScreen
import cn.xxstudy.assistant.ui.screens.SettingsScreen
import cn.xxstudy.assistant.ui.theme.AppsenseassistantTheme
import cn.xxstudy.assistant.viewmodel.MainViewModel

enum class AppNavScreen {
    CHAT,
    SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        AppSettings.init(application)
        handleIntent(intent)
        setContent {
            val themeMode by AppSettings.themeMode.collectAsState()
            val colorTheme by AppSettings.colorTheme.collectAsState()

            var currentScreen by remember { mutableStateOf(AppNavScreen.CHAT) }

            AppsenseassistantTheme(
                themeMode = themeMode,
                colorTheme = colorTheme
            ) {
                Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
                    when (screen) {
                        AppNavScreen.CHAT -> {
                            Scaffold(
                                topBar = {
                                    TopAppBar(
                                        title = { Text("TangRen AI 底座", fontWeight = FontWeight.Bold) },
                                        actions = {
                                            IconButton(onClick = { currentScreen = AppNavScreen.SETTINGS }) {
                                                Icon(Icons.Default.Settings, contentDescription = "设置")
                                            }
                                        },
                                        colors = TopAppBarDefaults.topAppBarColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                                        )
                                    )
                                }
                            ) { innerPadding ->
                                MainScreen(
                                    viewModel = viewModel,
                                    modifier = Modifier.padding(innerPadding)
                                )
                            }
                        }

                        AppNavScreen.SETTINGS -> {
                            SettingsScreen(
                                speechManager = viewModel.speechManager,
                                onClearChatHistory = { viewModel.clearChatHistory() },
                                onBack = { currentScreen = AppNavScreen.CHAT }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val sid = intent?.getIntExtra("sid", -1) ?: -1
        if (sid in 0..173) {
            AppSettings.setTtsSpeakerId(sid)
        }
        val prompt = intent?.getStringExtra("prompt")
        if (!prompt.isNullOrBlank()) {
            viewModel.sendMessage(prompt)
        }
        val ttsText = intent?.getStringExtra("tts")
        if (!ttsText.isNullOrBlank()) {
            viewModel.speechManager.speak(ttsText)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cn.xxstudy.assistant.server.LlamaServer.stop()
        viewModel.speechManager.destroy()
    }
}
