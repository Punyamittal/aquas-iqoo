package com.smriti.aqua.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

// Same navy / cyan palette as the AquaScope home screen (app/src/main/res/values/colors.xml).
val NavyDeep = Color(0xFF071A2B)   // background
val Navy = Color(0xFF0C2A43)       // panels
val NavyMid = Color(0xFF0E3A58)    // raised panels, tracks
val Panel = Color(0xE60C2A43)      // translucent card, matches bg_home_panel
val Hairline = Color(0xFF1E3F55)   // outlines
val Cyan = Color(0xFF4AAFC2)       // accent, normal
val CyanSoft = Color(0x334AAFC2)   // accent container
val CyanBloom = Color(0xFFC8FCFF)  // spectrogram hot end
val WarmWhite = Color(0xFFF5F5F0)  // primary text
val WarmMuted = Color(0xFFB7C9C8)  // secondary text
val WarmFaint = Color(0xFF7A9398)  // tertiary text
val Amber = Color(0xFFD99A3A)      // unusual
val AmberSoft = Color(0x33D99A3A)
val Alert = Color(0xFFE07A68)      // confirmed
val AlertSoft = Color(0x33B84A3A)

private val AquaDarkColors = darkColorScheme(
    primary = Cyan,
    onPrimary = NavyDeep,
    primaryContainer = CyanSoft,
    onPrimaryContainer = WarmWhite,
    secondary = WarmMuted,
    onSecondary = NavyDeep,
    secondaryContainer = NavyMid,
    onSecondaryContainer = WarmWhite,
    tertiary = Amber,
    onTertiary = NavyDeep,
    background = NavyDeep,
    onBackground = WarmWhite,
    surface = NavyDeep,
    onSurface = WarmWhite,
    surfaceVariant = Panel,
    onSurfaceVariant = WarmMuted,
    outline = Hairline,
    outlineVariant = Hairline,
    error = Alert,
    onError = NavyDeep,
    errorContainer = AlertSoft,
    onErrorContainer = WarmWhite,
)

private val base = Typography()

// Large titles are light like the home screen's "No walls remembered yet."
private val AquaTypography = base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Light),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Light),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Light),
)

@Composable
fun AquaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AquaDarkColors,
        typography = AquaTypography,
        content = content,
    )
}
