package com.mtzallqmy.aiagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared visual tokens. Feature screens should consume these instead of hard-coded values. */
object AegisColors {
    val Mint = Color(0xFF33E8C6)
    val MintDark = Color(0xFF1DB39A)
    val Ink = Color(0xFF101216)
    val SurfaceDark = Color(0xFF1A1D23)
    val SurfaceVariantDark = Color(0xFF2A2E37)
    val CanvasLight = Color(0xFFF7F8FC)
    val SurfaceVariantLight = Color(0xFFEEF1F7)
    val AccentBlue = Color(0xFFA8C7FA)
    val AccentBlueDark = Color(0xFF445599)
}

object AegisSpacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp
}

object AegisElevation {
    val flat: Dp = 0.dp
    val low: Dp = 1.dp
    val medium: Dp = 3.dp
    val high: Dp = 6.dp
}

object AegisMotion {
    const val fastMillis: Int = 120
    const val standardMillis: Int = 220
    const val emphasizedMillis: Int = 320
}

object AegisIcons {
    val Info: ImageVector = Icons.Default.Info
    val Success: ImageVector = Icons.Default.CheckCircle
    val Warning: ImageVector = Icons.Default.Warning
    val Error: ImageVector = Icons.Default.Error
    val Security: ImageVector = Icons.Default.Lock
}

val AegisTypography = Typography()
val AegisShapes = Shapes()

private val AegisDarkColorScheme = darkColorScheme(
    primary = AegisColors.Mint,
    onPrimary = Color(0xFF04251C),
    secondary = AegisColors.AccentBlue,
    background = AegisColors.Ink,
    surface = AegisColors.SurfaceDark,
    surfaceVariant = AegisColors.SurfaceVariantDark,
)

private val AegisLightColorScheme = lightColorScheme(
    primary = AegisColors.MintDark,
    onPrimary = Color.White,
    secondary = AegisColors.AccentBlueDark,
    background = AegisColors.CanvasLight,
    surface = Color.White,
    surfaceVariant = AegisColors.SurfaceVariantLight,
)

@Composable
fun AegisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AegisDarkColorScheme else AegisLightColorScheme,
        typography = AegisTypography,
        shapes = AegisShapes,
        content = content,
    )
}
