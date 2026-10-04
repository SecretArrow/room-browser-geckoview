package com.roombrowser.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** QR code generation (share URL / text as a QR code). */
object QrCodeGenerator {

    fun generate(content: String, size: Int = 720): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size, hints
        )
        val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap[x, y] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return bitmap
    }
}
