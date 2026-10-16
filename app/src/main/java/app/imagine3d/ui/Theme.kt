package app.imagine3d.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

private val Dark = darkColorScheme(primary = Color(0xFF8AB4F8), secondary = Color(0xFFFFC857), background = Color(0xFF111317), surface = Color(0xFF16181D))
private val Light = lightColorScheme(primary = Color(0xFF2A5BB0), secondary = Color(0xFFB7791F))

@Composable
fun Imagine3DTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)
