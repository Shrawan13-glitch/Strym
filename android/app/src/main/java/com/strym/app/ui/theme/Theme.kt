package com.strym.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val Colors = darkColorScheme()

/** A camera app is dark regardless of system theme (viewfinder contrast). */
@Composable
fun StrymTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
