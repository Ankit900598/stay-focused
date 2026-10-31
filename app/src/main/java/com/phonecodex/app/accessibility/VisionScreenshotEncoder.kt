package com.phonecodex.app.accessibility

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream

/**
 * Downscale a screenshot to a small JPEG. Never log the bytes.
 */
object VisionScreenshotEncoder {

    const val MAX_WIDTH_PX = 480
    const val JPEG_QUALITY = 50

    @RequiresApi(Build.VERSION_CODES.O)
    fun encodeDownscaledJpeg(
        buffer: HardwareBuffer,
        colorSpace: ColorSpace?
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return null
        val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        if (hardware !== software) {
            hardware.recycle()
        }
        val scaled = scaleToMaxWidth(software, MAX_WIDTH_PX)
        if (scaled !== software) {
            software.recycle()
        }
        val bytes = ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
        scaled.recycle()
        if (!ok) return null
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleToMaxWidth(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth || source.width <= 0) return source
        val height = (source.height.toFloat() * maxWidth / source.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, height, true)
    }
}
