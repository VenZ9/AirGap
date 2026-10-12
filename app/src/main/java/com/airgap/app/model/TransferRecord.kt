package com.airgap.app.model

enum class TransferDirection {
    SENT,
    RECEIVED
}

enum class TransferStatus {
    COMPLETED,
    VERIFIED,
    FAILED,
    INCOMPLETE
}

data class TransferRecord(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val timestamp: Long,
    val direction: TransferDirection,
    val status: TransferStatus,
    val sha256Hash: String,
    val isEncrypted: Boolean,
    val totalFrames: Int,
    val framesTransferred: Int,
    val errorMessage: String? = null
)
