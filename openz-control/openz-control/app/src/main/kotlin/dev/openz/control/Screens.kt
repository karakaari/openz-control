package dev.openz.control

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openz.core.Format

@Composable
fun App(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            if (state.connected) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = state.tab == tab,
                            onClick = { vm.selectTab(tab) },
                            icon = {},
                            label = { Text(tab.name) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.notice?.let { notice ->
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF333333)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(notice, color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::clearNotice) { Text("OK") }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!state.connected) {
                    ConnectScreen(state, vm)
                } else {
                    when (state.tab) {
                        Tab.Monitor -> MonitorScreen(state, vm)
                        Tab.Gallery -> GalleryScreen(state, vm)
                        Tab.Debug -> DebugScreen(state, vm)
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectScreen(state: UiState, vm: CameraViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OpenZ Control", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(state.message, color = Color.LightGray, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = vm::connect, enabled = !state.busy) {
            Text(if (state.busy) "Connecting…" else "Connect camera")
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "Unofficial, open-source, experimental. Not affiliated with or endorsed by Nikon.",
            color = Color.Gray,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun MonitorScreen(state: UiState, vm: CameraViewModel) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val frame = state.frame
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = "Live view",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("Waiting for live view…", color = Color.Gray)
            }
            Row(
                Modifier.align(Alignment.TopStart).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.model, color = Color.White, fontSize = 12.sp)
                state.battery?.let { Text("Batt $it", color = Color.White, fontSize = 12.sp) }
                if (state.recording) {
                    Text("● REC", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.controls.forEach { control ->
                ControlChip(control, vm) { vm.setControl(control.code, it) }
            }
        }

        Button(
            onClick = vm::toggleRecording,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.recording) Color.DarkGray else Color(0xFFD32F2F),
                contentColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth().padding(12.dp).height(56.dp),
        ) {
            Text(if (state.recording) "STOP" else "REC", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ControlChip(control: Control, vm: CameraViewModel, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = control.writable && control.choices.isNotEmpty(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(control.name, fontSize = 10.sp, color = Color.Gray)
                Text(
                    Format.forProp(control.code, control.current, vm.propMap),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            control.choices.forEach { value ->
                DropdownMenuItem(
                    text = { Text(Format.forProp(control.code, value, vm.propMap)) },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                )
            }
        }
    }
}

@Composable
fun GalleryScreen(state: UiState, vm: CameraViewModel) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        val progress = state.downloadProgress
        if (progress != null) {
            Text("Downloading ${state.downloadName}", color = Color.White, fontSize = 12.sp)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        if (state.busy && state.gallery.isEmpty()) Text("Reading card…", color = Color.Gray)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.gallery, key = { it.obj.handle }) { item ->
                GalleryCell(item, enabled = progress == null) { vm.download(item) }
            }
        }
    }
}

@Composable
private fun GalleryCell(item: GalleryItem, enabled: Boolean, onDownload: () -> Unit) {
    Column(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color(0xFF1C1C1C))) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.DarkGray), contentAlignment = Alignment.Center) {
            val thumb = item.thumb
            if (thumb != null) {
                Image(bitmap = thumb, contentDescription = item.obj.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(if (item.obj.isVideo) "VIDEO" else "NO PREVIEW", color = Color.LightGray, fontSize = 11.sp)
            }
        }
        Text(item.obj.name, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        Text(
            formatSize(item.obj.size),
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        TextButton(onClick = onDownload, enabled = enabled) { Text("Save to phone") }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes == 0xFFFFFFFFL -> ">4 GB"
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

@Composable
fun DebugScreen(state: UiState, vm: CameraViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::runDiagnostics, enabled = !state.busy) { Text("Run diagnostics") }
            val text = state.diagnostics
            if (text != null) {
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                }) { Text("Share") }
            }
            OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Diagnostics list every setting the camera exposes. Attach them when reporting a " +
                "problem so unsupported models and firmware can be fixed.",
            color = Color.Gray,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            state.diagnostics ?: "",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        )
    }
}
