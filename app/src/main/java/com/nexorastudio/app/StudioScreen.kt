package com.nexorastudio.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// =====================================================================================
//  Main studio screen
// =====================================================================================

@Composable
fun StudioScreen(vm: StudioViewModel, onSettings: () -> Unit, onPreview: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Live routing: the colors and the header follow the specialist chosen for what you are typing.
    val live = if (input.isBlank() && vm.attachment == null && vm.manualMode == Mode.AUTO) vm.activeMode
    else Router.route(input, vm.attachment, vm.manualMode)
    LaunchedEffect(live) { vm.auraMode = live }
    LaunchedEffect(vm.items.size) { if (vm.items.isNotEmpty()) listState.animateScrollToItem(vm.items.lastIndex) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attach(uri, AttachKind.VIDEO)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attach(uri, AttachKind.IMAGE)
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Header(live, onNew = { vm.newChat() }, onSettings = onSettings)
        ModeStrip(vm.manualMode, live) { vm.manualMode = it }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.items.isEmpty()) {
                EmptyState { input = it }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(vm.items, key = { it.id }) { m -> MsgView(m, vm, onPreview) }
                }
            }
        }

        if (vm.busy) StatusBar(vm.status, vm.progress) { vm.stop() }

        Composer(
            input = input,
            onInput = { input = it },
            attachment = vm.attachment,
            onClearAttachment = { vm.clearAttachment() },
            onPickVideo = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = !vm.busy && (input.isNotBlank() || vm.attachment != null),
            onSend = { vm.send(input); input = "" }
        )
    }
}

// =====================================================================================
//  Header, mode strip, composer, status
// =====================================================================================

@Composable
private fun Header(mode: Mode, onNew: () -> Unit, onSettings: () -> Unit) {
    val aura = LocalAura.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AuraOrb(Modifier.size(38.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "NEXORA",
                style = TextStyle(
                    brush = Brush.horizontalGradient(listOf(aura.c1, aura.c2)),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp
                )
            )
            Text("AI STUDIO  ·  ${mode.label.uppercase()}", color = InkDim, fontSize = 10.sp, letterSpacing = 3.sp)
        }
        IconButton(onClick = onNew) { Icon(Icons.Default.Add, "New chat", tint = Ink) }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings", tint = Ink) }
    }
}

@Composable
private fun ModeStrip(manual: Mode, routed: Mode, onPick: (Mode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Mode.values().forEach { m ->
            val selected = manual == m
            val label = if (m == Mode.AUTO && selected) "AUTO › ${routed.label.uppercase()}" else m.label.uppercase()
            ModeChip(label, selected) { onPick(m) }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val aura = LocalAura.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.auraFill(aura, 14.dp, 0.22f).auraGlowBorder(aura, 14.dp)
                else Modifier.border(1.dp, Color(0x33FFFFFF), shape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) Ink else InkDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp
        )
    }
}

@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    attachment: Attachment?,
    onClearAttachment: () -> Unit,
    onPickVideo: () -> Unit,
    onPickImage: () -> Unit,
    enabled: Boolean,
    onSend: () -> Unit
) {
    val aura = LocalAura.current
    var menu by remember { mutableStateOf(false) }

    Glass(Modifier.fillMaxWidth().padding(12.dp), radius = 28.dp) {
        Column {
            if (attachment != null) {
                Row(
                    Modifier.padding(start = 14.dp, top = 10.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (attachment.kind == AttachKind.IMAGE) {
                        UriThumb(attachment.uri, 44.dp)
                    } else {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Movie, null, tint = Ink) }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (attachment.kind == AttachKind.VIDEO) "Video attached" else "Image attached",
                        color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClearAttachment) { Icon(Icons.Default.Close, "Remove", tint = InkDim) }
                }
            }
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.AttachFile, "Attach", tint = Ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Video to edit") },
                            leadingIcon = { Icon(Icons.Default.Movie, null) },
                            onClick = { menu = false; onPickVideo() }
                        )
                        DropdownMenuItem(
                            text = { Text("Image") },
                            leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = { menu = false; onPickImage() }
                        )
                    }
                }
                TextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Build, edit, create, ask…", color = InkDim) },
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        cursorColor = Ink
                    )
                )
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .drawBehind {
                            drawCircle(
                                Brush.linearGradient(
                                    listOf(aura.c1, aura.c2),
                                    start = Offset.Zero,
                                    end = Offset(size.width, size.height)
                                ),
                                alpha = if (enabled) 1f else 0.3f
                            )
                        }
                        .clickable(enabled = enabled, onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Void)
                }
            }
        }
    }
}

