package com.billing.pos.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val windowState = rememberWindowState(
        position = WindowPosition(androidx.compose.ui.Alignment.Center),
        width = 1200.dp,
        height = 800.dp
    )
    Window(onCloseRequest = ::exitApplication, title = "POS Billing", state = windowState) {
        MaterialTheme {
            DashboardScreen()
        }
    }
}
