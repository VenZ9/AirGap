package com.airgap.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.airgap.app.model.TransferDirection
import com.airgap.app.model.TransferRecord
import com.airgap.app.model.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HistoryDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "airgap_history.db"
        const val DATABASE_VERSION = 1

        const val TABLE_HISTORY = "transfer_history"
        const val COL_ID = "id"
        const val COL_FILE_NAME = "file_name"
        const val COL_FILE_SIZE = "file_size"
        const val COL_MIME_TYPE = "mime_type"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_DIRECTION = "direction"
        const val COL_STATUS = "status"
        const val COL_SHA256 = "sha256_hash"
        const val COL_ENCRYPTED = "is_encrypted"
        const val COL_TOTAL_FRAMES = "total_frames"
        const val COL_FRAMES_TRANSFERRED = "frames_transferred"
        const val COL_ERROR = "error_message"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                $COL_ID TEXT PRIMARY KEY,
                $COL_FILE_NAME TEXT NOT NULL,
                $COL_FILE_SIZE INTEGER NOT NULL,
                $COL_MIME_TYPE TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_DIRECTION TEXT NOT NULL,
                $COL_STATUS TEXT NOT NULL,
                $COL_SHA256 TEXT NOT NULL,
                $COL_ENCRYPTED INTEGER NOT NULL,
                $COL_TOTAL_FRAMES INTEGER NOT NULL,
                $COL_FRAMES_TRANSFERRED INTEGER NOT NULL,
                $COL_ERROR TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        onCreate(db)
    }
}

class HistoryRepository(context: Context) {
    private val dbHelper = HistoryDatabaseHelper(context)

    suspend fun insertRecord(record: TransferRecord) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(HistoryDatabaseHelper.COL_ID, record.id)
            put(HistoryDatabaseHelper.COL_FILE_NAME, record.fileName)
            put(HistoryDatabaseHelper.COL_FILE_SIZE, record.fileSize)
            put(HistoryDatabaseHelper.COL_MIME_TYPE, record.mimeType)
            put(HistoryDatabaseHelper.COL_TIMESTAMP, record.timestamp)
            put(HistoryDatabaseHelper.COL_DIRECTION, record.direction.name)
            put(HistoryDatabaseHelper.COL_STATUS, record.status.name)
            put(HistoryDatabaseHelper.COL_SHA256, record.sha256Hash)
            put(HistoryDatabaseHelper.COL_ENCRYPTED, if (record.isEncrypted) 1 else 0)
            put(HistoryDatabaseHelper.COL_TOTAL_FRAMES, record.totalFrames)
            put(HistoryDatabaseHelper.COL_FRAMES_TRANSFERRED, record.framesTransferred)
            put(HistoryDatabaseHelper.COL_ERROR, record.errorMessage)
        }
        db.insertWithOnConflict(HistoryDatabaseHelper.TABLE_HISTORY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun getAllRecords(): List<TransferRecord> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<TransferRecord>()
        val cursor = db.query(
            HistoryDatabaseHelper.TABLE_HISTORY,
            null,
            null,
            null,
            null,
            null,
            "${HistoryDatabaseHelper.COL_TIMESTAMP} DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                val record = TransferRecord(
                    id = c.getString(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_ID)),
                    fileName = c.getString(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_FILE_NAME)),
                    fileSize = c.getLong(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_FILE_SIZE)),
                    mimeType = c.getString(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_MIME_TYPE)),
                    timestamp = c.getLong(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_TIMESTAMP)),
                    direction = TransferDirection.valueOf(c.getString(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_DIRECTION))),
                    status = TransferStatus.valueOf(c.getString(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_STATUS))),
                    sha256Hash = c.getString(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_SHA256)),
                    isEncrypted = c.getInt(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_ENCRYPTED)) == 1,
                    totalFrames = c.getInt(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_TOTAL_FRAMES)),
                    framesTransferred = c.getInt(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_FRAMES_TRANSFERRED)),
                    errorMessage = c.getString(c.getColumnIndexOrThrow(HistoryDatabaseHelper.COL_ERROR))
                )
                list.add(record)
            }
        }
        list
    }

    suspend fun deleteRecord(id: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(HistoryDatabaseHelper.TABLE_HISTORY, "${HistoryDatabaseHelper.COL_ID} = ?", arrayOf(id))
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete(HistoryDatabaseHelper.TABLE_HISTORY, null, null)
    }

    suspend fun autoCleanupHistory(daysOld: Int) = withContext(Dispatchers.IO) {
        if (daysOld <= 0) return@withContext
        val cutoff = System.currentTimeMillis() - (daysOld.toLong() * 24 * 60 * 60 * 1000)
        val db = dbHelper.writableDatabase
        db.delete(HistoryDatabaseHelper.TABLE_HISTORY, "${HistoryDatabaseHelper.COL_TIMESTAMP} < ?", arrayOf(cutoff.toString()))
    }
}