@Composable
private fun StatusBar(text: String, progress: Float?, onStop: () -> Unit) {
    val aura = LocalAura.current
    val t = rememberInfiniteTransition(label = "statusbar")
    val x = t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "x"
    )
    Glass(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), radius = 16.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = Ink, fontSize = 13.sp, maxLines = 2, modifier = Modifier.weight(1f))
                TextButton(onClick = onStop) { Text("STOP", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x22FFFFFF))
                    .drawBehind {
                        val brush = Brush.horizontalGradient(listOf(aura.c1, aura.c2, aura.c3))
                        if (progress != null) {
                            drawRect(brush, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
                        } else {
                            val w = size.width * 0.35f
                            val left = (size.width + w) * x.value - w
                            drawRect(brush, topLeft = Offset(left, 0f), size = Size(w, size.height))
                        }
                    }
            )
        }
    }
}

// =====================================================================================
//  Empty state
// =====================================================================================

private data class Cap(val icon: ImageVector, val title: String, val example: String)

@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    val caps = listOf(
        Cap(Icons.Default.Code, "CODE", "Build a landing page for a coffee shop with a hero photo"),
        Cap(Icons.Default.Movie, "VIDEO", "Attach a clip: trim the first 5s, make it noir, add a caption"),
        Cap(Icons.Default.Brush, "IMAGE", "Generate a cyberpunk city at night, wide wallpaper"),
        Cap(Icons.Default.AutoAwesome, "GENERAL", "Explain how transformers work like I'm 12")
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AuraOrb(Modifier.size(120.dp))
        Spacer(Modifier.height(16.dp))
        Text("What shall we create?", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Four specialists, one screen. Just ask and NEXORA switches to the right one.",
            color = InkDim, fontSize = 13.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        caps.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    Glass(Modifier.weight(1f), radius = 18.dp, onClick = { onPick(c.example) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(c.icon, null, tint = Ink)
                            Text(c.title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp)
                            Text(c.example, color = InkDim, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================================
//  Messages
// =====================================================================================

@Composable
private fun MsgView(m: Msg, vm: StudioViewModel, onPreview: (String) -> Unit) {
    when (m) {
        is UserMsg -> UserBubble(m)
        is AiMsg -> AiBubble(m)
        is ProjectMsg -> ProjectCard(m, vm, onPreview)
        is ImageMsg -> ImageCard(m, vm)
        is VideoMsg -> VideoCard(m)
        is ErrorMsg -> ErrorBubble(m)
    }
}

@Composable
private fun UserBubble(m: UserMsg) {
    val aura = LocalAura.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .auraFill(aura, 20.dp, 0.22f)
                .auraGlowBorder(aura, 20.dp)
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                m.attachment?.let { a ->
                    if (a.kind == AttachKind.IMAGE) UriThumb(a.uri, 120.dp)
                    else Text("🎞  Video attached", color = InkDim, fontSize = 12.sp)
                }
                if (m.text.isNotBlank()) Text(m.text, color = Ink, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ModeTag(mode: Mode) {
    val aura = LocalAura.current
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .auraGlowBorder(aura, 8.dp, 1.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(mode.label.uppercase(), color = InkDim, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AiBubble(m: AiMsg) {
    Glass(Modifier.fillMaxWidth(), radius = 20.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeTag(m.mode)
            if (m.text.isBlank() && m.streaming) Text("…", color = InkDim) else RichText(m.text)
        }
    }
}

@Composable
private fun ErrorBubble(m: ErrorMsg) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x33FF6B7A))
            .padding(12.dp)
    ) { SelectionContainer { Text(m.text, color = Color(0xFFFFB3BB), fontSize = 13.sp) } }
}

@Composable
private fun RichText(text: String) {
    val parts = remember(text) { text.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { i, part ->
            if (i % 2 == 0) {
                if (part.isNotBlank()) {
                    SelectionContainer { Text(part.trim(), color = Ink, fontSize = 15.sp, lineHeight = 22.sp) }
                }
            } else {
                val lang = if (part.contains('\n')) part.substringBefore('\n').trim() else ""
                val code = if (part.contains('\n')) part.substringAfter('\n').trimEnd() else part.trimEnd()
                CodeBlock(code, lang)
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, lang: String) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF080A10))
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "code" }.uppercase(), color = InkDim, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(code))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.ContentCopy, "Copy", tint = InkDim, modifier = Modifier.size(18.dp)) }
        }
        Box(
            Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            SelectionContainer {
                Text(code, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun PillButton(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val aura = LocalAura.current
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .auraFill(aura, 14.dp, 0.14f)
            .auraGlowBorder(aura, 14.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    }
}

// ---------------------------------------------------------------- project (code + images wired)

@Composable
private fun ProjectCard(m: ProjectMsg, vm: StudioViewModel, onPreview: (String) -> Unit) {
    val ctx = LocalContext.current
    val dir = File(vm.store.root, m.project)
    val files = remember(m.id) { vm.store.listRelative(dir) }
    val images = files.filter { it.startsWith("assets/") && it.endsWith(".png") }

    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeTag(Mode.CODE)
            Text(vm.store.displayName(dir), color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (m.text.isNotBlank()) Text(m.text, color = Ink, fontSize = 14.sp)
            if (images.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images) { rel -> FileThumb(File(dir, rel), 96.dp) }
                }
            }
            files.forEach { rel -> FileRow(File(dir, rel), rel) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("PREVIEW", Icons.Default.PlayArrow, Modifier.weight(1f)) { onPreview(m.project) }
                PillButton("EXPORT ZIP", Icons.Default.Share, Modifier.weight(1f)) { shareZip(ctx, vm.store, dir) }
            }
        }
    }
}

@Composable
private fun FileRow(file: File, rel: String) {
    var open by remember { mutableStateOf(false) }
    if (rel.endsWith(".png")) return
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Description, null, tint = InkDim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(rel, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = InkDim)
        }
        if (open) {
            val text by produceState("", file.path) {
                value = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("") }
            }
            CodeBlock(text, rel.substringAfterLast('.', ""))
        }
    }
}

