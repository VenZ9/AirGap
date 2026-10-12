package com.airgap.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun AirGapTheme(
    themeMode: AppThemeMode = AppThemeMode.DEFAULT,
    content: @Composable () -> Unit
) {
    val colorScheme = getThemeColorScheme(themeMode)
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
