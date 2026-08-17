package com.billing.pos.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.billing.pos.shared.greeting

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "POS Billing") {
        MaterialTheme {
            Surface {
                Text(greeting())
            }
        }
    }
}
