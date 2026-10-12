package com.airgap.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airgap.app.data.HistoryRepository
import com.airgap.app.data.ReceivedFileItem
import com.airgap.app.data.ReceivedFilesManager
import com.airgap.app.model.TransferDirection
import com.airgap.app.model.TransferRecord
import com.airgap.app.model.TransferStatus
import com.airgap.app.ui.theme.AccentColorChoice
import com.airgap.app.ui.theme.AppThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed class Screen {
    data object Home : Screen()
    data object Send : Screen()
    data object Receive : Screen()
    data object History : Screen()
    data object Files : Screen()
    data object Settings : Screen()
}

data class AppSettings(
    val themeMode: AppThemeMode = AppThemeMode.DEFAULT,
    val accentChoice: AccentColorChoice = AccentColorChoice.LIME,
    val defaultChunkBytes: Int = TransferProtocol.DEFAULT_CHUNK_BYTES,
    val defaultSpeedMs: Long = 280L,
    val defaultEncrypt: Boolean = false,
    val keepScreenOnActive: Boolean = true,
    val loopContinuous: Boolean = true,
    val hapticFeedback: Boolean = true,
    val hideFileNamesInHistory: Boolean = false,
    val autoCleanupDays: Int = 30
)

data class TransmitState(
    val fileName: String = "",
    val fileSize: Long = 0L,
    val mimeType: String = "",
    val isEncrypted: Boolean = false,
    val hash: String = "",
    val frames: List<String> = emptyList(),
    val currentFrameIndex: Int = 0,
    val isPlaying: Boolean = true,
    val speedMs: Long = 280L,
    val isPreparing: Boolean = false
)

data class ReceiveState(
    val session: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val totalFrames: Int = 0,
    val receivedCount: Int = 0,
    val isEncrypted: Boolean = false,
    val hash: String = "",
    val isComplete: Boolean = false,
    val isDecrypting: Boolean = false,
    val isPaused: Boolean = false,
    val decodedFile: TransferProtocol.DecodedFile? = null
)

class AirGapViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("airgap_prefs", Context.MODE_PRIVATE)
    val historyRepo = HistoryRepository(application)
    val filesManager = ReceivedFilesManager(application)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _transmitState = MutableStateFlow(TransmitState())
    val transmitState: StateFlow<TransmitState> = _transmitState.asStateFlow()

    private val _receiveState = MutableStateFlow(ReceiveState())
    val receiveState: StateFlow<ReceiveState> = _receiveState.asStateFlow()

    private val _historyRecords = MutableStateFlow<List<TransferRecord>>(emptyList())
    val historyRecords: StateFlow<List<TransferRecord>> = _historyRecords.asStateFlow()

    private val _receivedFiles = MutableStateFlow<List<ReceivedFileItem>>(emptyList())
    val receivedFiles: StateFlow<List<ReceivedFileItem>> = _receivedFiles.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isErrorMessage = MutableStateFlow(false)
    val isErrorMessage: StateFlow<Boolean> = _isErrorMessage.asStateFlow()

    private val receivedFramesMap = mutableMapOf<Int, TransferProtocol.Frame>()
    private var transmitLoopJob: Job? = null

    init {
        loadHistory()
        loadReceivedFiles()
        viewModelScope.launch {
            if (_settings.value.autoCleanupDays > 0) {
                historyRepo.autoCleanupHistory(_settings.value.autoCleanupDays)
            }
        }
    }

    private fun loadSettings(): AppSettings {
        val themeName = prefs.getString("theme_mode", AppThemeMode.DEFAULT.name) ?: AppThemeMode.DEFAULT.name
        val theme = runCatching { AppThemeMode.valueOf(themeName) }.getOrDefault(AppThemeMode.DEFAULT)
        val accentName = prefs.getString("accent_choice", AccentColorChoice.LIME.name) ?: AccentColorChoice.LIME.name
        val accent = runCatching { AccentColorChoice.valueOf(accentName) }.getOrDefault(AccentColorChoice.LIME)
        val chunk = prefs.getInt("default_chunk", TransferProtocol.DEFAULT_CHUNK_BYTES)
        val speed = prefs.getLong("default_speed", 280L)
        val enc = prefs.getBoolean("default_encrypt", false)
        val keepAwake = prefs.getBoolean("keep_awake", true)
        val loop = prefs.getBoolean("loop_continuous", true)
        val haptic = prefs.getBoolean("haptic_feedback", true)
        val hideNames = prefs.getBoolean("hide_filenames", false)
        val cleanupDays = prefs.getInt("cleanup_days", 30)

        return AppSettings(
            themeMode = theme,
            accentChoice = accent,
            defaultChunkBytes = chunk,
            defaultSpeedMs = speed,
            defaultEncrypt = enc,
            keepScreenOnActive = keepAwake,
            loopContinuous = loop,
            hapticFeedback = haptic,
            hideFileNamesInHistory = hideNames,
            autoCleanupDays = cleanupDays
        )
    }

    fun updateTheme(theme: AppThemeMode) {
        _settings.value = _settings.value.copy(themeMode = theme)
        prefs.edit().putString("theme_mode", theme.name).apply()
    }

    fun updateAccent(accent: AccentColorChoice) {
        _settings.value = _settings.value.copy(accentChoice = accent)
        prefs.edit().putString("accent_choice", accent.name).apply()
    }

    fun updateDefaultChunkBytes(bytes: Int) {
        val safe = bytes.coerceIn(TransferProtocol.MIN_CHUNK_BYTES, TransferProtocol.MAX_CHUNK_BYTES)
        _settings.value = _settings.value.copy(defaultChunkBytes = safe)
        prefs.edit().putInt("default_chunk", safe).apply()
    }

    fun updateDefaultSpeed(speedMs: Long) {
        _settings.value = _settings.value.copy(defaultSpeedMs = speedMs)
        prefs.edit().putLong("default_speed", speedMs).apply()
    }

    fun updateDefaultEncrypt(encrypt: Boolean) {
        _settings.value = _settings.value.copy(defaultEncrypt = encrypt)
        prefs.edit().putBoolean("default_encrypt", encrypt).apply()
    }

    fun updateKeepAwake(keepAwake: Boolean) {
        _settings.value = _settings.value.copy(keepScreenOnActive = keepAwake)
        prefs.edit().putBoolean("keep_awake", keepAwake).apply()
    }

    fun updateLoopContinuous(loop: Boolean) {
        _settings.value = _settings.value.copy(loopContinuous = loop)
        prefs.edit().putBoolean("loop_continuous", loop).apply()
    }

    fun updateHapticFeedback(haptic: Boolean) {
        _settings.value = _settings.value.copy(hapticFeedback = haptic)
        prefs.edit().putBoolean("haptic_feedback", haptic).apply()
    }

    fun updateHideFileNames(hide: Boolean) {
        _settings.value = _settings.value.copy(hideFileNamesInHistory = hide)
        prefs.edit().putBoolean("hide_filenames", hide).apply()
    }

    fun updateAutoCleanupDays(days: Int) {
        _settings.value = _settings.value.copy(autoCleanupDays = days)
        prefs.edit().putInt("cleanup_days", days).apply()
    }

    fun resetSettingsToDefault() {
        prefs.edit().clear().apply()
        _settings.value = AppSettings()
        showMessage("Settings restored to factory defaults.")
    }

    fun navigateTo(target: Screen) {
        _screen.value = target
        if (target != Screen.Send) {
            stopTransmissionLoop()
        }
        if (target == Screen.History) {
            loadHistory()
        }
        if (target == Screen.Files) {
            loadReceivedFiles()
        }
    }

    fun showMessage(msg: String, isError: Boolean = false) {
        _statusMessage.value = msg
        _isErrorMessage.value = isError
    }

    fun clearMessage() {
        _statusMessage.value = null
    }

    fun loadHistory() {
        viewModelScope.launch {
            _historyRecords.value = historyRepo.getAllRecords()
        }
    }

    fun deleteHistoryRecord(id: String) {
        viewModelScope.launch {
            historyRepo.deleteRecord(id)
            loadHistory()
            showMessage("Record deleted.")
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepo.clearHistory()
            loadHistory()
            showMessage("History cleared.")
        }
    }

    fun loadReceivedFiles() {
        viewModelScope.launch {
            _receivedFiles.value = filesManager.getReceivedFiles()
        }
    }

    fun deleteReceivedFile(file: File) {
        viewModelScope.launch {
            val ok = filesManager.deleteFile(file)
            if (ok) {
                loadReceivedFiles()
                showMessage("File deleted: ${file.name}")
            } else {
                showMessage("Failed to delete file.", isError = true)
            }
        }
    }

    fun clearAllReceivedFiles() {
        viewModelScope.launch {
            val count = filesManager.clearAllFiles()
            loadReceivedFiles()
            showMessage("Deleted $count received file(s).")
        }
    }

    fun triggerHapticFeedback() {
        if (!_settings.value.hapticFeedback) return
        try {
            val ctx = getApplication<Application>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(25)
            }
        } catch (_: Exception) {}
    }

    fun prepareFileForTransmission(
        context: Context,
        uri: Uri,
        passphrase: String,
        isEncrypted: Boolean,
        chunkBytes: Int
    ) {
        viewModelScope.launch {
            _transmitState.value = _transmitState.value.copy(isPreparing = true)
            try {
                var detectedName = "shared-file.bin"
                var detectedSize = 0L

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) detectedName = cursor.getString(nameIdx) ?: detectedName
                        if (sizeIdx >= 0) detectedSize = cursor.getLong(sizeIdx)
                    }
                }

                val detectedMime = context.contentResolver.getType(uri) ?: "application/octet-stream"

                // Stream directly to prevent holding entire large file in memory multiple times
                val passChars = if (isEncrypted) passphrase.toCharArray() else null
                val prepared = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Could not read file from storage.")

                    val realSize = if (detectedSize > 0) detectedSize else stream.available().toLong().coerceAtLeast(1L)
                    if (realSize > TransferProtocol.MAX_FILE_BYTES) {
                        stream.close()
                        throw IllegalArgumentException("File size (${realSize / (1024 * 1024)} MiB) exceeds the 50 MiB limit.")
                    }

                    stream.use { s ->
                        TransferProtocol.prepareFromStream(
                            stream = s,
                            totalSize = realSize,
                            fileName = detectedName,
                            mime = detectedMime,
                            passphrase = passChars,
                            chunkBytes = chunkBytes
                        )
                    }
                }

                _transmitState.value = TransmitState(
                    fileName = prepared.fileName,
                    fileSize = prepared.size,
                    mimeType = detectedMime,
                    isEncrypted = prepared.isEncrypted,
                    hash = prepared.hash,
                    frames = prepared.frames,
                    currentFrameIndex = 0,
                    isPlaying = true,
                    speedMs = _settings.value.defaultSpeedMs,
                    isPreparing = false
                )

                // Log to history as completed transmission
                historyRepo.insertRecord(
                    TransferRecord(
                        id = UUID.randomUUID().toString(),
                        fileName = prepared.fileName,
                        fileSize = prepared.size,
                        mimeType = detectedMime,
                        timestamp = System.currentTimeMillis(),
                        direction = TransferDirection.SENT,
                        status = TransferStatus.COMPLETED,
                        sha256Hash = prepared.hash,
                        isEncrypted = prepared.isEncrypted,
                        totalFrames = prepared.frames.size,
                        framesTransferred = prepared.frames.size
                    )
                )

                _screen.value = Screen.Send
                startTransmissionLoop()
            } catch (e: Exception) {
                _transmitState.value = _transmitState.value.copy(isPreparing = false)
                showMessage(e.message ?: "Failed to process file.", isError = true)
            }
        }
    }

    fun setTransmissionSpeed(speedMs: Long) {
        _transmitState.value = _transmitState.value.copy(speedMs = speedMs)
    }

    fun toggleTransmissionPlay() {
        val next = !_transmitState.value.isPlaying
        _transmitState.value = _transmitState.value.copy(isPlaying = next)
        triggerHapticFeedback()
    }

    fun nextFrame() {
        val frames = _transmitState.value.frames
        if (frames.isNotEmpty()) {
            val nextIdx = (_transmitState.value.currentFrameIndex + 1) % frames.size
            _transmitState.value = _transmitState.value.copy(currentFrameIndex = nextIdx)
            triggerHapticFeedback()
        }
    }

    fun prevFrame() {
        val frames = _transmitState.value.frames
        if (frames.isNotEmpty()) {
            val prevIdx = if (_transmitState.value.currentFrameIndex > 0) {
                _transmitState.value.currentFrameIndex - 1
            } else {
                frames.size - 1
            }
            _transmitState.value = _transmitState.value.copy(currentFrameIndex = prevIdx)
            triggerHapticFeedback()
        }
    }

    private fun startTransmissionLoop() {
        transmitLoopJob?.cancel()
        transmitLoopJob = viewModelScope.launch {
            while (true) {
                val state = _transmitState.value
                val delayTime = state.speedMs
                delay(delayTime)
                if (_transmitState.value.isPlaying && _transmitState.value.frames.isNotEmpty()) {
                    val count = _transmitState.value.frames.size
                    val currentIdx = _transmitState.value.currentFrameIndex
                    if (!_settings.value.loopContinuous && currentIdx == count - 1) {
                        _transmitState.value = _transmitState.value.copy(isPlaying = false)
                    } else {
                        val nextIdx = (currentIdx + 1) % count
                        _transmitState.value = _transmitState.value.copy(currentFrameIndex = nextIdx)
                    }
                }
            }
        }
    }

    private fun stopTransmissionLoop() {
        transmitLoopJob?.cancel()
        transmitLoopJob = null
    }

    fun resetTransmission() {
        stopTransmissionLoop()
        QrGenerator.clearCache()
        _transmitState.value = TransmitState()
        _screen.value = Screen.Home
    }

    fun resetReceiver() {
        synchronized(receivedFramesMap) {
            receivedFramesMap.clear()
        }
        _receiveState.value = ReceiveState()
    }

    fun togglePauseScanning() {
        val paused = !_receiveState.value.isPaused
        _receiveState.value = _receiveState.value.copy(isPaused = paused)
        triggerHapticFeedback()
        showMessage(if (paused) "Scanning paused." else "Scanning resumed.")
    }

    fun onScannedQrCode(rawBytes: ByteArray, rawString: String? = null) {
        if (_receiveState.value.isPaused || _receiveState.value.isComplete) return
        try {
            val frame = TransferProtocol.parse(rawBytes)
            synchronized(receivedFramesMap) {
                val current = _receiveState.value

                if (current.session.isNotEmpty() && current.session != frame.session) {
                    return
                }

                if (current.session.isEmpty()) {
                    _receiveState.value = ReceiveState(
                        session = frame.session,
                        fileName = frame.name,
                        mimeType = frame.mime,
                        totalFrames = frame.total,
                        receivedCount = 1,
                        isEncrypted = frame.encrypted,
                        hash = frame.hash,
                        isComplete = (frame.total == 1),
                        decodedFile = null
                    )
                    receivedFramesMap[frame.index] = frame
                    triggerHapticFeedback()
                } else {
                    if (!receivedFramesMap.containsKey(frame.index)) {
                        receivedFramesMap[frame.index] = frame
                        val count = receivedFramesMap.size
                        val complete = count == current.totalFrames
                        _receiveState.value = current.copy(
                            receivedCount = count,
                            isComplete = complete
                        )
                        triggerHapticFeedback()
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore non-AirGap QR codes
        }
    }

    fun onScannedQrCode(raw: String) {
        onScannedQrCode(raw.toByteArray(Charsets.ISO_8859_1), raw)
    }

    fun completeAndReconstruct(passphrase: String?) {
        viewModelScope.launch {
            _receiveState.value = _receiveState.value.copy(isDecrypting = true)
            try {
                val framesCopy = synchronized(receivedFramesMap) {
                    receivedFramesMap.values.toList()
                }

                val passChars = if (!passphrase.isNullOrEmpty()) passphrase.toCharArray() else null
                val result = withContext(Dispatchers.Default) {
                    TransferProtocol.reconstruct(framesCopy, passChars)
                }

                _receiveState.value = _receiveState.value.copy(
                    isDecrypting = false,
                    decodedFile = result
                )

                // Save automatically to internal received files library
                val savedFile = filesManager.saveReceivedFile(result.name, result.data)
                loadReceivedFiles()

                // Insert into History database as VERIFIED
                historyRepo.insertRecord(
                    TransferRecord(
                        id = UUID.randomUUID().toString(),
                        fileName = result.name,
                        fileSize = result.data.size.toLong(),
                        mimeType = result.mime,
                        timestamp = System.currentTimeMillis(),
                        direction = TransferDirection.RECEIVED,
                        status = TransferStatus.VERIFIED,
                        sha256Hash = result.hash,
                        isEncrypted = result.isEncrypted,
                        totalFrames = framesCopy.size,
                        framesTransferred = framesCopy.size
                    )
                )

                triggerHapticFeedback()
                showMessage("Reconstruction verified and saved to Files library (SHA-256 match).", isError = false)
            } catch (e: Exception) {
                _receiveState.value = _receiveState.value.copy(isDecrypting = false)
                // Log failed attempt to history
                val curr = _receiveState.value
                if (curr.session.isNotEmpty()) {
                    historyRepo.insertRecord(
                        TransferRecord(
                            id = UUID.randomUUID().toString(),
                            fileName = curr.fileName.ifBlank { "unknown.bin" },
                            fileSize = 0L,
                            mimeType = curr.mimeType,
                            timestamp = System.currentTimeMillis(),
                            direction = TransferDirection.RECEIVED,
                            status = TransferStatus.FAILED,
                            sha256Hash = curr.hash,
                            isEncrypted = curr.isEncrypted,
                            totalFrames = curr.totalFrames,
                            framesTransferred = curr.receivedCount,
                            errorMessage = e.message
                        )
                    )
                }
                showMessage(e.message ?: "Failed to reconstruct file.", isError = true)
            }
        }
    }

    fun saveDecodedFileToExternal(context: Context, destinationUri: Uri, onComplete: () -> Unit) {
        viewModelScope.launch {
            val file = _receiveState.value.decodedFile
            if (file == null) {
                showMessage("No file ready to export.", isError = true)
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        out.write(file.data)
                        out.flush()
                    } ?: throw IllegalStateException("Could not open destination storage.")
                }
                showMessage("File exported: ${file.name}", isError = false)
                onComplete()
            } catch (e: Exception) {
                showMessage("Failed to export: ${e.message}", isError = true)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTransmissionLoop()
        QrGenerator.clearCache()
    }
}
