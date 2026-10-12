package com.airgap.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF101114)
private val Panel = Color(0xFF1B1D22)
private val Lime = Color(0xFFC8FF4D)
private val Muted = Color(0xFF9DA3AD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AirGapApp() }
    }
}

@Composable
private fun AirGapApp() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    var mode by remember { mutableStateOf("home") }
    var error by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var senderFrames by remember { mutableStateOf<List<String>>(emptyList()) }
    var fileLabel by remember { mutableStateOf("") }
    var frameIndex by remember { mutableIntStateOf(0) }
    val received = remember { mutableStateMapOf<Int, TransferProtocol.Frame>() }
    var receivedName by remember { mutableStateOf("") }
    var receivedTotal by remember { mutableIntStateOf(0) }
    var readyToSave by remember { mutableStateOf<ByteArray?>(null) }
    var outputName by remember { mutableStateOf("airgap-file.bin") }
    var outputMime by remember { mutableStateOf("application/octet-stream") }
    var saveBytes by remember { mutableStateOf<ByteArray?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = saveBytes
        if (uri != null && bytes != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Could not open destination") }
                .onSuccess { error = "Saved successfully." }
                .onFailure { error = it.message ?: "Save failed." }
        }
        saveBytes = null
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else "shared-file.bin"
                } ?: "shared-file.bin"
            }.getOrDefault("shared-file.bin")
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read file")
                if (bytes.size > TransferProtocol.MAX_FILE_BYTES) throw IllegalArgumentException("MVP limit is 10 MiB. Choose a smaller file.")
                if (passphrase.length < 8) throw IllegalArgumentException("Enter a passphrase of at least 8 characters first.")
                val prepared = TransferProtocol.prepare(bytes, name, mime, passphrase.toCharArray())
                senderFrames = prepared.frames
                passphrase = ""
                fileLabel = "${prepared.fileName} · ${prepared.size / 1024} KB · ${prepared.frames.size} QR frames"
                frameIndex = 0
                mode = "send"
                error = null
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }.onFailure { error = it.message ?: "Could not prepare file." }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { mode = "receive"; error = null } else error = "Camera permission is required to receive QR frames."
    }

    LaunchedEffect(mode, senderFrames.size) {
        if (mode == "send" && senderFrames.isNotEmpty()) {
            while (true) { delay(320); frameIndex = (frameIndex + 1) % senderFrames.size }
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Lime, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
        Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(Lime, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Text("A", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("AirGap", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("OFFLINE OPTICAL TRANSFER", fontSize = 10.sp, color = Muted, letterSpacing = 1.5.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("● LOCAL", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            when (mode) {
                "home" -> {
                    Spacer(Modifier.height(10.dp))
                    Text("Files. Shared by light.", fontSize = 31.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                    Text("No network. No pairing. Just two screens and a camera.", color = Muted, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, label = { Text("Shared passphrase (8+ characters)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("Both phones must use the exact same passphrase.", color = Muted, fontSize = 12.sp)
                    PrimaryAction("Send a file", "Choose any file") { if (passphrase.length < 8) error = "Enter a passphrase of at least 8 characters." else pickLauncher.launch(arrayOf("*/*")) }
                    PrimaryAction("Receive a file", "Scan the sender's animated QR") {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) mode = "receive"
                        else permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    Spacer(Modifier.height(8.dp))
                    InfoCard("MVP limits", "Files up to 10 MiB · 360-byte chunks · AES-256-GCM per chunk · SHA-256 verification")
                    InfoCard("Transfer reality", "Keep both screens awake and steady. QR transfers can be slow; the sender cycles frames until the receiver has them all.")
                }
                "send" -> {
                    Text("TRANSMITTING", color = Lime, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(fileLabel, color = Muted, fontSize = 13.sp)
                    Text("Frame ${frameIndex + 1} / ${senderFrames.size}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    val bitmap = remember(senderFrames, frameIndex) { senderFrames.getOrNull(frameIndex)?.let { qrBitmap(it, 780) } }
                    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Animated AirGap QR frame", modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp))
                    Text("Ask the receiver to keep scanning. Frames repeat in a loop.", color = Muted, fontSize = 13.sp)
                    PrimaryAction("Stop transfer", "Return to home") { senderFrames = emptyList(); mode = "home"; passphrase = "" }
                }
                "receive" -> {
                    Text("RECEIVE", color = Lime, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Point the camera at the sender's QR. Frames are collected automatically.", color = Muted, fontSize = 13.sp)
                    CameraScanner(enabled = true, onFrame = { raw ->
                        runCatching {
                            val f = TransferProtocol.parse(raw)
                            if (received.isNotEmpty() && received.values.first().session != f.session) return@runCatching
                            if (received.isEmpty()) { receivedName = f.name; receivedTotal = f.total }
                            if (f.total == receivedTotal) received.putIfAbsent(f.index, f)
                        }.onFailure { /* Ignore non-AirGap QR codes and malformed frames. */ }
                    })
                    LinearProgressIndicator(progress = { if (receivedTotal > 0) received.size.toFloat() / receivedTotal else 0f }, modifier = Modifier.fillMaxWidth(), color = Lime)
                    Text(if (receivedTotal > 0) "${received.size} / $receivedTotal frames · $receivedName" else "Waiting for AirGap frames…", color = Color.White)
                    if (receivedTotal > 0 && received.size == receivedTotal) {
                        Text("All frames captured. Enter the same passphrase used by the sender.", color = Lime, fontSize = 13.sp)
                        OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, label = { Text("Passphrase") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        PrimaryAction("Decrypt and verify", "Authenticate each chunk and check SHA-256") {
                            runCatching {
                                val (name, bytes) = TransferProtocol.decrypt(received.values.toList(), passphrase.toCharArray())
                                passphrase = ""
                                outputName = name.ifBlank { "airgap-file.bin" }
                                outputMime = "application/octet-stream"
                                readyToSave = bytes
                                error = "Verified. Choose where to save the file."
                            }.onFailure { error = it.message ?: "Decryption failed." }
                        }
                        if (readyToSave != null) PrimaryAction("Save file", outputName) {
                            saveBytes = readyToSave
                            saveLauncher.launch(outputName.substringAfterLast('/').take(120).ifBlank { "airgap-file.bin" })
                            readyToSave = null
                        }
                    }
                    PrimaryAction("Cancel receive", "Clear captured frames") { received.clear(); receivedTotal = 0; receivedName = ""; readyToSave = null; mode = "home" }
                }
            }
            if (error != null) {
                Text(error!!, color = if (error!!.contains("successfully") || error!!.contains("Verified")) Lime else Color(0xFFFF8A80), fontSize = 13.sp)
                TextButton(onClick = { error = null }) { Text("Dismiss", color = Muted) }
            }
            Spacer(Modifier.height(8.dp))
            Text("AIRGAP 0.1.0 · NETWORK PERMISSIONS: NONE", color = Color(0xFF666C75), fontSize = 10.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun PrimaryAction(title: String, subtitle: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Color.White), contentPadding = PaddingValues(18.dp)) {
        Column(Modifier.fillMaxWidth()) { Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text(subtitle, color = Muted, fontSize = 12.sp) }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = Lime, fontWeight = FontWeight.Bold)
        Text(body, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

private fun qrBitmap(contents: String, size: Int): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val matrix = MultiFormatWriter().encode(contents, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun CameraScanner(enabled: Boolean, onFrame: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    DisposableEffect(Unit) { onDispose { scanner.close(); executor.shutdown() } }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(300.dp).background(Panel, RoundedCornerShape(16.dp)),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || !enabled) { proxy.close(); return@setAnalyzer }
                    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener { codes -> codes.forEach { it.rawValue?.let(onFrame) } }
                        .addOnCompleteListener { proxy.close() }
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) { }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}
