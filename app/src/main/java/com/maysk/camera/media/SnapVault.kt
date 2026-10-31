package com.maysk.camera.media

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.CipherOutputStream
import javax.crypto.CipherInputStream
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore

/** Encrypted, app-private one-view snap storage. A snap is removed before plaintext is returned. */
class SnapVault(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.noBackupFilesDir, "private_snaps").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("private_snap_index", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }


    data class Entry(val id: String, val expiresAt: Long, val durationSeconds: Int, val isVideo: Boolean = false)

    companion object { private const val KEY_ALIAS = "maysk_snap_aes_gcm_v1" }

    @Synchronized fun pending(): List<Entry> {
        cleanupExpired()
        return dir.listFiles()?.filter { it.extension == "bin" }?.mapNotNull { file ->
            val id = file.nameWithoutExtension
            val expiry = prefs.getLong("$id.expires", 0L)
            if (expiry > System.currentTimeMillis()) Entry(id, expiry, prefs.getInt("$id.duration", 10), prefs.getBoolean("$id.video", false)) else null
        }?.sortedBy { it.expiresAt } ?: emptyList()
    }

    @Synchronized fun save(bitmap: Bitmap, durationSeconds: Int = 10): Entry {
        require(durationSeconds in setOf(3, 5, 10, 30))
        cleanupExpired()
        val id = "snap_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out); out.toByteArray() }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
        val encrypted = cipher.doFinal(bytes)
        File(dir, "$id.bin").outputStream().use { it.write(nonce); it.write(encrypted) }
        // Unopened snaps stay available for 24 hours; the selected view timer starts on first open.
        val expires = System.currentTimeMillis() + 24L * 60L * 60L * 1000L
        prefs.edit().putLong("$id.expires", expires).putInt("$id.duration", durationSeconds).putBoolean("$id.video", false).apply()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ cleanupExpired() }, durationSeconds * 1000L + 250L)
        return Entry(id, expires, durationSeconds, false)
    }

    /** Encrypt a recorded MP4 as a stream; the unencrypted source remains in MediaStore until save succeeds. */
    @Synchronized fun saveVideo(sourceUri: Uri, durationSeconds: Int = 10): Entry? {
        require(durationSeconds in setOf(3, 5, 10, 30))
        cleanupExpired()
        val id = "snap_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val target = File(dir, "$id.bin")
        return try {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
            app.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { raw ->
                    raw.write(nonce)
                    CipherOutputStream(raw, cipher).use { encrypted -> input.copyTo(encrypted) }
                }
            } ?: return null
            val expires = System.currentTimeMillis() + 24L * 60L * 60L * 1000L
            prefs.edit().putLong("$id.expires", expires).putInt("$id.duration", durationSeconds).putBoolean("$id.video", true).apply()
            Entry(id, expires, durationSeconds, true)
        } catch (_: Exception) {
            target.delete()
            null
        }
    }


    /** Перенос Snap в обычную системную галерею. Исходник удаляется только после успешной записи. */
    @Synchronized fun saveToGallery(id: String): Uri? {
        cleanupExpired()
        if (!id.matches(Regex("snap_[a-f0-9]{32}"))) return null
        val source = File(dir, "$id.bin")
        val expires = prefs.getLong("$id.expires", 0L)
        if (!source.isFile || expires <= System.currentTimeMillis()) return null
        val isVideo = prefs.getBoolean("$id.video", false)
        val mime = if (isVideo) "video/mp4" else "image/jpeg"
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val name = if (isVideo) "Maysk_Snap_${System.currentTimeMillis()}.mp4" else "Maysk_Snap_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/MayskCamera" else "Pictures/MayskCamera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val destination = app.contentResolver.insert(collection, values) ?: return null
        return try {
            source.inputStream().use { raw ->
                val nonce = ByteArray(12)
                require(raw.read(nonce) == 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
                CipherInputStream(raw, cipher).use { decrypted ->
                    app.contentResolver.openOutputStream(destination, "w")?.use { output -> decrypted.copyTo(output) }
                        ?: throw IllegalStateException("Не удалось открыть файл галереи")
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                app.contentResolver.update(destination, ready, null, null)
            }
            source.delete()
            prefs.edit().remove("$id.expires").remove("$id.duration").remove("$id.video").apply()
            destination
        } catch (_: Exception) {
            runCatching { app.contentResolver.delete(destination, null, null) }
            null
        }
    }

    /** Удаляет Snap из закрытого хранилища без создания открытой копии. */
    @Synchronized fun delete(id: String): Boolean {
        if (!id.matches(Regex("snap_[a-f0-9]{32}"))) return false
        val removed = File(dir, "$id.bin").delete()
        prefs.edit().remove("$id.expires").remove("$id.duration").remove("$id.video").apply()
        return removed
    }

    /** One-view semantics: encrypted source is deleted before the decrypted temporary file is returned. */
    @Synchronized fun consumeOnce(id: String): Uri? {
        cleanupExpired()
        val expires = prefs.getLong("$id.expires", 0L)
        val source = File(dir, "$id.bin")
        if (!id.matches(Regex("snap_[a-f0-9]{32}")) || expires <= System.currentTimeMillis() || !source.isFile) return null
        val isVideo = prefs.getBoolean("$id.video", false)
        val output = File(app.cacheDir, "$id-view.${if (isVideo) "mp4" else "jpg"}")
        return try {
            val viewSeconds = prefs.getInt("$id.duration", 10).let { if (it in setOf(3, 5, 10, 30)) it else 10 }
            source.inputStream().use { raw ->
                val nonce = ByteArray(12)
                require(raw.read(nonce) == 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
                CipherInputStream(raw, cipher).use { decrypted -> output.outputStream().use { decrypted.copyTo(it) } }
            }
            // Source is removed before the viewer receives a plaintext URI.
            source.delete()
            prefs.edit().remove("$id.expires").remove("$id.duration").remove("$id.video").apply()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ output.delete() }, viewSeconds * 1000L)
            Uri.fromFile(output)
        } catch (_: Exception) {
            output.delete()
            source.delete()
            prefs.edit().remove("$id.expires").remove("$id.duration").remove("$id.video").apply()
            null
        }
    }

    @Synchronized fun cleanupExpired() {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".bin")
            if (file.extension == "bin" && prefs.getLong("$id.expires", 0L) <= now) {
                file.delete(); prefs.edit().remove("$id.expires").remove("$id.duration").remove("$id.video").apply()
            }
        }
        app.cacheDir.listFiles()?.filter { it.name.startsWith("snap_") && (it.name.endsWith("-view.jpg") || it.name.endsWith("-view.mp4")) }
            ?.filter { now - it.lastModified() > 30_000L }?.forEach { it.delete() }
    }
}
