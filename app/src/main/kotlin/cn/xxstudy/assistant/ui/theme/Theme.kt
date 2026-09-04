package cn.xxstudy.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import cn.xxstudy.assistant.data.ColorTheme
import cn.xxstudy.assistant.data.ThemeMode

// --- 极客紫 ---
private val PurpleDark = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)
private val PurpleLight = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

// --- 科技蓝 ---
private val BlueDark = darkColorScheme(
    primary = Blue80,
    secondary = BlueGrey80,
    tertiary = Cyan80
)
private val BlueLight = lightColorScheme(
    primary = Blue40,
    secondary = BlueGrey40,
    tertiary = Cyan40
)

// --- 翡翠绿 ---
private val GreenDark = darkColorScheme(
    primary = Green80,
    secondary = GreenGrey80,
    tertiary = Teal80
)
private val GreenLight = lightColorScheme(
    primary = Green40,
    secondary = GreenGrey40,
    tertiary = Teal40
)

// --- 赛博橙 ---
private val OrangeDark = darkColorScheme(
    primary = Orange80,
    secondary = OrangeGrey80,
    tertiary = Amber80
)
private val OrangeLight = lightColorScheme(
    primary = Orange40,
    secondary = OrangeGrey40,
    tertiary = Amber40
)

@Composable
fun AppsenseassistantTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    colorTheme: ColorTheme = ColorTheme.PURPLE,
    content: @Composable () -> Unit
) {
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemInDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when (colorTheme) {
        ColorTheme.PURPLE -> if (isDark) PurpleDark else PurpleLight
        ColorTheme.BLUE -> if (isDark) BlueDark else BlueLight
        ColorTheme.GREEN -> if (isDark) GreenDark else GreenLight
        ColorTheme.ORANGE -> if (isDark) OrangeDark else OrangeLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}