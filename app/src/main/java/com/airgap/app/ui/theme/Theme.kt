package com.airgap.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun AirGapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AirGapColorScheme,
        content = content
    )
}
