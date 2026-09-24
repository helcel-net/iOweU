package net.helcel.owu.activity

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import net.helcel.owu.R
import net.helcel.owu.helper.defaultPreferences

/**
 * Which theme the user picked. It is state as well as a preference, because
 * picking one has to repaint the screen it was picked on, and a preference
 * read during composition never comes back to say it has changed.
 */
object ThemeChoice {
    private var chosen by mutableStateOf<String?>(null)

    fun current(context: Context): String = chosen ?: stored(context)

    fun set(context: Context, value: String) {
        defaultPreferences(context).edit { putString(context.getString(R.string.key_theme), value) }
        chosen = value
    }

    private fun stored(context: Context): String = defaultPreferences(context).getString(
        context.getString(R.string.key_theme),
        context.getString(R.string.system),
    )!!
}

/**
 * The wallpaper's accent, where the platform will not give us a palette.
 * `getWallpaperColors` has been there since API 27 - below our own minimum -
 * and asks no permission, while Material You only arrives at 31. Null if
 * there is nothing to read.
 */
private fun wallpaperAccent(context: Context): Color? {
    val argb = runCatching {
        WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
    }.getOrNull() ?: return null
    return Color(argb)
}

/**
 * The baseline scheme with its primary family re-hued to [seed].
 *
 * Not a tonal palette - that wants a library this app does without - so only
 * the hue and some of the saturation are the wallpaper's. Brightness is forced
 * into the band Material uses for the theme, which is what keeps text legible
 * on it whatever the wallpaper happens to be.
 */
private fun ColorScheme.accented(seed: Color, dark: Boolean): ColorScheme {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(seed.toArgb(), hsv)
    fun of(saturation: Float, value: Float) =
        Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], saturation.coerceIn(0f, 1f), value)))

    val s = hsv[1]
    return if (dark) copy(
        primary = of(s.coerceIn(0.10f, 0.55f), 0.92f),
        onPrimary = Color.Black,
        primaryContainer = of(s.coerceIn(0.15f, 0.70f), 0.35f),
        onPrimaryContainer = of(s * 0.3f, 0.95f),
        secondary = of(s * 0.4f, 0.85f),
    ) else copy(
        primary = of(s.coerceIn(0.25f, 0.90f), 0.55f),
        onPrimary = Color.White,
        primaryContainer = of(s * 0.25f, 0.96f),
        onPrimaryContainer = of(s.coerceIn(0.25f, 0.90f), 0.25f),
        secondary = of(s * 0.4f, 0.50f),
    )
}

@Composable
fun SysTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeKey = ThemeChoice.current(context)
    val darkTheme = when (themeKey) {
        stringResource(R.string.system) -> isSystemInDarkTheme()
        stringResource(R.string.light) -> false
        stringResource(R.string.dark) -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        // Material You: the platform hands us a whole palette from the wallpaper.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // Below that there is no palette to ask for, but the wallpaper's own
        // accent is still readable, so the app is at least the right colour.
        else -> {
            val baseline = if (darkTheme) darkColorScheme() else lightColorScheme()
            wallpaperAccent(context)?.let { baseline.accented(it, darkTheme) } ?: baseline
        }
    }
    val m2colors = Colors(
        primary = colorScheme.primary,
        primaryVariant = colorScheme.primaryContainer,
        secondary = colorScheme.secondary,
        background = colorScheme.background,
        surface = colorScheme.surface,
        onPrimary = colorScheme.onPrimary,
        onSecondary = colorScheme.onSecondary,
        onBackground = colorScheme.onBackground,
        onSurface = colorScheme.onSurface,
        secondaryVariant = colorScheme.secondary,
        error = colorScheme.error,
        onError = colorScheme.onError,
        isLight = !darkTheme,
    )

    // Both, because the app mixes the two: most of it is Material 2, while the
    // dividers are Material 3 and would otherwise fall back to the default
    // light scheme.
    androidx.compose.material3.MaterialTheme(colorScheme = colorScheme) {
        MaterialTheme(
            colors = m2colors,
            content = content
        )
    }
}
