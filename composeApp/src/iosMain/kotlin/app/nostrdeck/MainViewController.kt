package app.nostrdeck

import androidx.compose.ui.window.ComposeUIViewController

/**
 * iOS の入口。Swift 側（iosApp）から `MainViewControllerKt.MainViewController()` を呼び出し、
 * SwiftUI の UIViewControllerRepresentable でホストする。
 */
fun MainViewController() = ComposeUIViewController { App() }
