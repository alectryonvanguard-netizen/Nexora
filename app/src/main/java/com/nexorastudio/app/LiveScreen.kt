package com.nexorastudio.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun LiveScreen(vm: LiveViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val settings = remember { AppSettings(ctx) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.start() else vm.fail("Microphone permission is needed for a live conversation.")
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setCamera(true) else vm.fail("Camera permission was denied.")
    }
    val screenCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) vm.onScreenGranted(result.resultCode, data)
    }

    LaunchedEffect(Unit) {
        if (ctx.has(Manifest.permission.RECORD_AUDIO)) vm.start() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(Unit) { onDispose { vm.end() } }

    // The camera is (re)bound whenever it is switched, flipped, or the preview appears or disappears.
    LaunchedEffect(vm.showPreview, vm.cameraOn) {
        if (!(vm.showPreview && vm.cameraOn)) previewView = null
    }
    LaunchedEffect(vm.cameraOn, vm.showPreview, vm.frontCamera, previewView) {
        if (vm.cameraOn) vm.rebindCamera(owner, if (vm.showPreview) previewView?.surfaceProvider else null)
    }

    val energy = animateFloatAsState(
        targetValue = when (vm.phase) {
            LivePhase.SPEAKING -> 1f
            LivePhase.THINKING -> 0.6f
            LivePhase.LISTENING -> 0.25f
            LivePhase.IDLE -> 0f
        },
        animationSpec = tween(600),
        label = "energy"
    )
    val orbScale by animateFloatAsState(
        targetValue = when (vm.phase) {
            LivePhase.SPEAKING -> 1.12f
            LivePhase.THINKING -> 0.92f
            else -> 1f
        },
        animationSpec = tween(500),
        label = "orbScale"
    )

    Box(Modifier.fillMaxSize()) {
        ParticleBackdrop(Modifier.fillMaxSize(), energy)

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Ink) }
                Column(Modifier.weight(1f)) {
                    Text("LIVE", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 4.sp)
                    Text(Accents.name(settings.accentTag), color = InkDim, fontSize = 11.sp)
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(if (vm.muted) "MIC MUTED" else "MIC ON")
                if (vm.cameraOn) StatusPill("CAMERA ON")
                if (vm.screenOn) StatusPill("SCREEN SHARED")
            }

            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AuraOrb(Modifier.size(220.dp).scale(orbScale).clip(CircleShape).clickable { vm.tapOrb() })
                Spacer(Modifier.height(16.dp))
                Text(
                    when (vm.phase) {
                        LivePhase.IDLE -> if (vm.muted) "Mic is muted" else "Tap the orb to start"
                        LivePhase.LISTENING -> "Listening…"
                        LivePhase.THINKING -> "Thinking…"
                        LivePhase.SPEAKING -> "Speaking, just talk to interrupt"
                    },
                    color = InkDim, fontSize = 12.sp, letterSpacing = 2.sp
                )
                Spacer(Modifier.height(14.dp))
                if (vm.heard.isNotBlank()) {
                    Text(vm.heard, color = InkDim, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 3)
                    Spacer(Modifier.height(10.dp))
                }
                Box(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    Text(vm.reply, color = Ink, fontSize = 19.sp, lineHeight = 27.sp, textAlign = TextAlign.Center)
                }
                vm.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Color(0xFFFFB3BB), fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundButton(if (vm.muted) Icons.Default.MicOff else Icons.Default.Mic, !vm.muted) { vm.toggleMute() }
                RoundButton(if (vm.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff, vm.cameraOn) {
                    if (vm.cameraOn) vm.setCamera(false)
                    else if (ctx.has(Manifest.permission.CAMERA)) vm.setCamera(true)
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                }
                if (vm.cameraOn) {
                    RoundButton(Icons.Default.Cameraswitch, false) { vm.flipCamera() }
                    RoundButton(if (vm.showPreview) Icons.Default.Visibility else Icons.Default.VisibilityOff, vm.showPreview) {
                        vm.togglePreview()
                    }
                }
                RoundButton(if (vm.screenOn) Icons.Default.StopScreenShare else Icons.Default.ScreenShare, vm.screenOn) {
                    if (vm.screenOn) {
                        vm.stopScreen()
                    } else {
                        val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenCapture.launch(mgr.createScreenCaptureIntent())
                    }
                }
                RoundButton(Icons.Default.CallEnd, false, onClick = onClose)
            }
        }

        // Optional camera picture, only when you turn "show camera" on
        if (vm.cameraOn && vm.showPreview) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(top = 96.dp, end = 16.dp)
                    .size(width = 108.dp, height = 148.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(16.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        PreviewView(c).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                    },
                    update = { previewView = it }
                )
            }
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    val aura = LocalAura.current
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .auraGlowBorder(aura, 10.dp, 1.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = Ink, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val aura = LocalAura.current
    Box(
        Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(Color(0xB30B0D0B))
            .then(if (active) Modifier.auraGlowBorder(aura, 25.dp, 1.6.dp) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Ink)
    }
}
