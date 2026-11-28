package com.nexorastudio.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

enum class Screen { STUDIO, SETTINGS, PREVIEW, LIVE, HISTORY }

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        // Replies can only show up as notifications if this is allowed (Android 13 and newer).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { NexoraTheme { NexoraApp() } }
    }

    override fun onStart() {
        super.onStart()
        AppState.visible = true
    }

    override fun onStop() {
        AppState.visible = false
        super.onStop()
    }
}

/** The chat state lives in the Application, so it survives closing the screen. */
@Composable
private fun appStudioViewModel(): StudioViewModel {
    val app = LocalContext.current.applicationContext as Application
    return viewModel(
        viewModelStoreOwner = app as ViewModelStoreOwner,
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
    )
}

@Composable
fun NexoraApp(vm: StudioViewModel = appStudioViewModel(), live: LiveViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.STUDIO) }
    var project by rememberSaveable { mutableStateOf("") }
    var booting by rememberSaveable { mutableStateOf(!BootState.shown) }

    BackHandler(enabled = screen != Screen.STUDIO) { screen = Screen.STUDIO }

    val aura = rememberAura(vm.auraMode)

    CompositionLocalProvider(LocalAura provides aura) {
        Box(Modifier.fillMaxSize()) {
            AuroraBackdrop()
            when (screen) {
                Screen.STUDIO -> StudioScreen(
                    vm = vm,
                    onSettings = { screen = Screen.SETTINGS },
                    onLive = { screen = Screen.LIVE },
                    onHistory = { screen = Screen.HISTORY },
                    onPreview = { project = it; screen = Screen.PREVIEW }
                )
                Screen.SETTINGS -> SettingsScreen(vm) { screen = Screen.STUDIO }
                Screen.PREVIEW -> PreviewScreen(vm, project) { screen = Screen.STUDIO }
                Screen.LIVE -> LiveScreen(live) { screen = Screen.STUDIO }
                Screen.HISTORY -> HistoryScreen(
                    vm = vm,
                    onBack = { screen = Screen.STUDIO },
                    onOpen = { id -> vm.openChat(id); screen = Screen.STUDIO }
                )
            }
            AnimatedVisibility(visible = booting, exit = fadeOut(tween(600))) {
                BootScreen {
                    BootState.shown = true
                    booting = false
                }
            }
        }
    }
}
