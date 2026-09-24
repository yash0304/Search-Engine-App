package com.sarvam.voiceassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import android.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * Turns whatever the user picked into the PDF Document Intelligence needs.
 *
 * The upload endpoint takes exactly one PDF (or a ZIP). Photos of receipts, notices or
 * letters are the common case on a phone, so an image becomes a one-page PDF here, on the
 * device, rather than being refused.
 */
object DocumentInput {

    /** Big enough to read small print; small enough to upload quickly on mobile data. */
    private const val MAX_IMAGE_EDGE = 2_400

    /** Document Intelligence's own ceiling is higher; this keeps uploads practical. */
    private const val MAX_BYTES = 25L * 1024 * 1024

    data class Prepared(val name: String, val pdf: ByteArray)

    fun prepare(context: Context, uri: Uri): Prepared {
        val resolver = context.contentResolver
        val name = displayName(context, uri) ?: "document"
        val type = resolver.getType(uri).orEmpty()

        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw SarvamException("That file could not be opened.")
        if (bytes.size > MAX_BYTES) throw SarvamException("That file is over 25 MB. Try a smaller one.")

        return when {
            type == "application/pdf" || bytes.startsWithPdfMagic() -> Prepared(name, bytes)
            type.startsWith("image/") -> Prepared(name, imageToPdf(bytes))
            else -> throw SarvamException("Only PDFs and photos can be read.")
        }
    }

    private fun ByteArray.startsWithPdfMagic() =
        size >= 4 && this[0] == '%'.code.toByte() && this[1] == 'P'.code.toByte() &&
            this[2] == 'D'.code.toByte() && this[3] == 'F'.code.toByte()

    private fun imageToPdf(bytes: ByteArray): ByteArray {
        val bitmap = decodeScaled(bytes) ?: throw SarvamException("That image could not be read.")
        val upright = rotateUpright(bitmap, bytes)

        val document = PdfDocument()
        try {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(upright.width, upright.height, 1).create(),
            )
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawBitmap(upright, 0f, 0f, null)
            document.finishPage(page)

            return ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
        } finally {
            document.close()
            upright.recycle()
            if (upright !== bitmap) bitmap.recycle()
        }
    }

    /** Decodes at a reduced size up front, so a 50-megapixel photo cannot exhaust memory. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Phone cameras store portrait shots sideways plus an EXIF note; OCR needs them upright. */
    private fun rotateUpright(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
