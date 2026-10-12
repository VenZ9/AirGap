package com.airgap.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.airgap.app.ui.theme.*

class MainActivity : ComponentActivity() {
    private val viewModel: AirGapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AirGapTheme {
                AirGapMainScreen(viewModel = viewModel)
            }
        }
    }

    fun keepScreenOn(enable: Boolean) {
        if (enable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirGapMainScreen(viewModel: AirGapViewModel) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val currentScreen by viewModel.screen.collectAsState()
    val statusMsg by viewModel.statusMessage.collectAsState()
    val isError by viewModel.isErrorMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusMsg) {
        statusMsg?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(currentScreen) {
        activity.keepScreenOn(currentScreen == Screen.Send || currentScreen == Screen.Receive)
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    containerColor = if (isError) AccentError else SurfaceElevatedDark,
                    contentColor = if (isError) Color.White else AccentPrimary,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(data.visuals.message, fontWeight = FontWeight.Medium)
                }
            }
        },
        containerColor = BgDark,
        topBar = {
            AirGapTopBar(
                screen = currentScreen,
                onBack = {
                    when (currentScreen) {
                        Screen.Send -> viewModel.resetTransmission()
                        Screen.Receive -> {
                            viewModel.resetReceiver()
                            viewModel.navigateTo(Screen.Home)
                        }
                        Screen.Home -> {}
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                label = "ScreenTransition"
            ) { target ->
                when (target) {
                    Screen.Home -> HomeScreen(viewModel = viewModel)
                    Screen.Send -> SendScreen(viewModel = viewModel)
                    Screen.Receive -> ReceiveScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirGapTopBar(
    screen: Screen,
    onBack: () -> Unit
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = BgDark,
            titleContentColor = TextPrimary
        ),
        navigationIcon = {
            if (screen != Screen.Home) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(AccentPrimary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "AG",
                        color = BgDark,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "AirGap",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "OFFLINE OPTICAL SYNC",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextTertiary,
                        letterSpacing = 1.2.sp
                    )
                }
            }
        },
        actions = {
            Surface(
                color = SurfaceVariantDark,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(AccentSuccess, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "OFFLINE",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
    )
}

@Composable
fun HomeScreen(viewModel: AirGapViewModel) {
    val context = LocalContext.current
    var isEncryptionEnabled by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var isPassphraseVisible by remember { mutableStateOf(false) }
    var selectedChunkSize by remember { mutableIntStateOf(TransferProtocol.DEFAULT_CHUNK_BYTES) }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (isEncryptionEnabled && passphrase.length < 8) {
                viewModel.showMessage("Passphrase must be at least 8 characters.", isError = true)
                return@rememberLauncherForActivityResult
            }
            viewModel.prepareFileForTransmission(
                context = context,
                uri = uri,
                passphrase = passphrase,
                isEncrypted = isEncryptionEnabled,
                chunkBytes = selectedChunkSize
            )
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.resetReceiver()
            viewModel.navigateTo(Screen.Receive)
        } else {
            viewModel.showMessage("Camera permission is required to scan QR frames.", isError = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Hero headline
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Offline Transfer",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                "Transfer files using sequential animated QR codes. No internet, Wi-Fi, Bluetooth, or local network required.",
                fontSize = 14.sp,
                color = TextSecondary,
                lineHeight = 20.sp
            )
        }

        // Action Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Send Button Card
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                color = SurfaceDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(AccentPrimary.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = "Send",
                            tint = AccentPrimary
                        )
                    }
                    Column {
                        Text(
                            "Send File",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            "Display animated QR",
                            fontSize = 12.sp,
                            color = TextTertiary
                        )
                    }
                }
            }

            // Receive Button Card
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            viewModel.resetReceiver()
                            viewModel.navigateTo(Screen.Receive)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                color = SurfaceDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(AccentSecondary.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Receive",
                            tint = AccentSecondary
                        )
                    }
                    Column {
                        Text(
                            "Receive",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            "Scan with camera",
                            fontSize = 12.sp,
                            color = TextTertiary
                        )
                    }
                }
            }
        }

        // Encryption Configuration Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Security",
                            tint = if (isEncryptionEnabled) AccentPrimary else TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                "Authenticated Encryption",
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                            Text(
                                if (isEncryptionEnabled) "AES-256-GCM + PBKDF2 active" else "Plaintext with SHA-256 integrity",
                                color = TextTertiary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Switch(
                        checked = isEncryptionEnabled,
                        onCheckedChange = { isEncryptionEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BgDark,
                            checkedTrackColor = AccentPrimary,
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = SurfaceVariantDark
                        )
                    )
                }

                if (isEncryptionEnabled) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Shared Passphrase (8+ characters)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (isPassphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isPassphraseVisible = !isPassphraseVisible }) {
                                Icon(
                                    imageVector = if (isPassphraseVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle Passphrase Visibility",
                                    tint = TextSecondary
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = BorderSubtle,
                            focusedLabelColor = AccentPrimary,
                            unfocusedLabelColor = TextTertiary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        // Density / Chunk Size setting card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "QR Density",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "QR Code Density",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                }
                Text(
                    "Select chunk size for your display and camera resolution:",
                    fontSize = 12.sp,
                    color = TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Compact (240B)" to 240,
                        "Standard (360B)" to 360,
                        "Dense (480B)" to 480
                    ).forEach { (label, size) ->
                        FilterChip(
                            selected = (selectedChunkSize == size),
                            onClick = { selectedChunkSize = size },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPrimary.copy(alpha = 0.2f),
                                selectedLabelColor = AccentPrimary
                            )
                        )
                    }
                }
            }
        }

        // Specifications & Privacy Guarantee Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Transfer Architecture",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                listOf(
                    Icons.Default.VerifiedUser to "100% Offline: zero network permissions in Manifest",
                    Icons.Default.Memory to "Up to 10 MiB: memory-safe sequential reassembly",
                    Icons.Default.Loop to "Continuous loop: scan receiver can join anytime",
                    Icons.Default.Fingerprint to "SHA-256 integrity: cryptographically verified output"
                ).forEach { (icon, text) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = AccentPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SendScreen(viewModel: AirGapViewModel) {
    val transmitState by viewModel.transmitState.collectAsState()
    BackHandler {
        viewModel.resetTransmission()
    }

    val currentFrameStr = transmitState.frames.getOrNull(transmitState.currentFrameIndex)
    val qrBitmap = remember(currentFrameStr) {
        currentFrameStr?.let { QrGenerator.generate(it, 800) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    transmitState.fileName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${transmitState.fileSize / 1024} KB • ${if (transmitState.isEncrypted) "Encrypted" else "Plaintext"}",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Surface(
                color = AccentPrimary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "LOOPING",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = AccentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // QR Code display container
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(3.dp, BorderSubtle)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Sequential QR Code Frame",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    CircularProgressIndicator(color = AccentPrimary)
                }
            }
        }

        // Frame index and progress bar
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Frame ${transmitState.currentFrameIndex + 1} of ${transmitState.frames.size}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    "${((transmitState.currentFrameIndex + 1).toFloat() / transmitState.frames.size.coerceAtLeast(1) * 100).toInt()}%",
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            }
            LinearProgressIndicator(
                progress = {
                    if (transmitState.frames.isNotEmpty()) {
                        (transmitState.currentFrameIndex + 1).toFloat() / transmitState.frames.size
                    } else 0f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = AccentPrimary,
                trackColor = SurfaceVariantDark
            )
        }

        // Transmission Controls
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = SurfaceDark,
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.prevFrame() }) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Frame",
                        tint = TextPrimary
                    )
                }
                IconButton(
                    onClick = { viewModel.toggleTransmissionPlay() },
                    modifier = Modifier
                        .size(48.dp)
                        .background(AccentPrimary, CircleShape)
                ) {
                    Icon(
                        imageVector = if (transmitState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = BgDark
                    )
                }
                IconButton(onClick = { viewModel.nextFrame() }) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Frame",
                        tint = TextPrimary
                    )
                }
            }
        }

        // Speed Adjustment
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Speed:", fontSize = 12.sp, color = TextSecondary)
            listOf("Slow (420ms)" to 420L, "Normal (280ms)" to 280L, "Fast (180ms)" to 180L).forEach { (label, speed) ->
                FilterChip(
                    selected = (transmitState.speedMs == speed),
                    onClick = { viewModel.setTransmissionSpeed(speed) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentPrimary.copy(alpha = 0.2f),
                        selectedLabelColor = AccentPrimary
                    )
                )
            }
        }

        Button(
            onClick = { viewModel.resetTransmission() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariantDark, contentColor = TextPrimary),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Stop Transmission")
        }
    }
}

