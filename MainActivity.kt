package com.nexorastudio.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

enum class Screen { STUDIO, SETTINGS, PREVIEW }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent { NexoraTheme { NexoraApp() } }
    }
}

@Composable
fun NexoraApp(vm: StudioViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.STUDIO) }
    var project by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = screen != Screen.STUDIO) { screen = Screen.STUDIO }

    val aura = rememberAura(vm.auraMode)

    CompositionLocalProvider(LocalAura provides aura) {
        Box(Modifier.fillMaxSize()) {
            AuroraBackdrop()
            when (screen) {
                Screen.STUDIO -> StudioScreen(
                    vm = vm,
                    onSettings = { screen = Screen.SETTINGS },
                    onPreview = { project = it; screen = Screen.PREVIEW }
                )
                Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.STUDIO }
                Screen.PREVIEW -> PreviewScreen(vm, project) { screen = Screen.STUDIO }
            }
        }
    }
}
