package com.airgap.app

import android.graphics.Bitmap
import androidx.collection.LruCache
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrGenerator {
    private val hintsLevelM = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "ISO-8859-1"
    )

    private val hintsLevelL = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "ISO-8859-1"
    )

    // Cache recently generated QR bitmaps to save CPU/battery during rapid transmission loops
    private val cache = LruCache<String, Bitmap>(64)

    fun generate(content: String, size: Int = 800): Bitmap {
        val key = "$size:${content.hashCode()}"
        val cached = cache.get(key)
        if (cached != null && !cached.isRecycled) {
            return cached
        }

        // Use ErrorCorrectionLevel.M (15%) for robust scanning; fallback to L (7%) for ultra-dense payloads > 2100 bytes
        val hints = if (content.length > 2100) hintsLevelL else hintsLevelM
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)

        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) black else white
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565)
        cache.put(key, bitmap)
        return bitmap
    }

    fun clearCache() {
        cache.evictAll()
    }
}