@Composable
fun ReceiveScreen(viewModel: AirGapViewModel) {
    val context = LocalContext.current
    val receiveState by viewModel.receiveState.collectAsState()
    var passphrase by remember { mutableStateOf("") }

    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destinationUri ->
        if (destinationUri != null) {
            viewModel.saveDecodedFile(context, destinationUri) {
                viewModel.resetReceiver()
                viewModel.navigateTo(Screen.Home)
            }
        }
    }

    BackHandler {
        viewModel.resetReceiver()
        viewModel.navigateTo(Screen.Home)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Camera Viewfinder (only active until all frames captured)
        if (!receiveState.isComplete) {
            CameraScannerView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                onQrDetected = { raw ->
                    viewModel.onScannedQrCode(raw)
                }
            )
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(20.dp),
                color = AccentSuccess.copy(alpha = 0.12f),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentSuccess.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Complete",
                        tint = AccentSuccess,
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            "All Frames Captured",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentSuccess
                        )
                        Text(
                            "${receiveState.totalFrames} of ${receiveState.totalFrames} chunks collected",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // Progress indicators
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (receiveState.session.isEmpty()) "Waiting for AirGap QR…" else "${receiveState.receivedCount} / ${receiveState.totalFrames} frames",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                if (receiveState.totalFrames > 0) {
                    Text(
                        "${(receiveState.receivedCount.toFloat() / receiveState.totalFrames * 100).toInt()}%",
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                }
            }
            LinearProgressIndicator(
                progress = {
                    if (receiveState.totalFrames > 0) {
                        receiveState.receivedCount.toFloat() / receiveState.totalFrames
                    } else 0f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (receiveState.isComplete) AccentSuccess else AccentPrimary,
                trackColor = SurfaceVariantDark
            )
        }

        if (receiveState.fileName.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = SurfaceDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        receiveState.fileName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "Session: ${receiveState.session.take(8)}… • ${if (receiveState.isEncrypted) "AES-256-GCM Encrypted" else "Plaintext"}",
                        fontSize = 12.sp,
                        color = TextTertiary
                    )
                }
            }
        }

        // Actions when complete
        if (receiveState.isComplete) {
            if (receiveState.decodedFile == null) {
                if (receiveState.isEncrypted) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Enter Sender's Passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = BorderSubtle
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Button(
                    onClick = {
                        viewModel.completeAndReconstruct(if (receiveState.isEncrypted) passphrase else null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = BgDark),
                    enabled = !receiveState.isDecrypting
                ) {
                    if (receiveState.isDecrypting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = BgDark)
                    } else {
                        Text(if (receiveState.isEncrypted) "Decrypt & Verify SHA-256" else "Reassemble & Verify SHA-256", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // File decoded and verified, show Save button
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = SurfaceDark,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Integrity Verified ✓", color = AccentSuccess, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("SHA-256: ${receiveState.decodedFile!!.hash.take(16)}…", color = TextTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("Size: ${receiveState.decodedFile!!.data.size / 1024} KB", color = TextSecondary, fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = {
                        saveFileLauncher.launch(receiveState.decodedFile!!.name)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentSuccess, contentColor = BgDark)
                ) {
                    Icon(imageVector = Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save to Device", fontWeight = FontWeight.Bold)
                }
            }
        }

        OutlinedButton(
            onClick = {
                viewModel.resetReceiver()
                viewModel.navigateTo(Screen.Home)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
        ) {
            Text("Cancel & Reset")
        }
    }
}
