package com.decloud.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Utility class to generate QR codes for connection info
 */
object QRCodeGenerator {

    /**
     * Generate a QR code bitmap containing the server URL
     *
     * @param ipAddress The server IP address
     * @param port The server port
     * @param size The QR code size in pixels (default 256)
     * @return Bitmap containing the QR code, or null if generation fails
     */
    fun generateConnectionQR(ipAddress: String, port: Int, size: Int = 256): Bitmap? {
        return try {
            val url = "http://$ipAddress:$port"
            generateQRCode(url, size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generate a QR code bitmap from any string content
     *
     * @param content The content to encode
     * @param size The QR code size in pixels
     * @return Bitmap containing the QR code, or null if generation fails
     */
    fun generateQRCode(content: String, size: Int = 256): Bitmap? {
        return try {
            val hints = hashMapOf<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1  // Minimal margin
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                }
            }

            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generate a QR code with custom colors
     *
     * @param content The content to encode
     * @param size The QR code size in pixels
     * @param foregroundColor The color for QR code modules (default black)
     * @param backgroundColor The background color (default white)
     * @return Bitmap containing the QR code, or null if generation fails
     */
    fun generateQRCodeWithColors(
        content: String,
        size: Int = 256,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        return try {
            val hints = hashMapOf<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (bitMatrix[x, y]) foregroundColor else backgroundColor
                }
            }

            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            null
        }
    }
}
