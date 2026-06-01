package org.github.ewt45.winemulator.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

// 新增暗色主题颜色方案（用于悬浮按钮等）
private val DarkSurfaceColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    surface = androidx.compose.ui.graphics.Color(0xFF121212),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
    background = androidx.compose.ui.graphics.Color(0xFF121212)
)

@Composable
fun MainTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    // 用于从外部传入主题偏好的Flow（true = 暗色主题，false = 亮色主题，null = 跟随系统）
    externalDarkThemeFlow: Flow<Boolean?>? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    // 获取外部传入的主题偏好
    val externalDarkTheme by externalDarkThemeFlow?.collectAsState(initial = null) ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(null) }
    
    // 确定最终的主题：优先使用外部传入的偏好，其次使用传入的darkTheme参数（默认暗色）
    val isDarkTheme = externalDarkTheme ?: darkTheme ?: true
    
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}