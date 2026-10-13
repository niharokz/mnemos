package com.nihars.mnemos.feature.editor

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.nihars.mnemos.core.data.Attachments
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Reads pictures from the photo picker or the camera, ready for [Attachments.saveImage]. */
internal object ImageImport {
    // Bigger than any phone photo; stops a 200 MB panorama from filling the vault by surprise.
    private const val MAX_BYTES = 25L * 1024 * 1024
    private const val CAMERA_DIR = "camera"

    /** Name and bytes of a picked picture. HEIC and other formats a browser can't show become JPEG. */
    fun read(resolver: ContentResolver, uri: Uri): Pair<String, ByteArray> {
        var name = "image"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_BYTES) throw IOException("“$name” is bigger than 25 MB")
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("can't read “$name”")
        if (bytes.size > MAX_BYTES) throw IOException("“$name” is bigger than 25 MB")
        if (Attachments.imageExt(bytes) != null) return name to bytes
        return name.substringBeforeLast('.') + ".jpg" to toJpeg(bytes)
    }

    // ImageDecoder applies the EXIF rotation, so the JPEG is upright without it.
    private fun toJpeg(bytes: ByteArray): ByteArray {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: ImageDecoder.DecodeException) {
            throw IOException("not a picture Mnemos can read", e)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** A fresh file the camera app writes into (app cache, shared through the FileProvider). */
    fun cameraTarget(context: Context): Uri {
        val dir = File(context.cacheDir, CAMERA_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the photo being taken is ever kept here
        val name = "photo-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jpg"
        return FileProvider.getUriForFile(context, context.packageName + ".files", File(dir, name))
    }

    private const val JPEG_QUALITY = 90
}