fun shareZip(ctx: Context, store: ProjectStore, dir: File) {
    val zip = store.zip(dir)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", zip)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Export project"))
}

// ---------------------------------------------------------------- image result

@Composable
private fun ImageCard(m: ImageMsg, vm: StudioViewModel) {
    val ctx = LocalContext.current
    val file = File(m.path)
    val bmp by produceState<ImageBitmap?>(null, m.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(m.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeTag(m.mode)
            bmp?.let {
                Image(
                    it, null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(14.dp))
                )
            }
            Text(m.text, color = InkDim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("SAVE", Icons.Default.Download, Modifier.weight(1f)) {
                    val ok = runCatching { MediaUtils.saveToGallery(ctx, file, false) }.getOrDefault(false)
                    Toast.makeText(ctx, if (ok) "Saved to Pictures/Nexora" else "Could not save", Toast.LENGTH_SHORT).show()
                }
                PillButton("EDIT THIS", Icons.Default.Edit, Modifier.weight(1f)) { vm.attachFile(file) }
            }
        }
    }
}

// ---------------------------------------------------------------- video result

@OptIn(UnstableApi::class)
@Composable
private fun VideoCard(m: VideoMsg) {
    val ctx = LocalContext.current
    val player = remember(m.path) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(m.path))))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeTag(Mode.VIDEO)
            AndroidView(
                factory = { c -> PlayerView(c).apply { this.player = player; useController = true } },
                modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(14.dp))
            )
            Text(m.text, color = Ink, fontSize = 14.sp)
            PillButton("SAVE TO GALLERY", Icons.Default.Download, Modifier.fillMaxWidth()) {
                val ok = runCatching { MediaUtils.saveToGallery(ctx, File(m.path), true) }.getOrDefault(false)
                Toast.makeText(ctx, if (ok) "Saved to Movies/Nexora" else "Could not save", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ---------------------------------------------------------------- thumbnails

@Composable
private fun UriThumb(uri: Uri, size: Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF))) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun FileThumb(file: File, size: Dp) {
    val bmp by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Color(0x22FFFFFF))) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
