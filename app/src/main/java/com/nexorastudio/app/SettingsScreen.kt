package com.nexorastudio.app

import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    focusedBorderColor = Ink,
    unfocusedBorderColor = Color(0x44FFFFFF),
    focusedLabelColor = Ink,
    unfocusedLabelColor = InkDim,
    cursorColor = Ink
)

@Composable
private fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink) }
        Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f), maxLines = 1)
        actions()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: StudioViewModel, onBack: () -> Unit) {
    val s = vm.settings
    var key by remember { mutableStateOf(s.apiKey) }
    var show by remember { mutableStateOf(false) }
    var chat by remember { mutableStateOf(s.chatModel) }
    var fast by remember { mutableStateOf(s.fastModel) }
    var vision by remember { mutableStateOf(s.visionModel) }
    var gen by remember { mutableStateOf(s.genModel) }
    var edit by remember { mutableStateOf(s.editModel) }
    var base by remember { mutableStateOf(s.imageBase) }
    var effort by remember { mutableStateOf(s.effort) }
    var steps by remember { mutableFloatStateOf(s.steps.toFloat()) }
    var accent by remember { mutableStateOf(s.accentTag) }
    var auto by remember { mutableStateOf(s.autoLanguage) }
    var voice by remember { mutableStateOf(s.voiceName) }
    var voiceOptions by remember(accent) { mutableStateOf(vm.voiceOptions(accent)) }
    LaunchedEffect(accent) {
        repeat(8) {
            if (voiceOptions.isEmpty()) {
                delay(400)
                voiceOptions = vm.voiceOptions(accent)
            }
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopBar("SETTINGS", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("NVIDIA API KEY", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; s.apiKey = it },
                label = { Text("nvapi-…") },
                singleLine = true,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = InkDim)
                    }
                }
            )
            Text("Free key from build.nvidia.com. Stored only on this device.", color = InkDim, fontSize = 12.sp)

            Text("MODELS", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp)
            OutlinedTextField(chat, { chat = it; s.chatModel = it }, label = { Text("Main model (code and video editing)") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fast, { fast = it; s.fastModel = it }, label = { Text("Fast chat model") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vision, { vision = it; s.visionModel = it }, label = { Text("Fast vision model (live camera, screen)") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(gen, { gen = it; s.genModel = it }, label = { Text("Image generation model") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(edit, { edit = it; s.editModel = it }, label = { Text("Image editing model") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(base, { base = it; s.imageBase = it }, label = { Text("Image API base URL") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
            Text("Point the base URL at your own FLUX NIM if the hosted preview limits image editing.", color = InkDim, fontSize = 12.sp)

            Text("Normal chat uses the fast chat model. Pictures, the live camera and screen sharing use the fast vision model. The main model is for code and video editing. If a model is slow or unavailable, type another NVIDIA model id here.", color = InkDim, fontSize = 12.sp)

            Text("LANGUAGE AND ACCENT", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reply in the language I write", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = auto,
                    onCheckedChange = { auto = it; s.autoLanguage = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Ink, checkedThumbColor = Color(0xFF05060A))
                )
            }
            Text(
                if (auto) "Pick the accent for the voice and for English spelling." else "NEXORA will always reply in the language you pick below.",
                color = InkDim, fontSize = 12.sp
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Accents.tags.forEach { tag ->
                    FilterChip(
                        selected = accent == tag,
                        onClick = { accent = tag; s.accentTag = tag },
                        label = { Text(Accents.name(tag)) }
                    )
                }
            }
            TextButton(onClick = { vm.previewVoice() }) { Text("TEST VOICE", color = Ink, letterSpacing = 2.sp) }

            Text("VOICE", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp)
            Text(
                "Tap a voice to hear it. Voices marked natural sound the most human and may need internet. You can install more in Android Settings, Text to speech.",
                color = InkDim, fontSize = 12.sp
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = voice.isEmpty(),
                    onClick = { voice = ""; s.voiceName = ""; vm.previewVoice() },
                    label = { Text("Auto (best)") }
                )
                voiceOptions.forEach { o ->
                    FilterChip(
                        selected = voice == o.name,
                        onClick = { voice = o.name; s.voiceName = o.name; vm.previewVoice() },
                        label = { Text(o.label) }
                    )
                }
            }

            Text("CODE REASONING EFFORT", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp)
            Row {
                listOf("low", "medium", "high").forEach { e ->
                    FilterChip(selected = effort == e, onClick = { effort = e; s.effort = e }, label = { Text(e) })
                    Spacer(Modifier.width(8.dp))
                }
            }

            Text("IMAGE QUALITY STEPS: ${steps.roundToInt()}", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp)
            Slider(
                value = steps,
                onValueChange = { steps = it },
                onValueChangeFinished = { s.steps = steps.roundToInt() },
                valueRange = 10f..50f,
                colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Ink, inactiveTrackColor = Color(0x33FFFFFF))
            )
        }
    }
}

/** Runs a generated HTML project in a WebView, images included. */
@Suppress("DEPRECATION")
@Composable
fun PreviewScreen(vm: StudioViewModel, project: String, onBack: () -> Unit) {
    val dir = File(vm.store.root, project)
    val entry = remember(project) {
        File(dir, "index.html").takeIf { it.exists() } ?: dir.walkTopDown().firstOrNull { it.extension == "html" }
    }
    var reload by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopBar(vm.store.displayName(dir).uppercase(), onBack) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Reload", tint = Ink) }
        }
        if (entry == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No HTML file to preview in this project.", color = InkDim)
            }
        } else {
            key(reload) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowFileAccessFromFileURLs = true
                            webViewClient = WebViewClient()
                            loadUrl(Uri.fromFile(entry).toString())
                        }
                    }
                )
            }
        }
    }
}
