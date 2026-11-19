package org.darkcamera.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves photos and videos to DCIM/DarkCamera through MediaStore (no storage permission needed). */
object Store {
    private const val DIR = "DCIM/DarkCamera"

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun insert(ctx: Context, name: String, mime: String, video: Boolean): Uri? {
        val col = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(col, v)
    }

    fun newVideo(ctx: Context, name: String): Pair<Uri, ParcelFileDescriptor> {
        val uri = insert(ctx, name, "video/mp4", true) ?: throw IOException("MediaStore insert failed")
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "w") ?: throw IOException("Cannot open file")
        return uri to pfd
    }

    fun finish(ctx: Context, uri: Uri, ok: Boolean) {
        try {
            if (ok) {
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } else {
                ctx.contentResolver.delete(uri, null, null)
            }
        } catch (_: Exception) {
        }
    }

    fun saveBytes(ctx: Context, name: String, mime: String, bytes: ByteArray): Boolean =
        saveStream(ctx, name, mime) { it.write(bytes) }

    fun saveStream(ctx: Context, name: String, mime: String, writer: (OutputStream) -> Unit): Boolean {
        val uri = try { insert(ctx, name, mime, false) } catch (e: Exception) { null } ?: return false
        return try {
            (ctx.contentResolver.openOutputStream(uri) ?: throw IOException("no stream")).use(writer)
            finish(ctx, uri, true)
            true
        } catch (e: Exception) {
            finish(ctx, uri, false)
            false
        }
    }

    fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        0L
    }
}
