package com.maysk.camera.media

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class MediaItem(
    val uri: Uri,
    val id: Long,
    val isVideo: Boolean,
    val dateMs: Long,
    val durationMs: Long,
    val bucket: String,
    val name: String,
)

object MediaStoreRepo {
    const val FOLDER = "DCIM/MayskCamera"

    fun newName(prefix: String = "IMG"): String =
        "${prefix}_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    @SuppressLint("MissingPermission")
    fun lastLocation(context: Context): Location? = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()

    suspend fun saveJpeg(
        context: Context, bitmap: Bitmap, quality: Int, location: Location? = null, name: String = newName()
    ): Uri? = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "$name.jpg")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        runCatching {
            ExifInterface(tmp).apply {
                setAttribute(ExifInterface.TAG_SOFTWARE, "Maysk Camera")
                setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
                setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()))
                if (location != null) setGpsInfo(location)
                saveAttributes()
            }
        }
        val uri = insertFile(context, tmp, "$name.jpg", "image/jpeg", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        tmp.delete()
        uri
    }

    suspend fun saveVideoFile(context: Context, file: File, name: String = newName("VID")): Uri? = withContext(Dispatchers.IO) {
        insertFile(context, file, "$name.mp4", "video/mp4", MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    }

    suspend fun savePdf(context: Context, src: Uri, name: String = newName("SCAN")): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/MayskCamera")
        }
        val r = context.contentResolver
        val uri = r.insert(MediaStore.Files.getContentUri("external"), values) ?: return@withContext null
        r.openOutputStream(uri)?.use { out -> r.openInputStream(src)?.use { it.copyTo(out) } }
        uri
    }

    private fun insertFile(context: Context, file: File, display: String, mime: String, collection: Uri): Uri? {
        val r = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, display)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(collection, values) ?: return null
        runCatching {
            r.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        }.onFailure { r.delete(uri, null, null); return null }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        r.update(uri, values, null, null)
        return uri
    }

    fun videoContentValues(name: String = newName("VID")): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
    }

    suspend fun loadBitmap(context: Context, uri: Uri, maxSide: Int = 4000): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val m = max(info.size.width, info.size.height)
                if (m > maxSide) {
                    val s = maxSide.toFloat() / m
                    decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                }
            }
        }.getOrNull()
    }

    fun thumbnail(context: Context, uri: Uri, size: Int = 320): Bitmap? =
        runCatching { context.contentResolver.loadThumbnail(uri, Size(size, size), null) }.getOrNull()

    /** Медиа Maysk Camera (фото + видео), новые сверху. */
    suspend fun queryMaysk(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$FOLDER%")
        (query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, sel, args) +
            query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, sel, args)).sortedByDescending { it.dateMs }
    }

    /** Все фото устройства (для «Альбомов»), требуется разрешение на чтение медиа. */
    suspend fun queryAll(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        (query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, null, null) +
            query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, null, null)).sortedByDescending { it.dateMs }
    }

    private fun query(context: Context, collection: Uri, video: Boolean, sel: String?, args: Array<String>?): List<MediaItem> {
        val proj = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        if (video) proj += MediaStore.Video.VideoColumns.DURATION
        val list = mutableListOf<MediaItem>()
        runCatching {
            context.contentResolver.query(collection, proj.toTypedArray(), sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iDur = if (video) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    list += MediaItem(
                        ContentUris.withAppendedId(collection, id), id, video, c.getLong(iDate) * 1000,
                        if (iDur >= 0) c.getLong(iDur) else 0L, c.getString(iBucket) ?: "Другое", c.getString(iName) ?: ""
                    )
                }
            }
        }
        return list
    }
}
