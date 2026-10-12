package com.airgap.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun AirGapTheme(
    themeMode: AppThemeMode = AppThemeMode.DEFAULT,
    accent: AccentColorChoice = AccentColorChoice.LIME,
    content: @Composable () -> Unit
) {
    val colorScheme = createColorScheme(themeMode, accent)
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
