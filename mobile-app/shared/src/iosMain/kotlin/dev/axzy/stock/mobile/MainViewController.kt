package dev.axzy.stock.mobile

import androidx.compose.ui.window.ComposeUIViewController

/** Entry point of the iOS host: `iosApp/iosApp/ContentView.swift` embeds this controller. */
fun MainViewController() = ComposeUIViewController { App() }
