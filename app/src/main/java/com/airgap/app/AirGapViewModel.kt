package com.airgap.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airgap.app.ui.theme.AppThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    data object Home : Screen()
    data object Send : Screen()
    data object Receive : Screen()
    data object Settings : Screen()
}

data class AppSettings(
    val themeMode: AppThemeMode = AppThemeMode.DEFAULT,
    val defaultChunkBytes: Int = TransferProtocol.DEFAULT_CHUNK_BYTES,
    val defaultSpeedMs: Long = 280L,
    val defaultEncrypt: Boolean = false,
    val keepScreenOnActive: Boolean = true,
    val loopContinuous: Boolean = true,
    val errorCorrectionMargin: Int = 1
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
    val decodedFile: TransferProtocol.DecodedFile? = null
)

class AirGapViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("airgap_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _transmitState = MutableStateFlow(TransmitState())
    val transmitState: StateFlow<TransmitState> = _transmitState.asStateFlow()

    private val _receiveState = MutableStateFlow(ReceiveState())
    val receiveState: StateFlow<ReceiveState> = _receiveState.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isErrorMessage = MutableStateFlow(false)
    val isErrorMessage: StateFlow<Boolean> = _isErrorMessage.asStateFlow()

    private val receivedFramesMap = mutableMapOf<Int, TransferProtocol.Frame>()

    private var transmitLoopJob: Job? = null

    private fun loadSettings(): AppSettings {
        val themeName = prefs.getString("theme_mode", AppThemeMode.DEFAULT.name) ?: AppThemeMode.DEFAULT.name
        val theme = runCatching { AppThemeMode.valueOf(themeName) }.getOrDefault(AppThemeMode.DEFAULT)
        val chunk = prefs.getInt("default_chunk", TransferProtocol.DEFAULT_CHUNK_BYTES)
        val speed = prefs.getLong("default_speed", 280L)
        val enc = prefs.getBoolean("default_encrypt", false)
        val keepAwake = prefs.getBoolean("keep_awake", true)
        val loop = prefs.getBoolean("loop_continuous", true)
        val margin = prefs.getInt("qr_margin", 1)

        return AppSettings(
            themeMode = theme,
            defaultChunkBytes = chunk,
            defaultSpeedMs = speed,
            defaultEncrypt = enc,
            keepScreenOnActive = keepAwake,
            loopContinuous = loop,
            errorCorrectionMargin = margin
        )
    }

    fun updateTheme(theme: AppThemeMode) {
        _settings.value = _settings.value.copy(themeMode = theme)
        prefs.edit().putString("theme_mode", theme.name).apply()
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
    }

    fun showMessage(msg: String, isError: Boolean = false) {
        _statusMessage.value = msg
        _isErrorMessage.value = isError
    }

    fun clearMessage() {
        _statusMessage.value = null
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
                val (name, size, mime, bytes) = withContext(Dispatchers.IO) {
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
                    val fileBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Could not read file from storage.")

                    if (fileBytes.size > TransferProtocol.MAX_FILE_BYTES) {
                        throw IllegalArgumentException("File size (${fileBytes.size / (1024 * 1024)} MiB) exceeds the 10 MiB limit.")
                    }

                    Quad(detectedName, if (detectedSize > 0) detectedSize else fileBytes.size.toLong(), detectedMime, fileBytes)
                }

                val passChars = if (isEncrypted) passphrase.toCharArray() else null
                val prepared = withContext(Dispatchers.Default) {
                    TransferProtocol.prepare(
                        data = bytes,
                        fileName = name,
                        mime = mime,
                        passphrase = passChars,
                        chunkBytes = chunkBytes
                    )
                }

                _transmitState.value = TransmitState(
                    fileName = prepared.fileName,
                    fileSize = size,
                    mimeType = mime,
                    isEncrypted = prepared.isEncrypted,
                    hash = prepared.hash,
                    frames = prepared.frames,
                    currentFrameIndex = 0,
                    isPlaying = true,
                    speedMs = _settings.value.defaultSpeedMs,
                    isPreparing = false
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
    }

    fun nextFrame() {
        val frames = _transmitState.value.frames
        if (frames.isNotEmpty()) {
            val nextIdx = (_transmitState.value.currentFrameIndex + 1) % frames.size
            _transmitState.value = _transmitState.value.copy(currentFrameIndex = nextIdx)
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

    fun onScannedQrCode(raw: String) {
        try {
            val frame = TransferProtocol.parse(raw)
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
                } else {
                    if (!receivedFramesMap.containsKey(frame.index)) {
                        receivedFramesMap[frame.index] = frame
                        val count = receivedFramesMap.size
                        val complete = count == current.totalFrames
                        _receiveState.value = current.copy(
                            receivedCount = count,
                            isComplete = complete
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore QR codes not matching AirGap protocol
        }
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
                showMessage("Reconstruction verified successfully (SHA-256 match).", isError = false)
            } catch (e: Exception) {
                _receiveState.value = _receiveState.value.copy(isDecrypting = false)
                showMessage(e.message ?: "Failed to reconstruct file.", isError = true)
            }
        }
    }

    fun saveDecodedFile(context: Context, destinationUri: Uri, onComplete: () -> Unit) {
        viewModelScope.launch {
            val file = _receiveState.value.decodedFile
            if (file == null) {
                showMessage("No file ready to save.", isError = true)
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        out.write(file.data)
                        out.flush()
                    } ?: throw IllegalStateException("Could not open destination storage.")
                }
                showMessage("File saved successfully: ${file.name}", isError = false)
                onComplete()
            } catch (e: Exception) {
                showMessage("Failed to save: ${e.message}", isError = true)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTransmissionLoop()
        QrGenerator.clearCache()
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
