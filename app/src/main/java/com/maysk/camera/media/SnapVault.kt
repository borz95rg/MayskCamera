package com.maysk.camera.media

import android.content.Context
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


    data class Entry(val id: String, val expiresAt: Long, val durationSeconds: Int)

    companion object { private const val KEY_ALIAS = "maysk_snap_aes_gcm_v1" }

    @Synchronized fun pending(): List<Entry> {
        cleanupExpired()
        return dir.listFiles()?.filter { it.extension == "bin" }?.mapNotNull { file ->
            val id = file.nameWithoutExtension
            val expiry = prefs.getLong("$id.expires", 0L)
            if (expiry > System.currentTimeMillis()) Entry(id, expiry, prefs.getInt("$id.duration", 10)) else null
        }?.sortedBy { it.expiresAt } ?: emptyList()
    }

    @Synchronized fun save(bitmap: Bitmap, durationSeconds: Int = 10): Entry {
        require(durationSeconds in 3..30)
        cleanupExpired()
        val id = "snap_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out); out.toByteArray() }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
        val encrypted = cipher.doFinal(bytes)
        File(dir, "$id.bin").outputStream().use { it.write(nonce); it.write(encrypted) }
        // Unopened snaps stay available for 24 hours; the selected view timer starts on first open.
        val expires = System.currentTimeMillis() + 24L * 60L * 60L * 1000L
        prefs.edit().putLong("$id.expires", expires).putInt("$id.duration", durationSeconds).apply()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ cleanupExpired() }, durationSeconds * 1000L + 250L)
        return Entry(id, expires, durationSeconds)
    }

    /** One-view semantics: encrypted source is deleted before the decrypted temporary file is returned. */
    @Synchronized fun consumeOnce(id: String): Uri? {
        cleanupExpired()
        val expires = prefs.getLong("$id.expires", 0L)
        val source = File(dir, "$id.bin")
        if (!id.matches(Regex("snap_[a-f0-9]{32}")) || expires <= System.currentTimeMillis() || !source.isFile) return null
        return try {
            val bytes = source.readBytes()
            require(bytes.size > 12)
            val nonce = bytes.copyOfRange(0, 12)
            val encrypted = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
            val plain = cipher.doFinal(encrypted)
            val output = File(app.cacheDir, "$id-view.jpg")
            output.writeBytes(plain)
            val viewSeconds = prefs.getInt("$id.duration", 10).coerceIn(3, 30)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ output.delete() }, viewSeconds * 1000L)
            source.delete()
            prefs.edit().remove("$id.expires").remove("$id.duration").apply()
            Uri.fromFile(output)
        } catch (_: Exception) { source.delete(); prefs.edit().remove("$id.expires").remove("$id.duration").apply(); null }
    }

    @Synchronized fun cleanupExpired() {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".bin")
            if (file.extension == "bin" && prefs.getLong("$id.expires", 0L) <= now) {
                file.delete(); prefs.edit().remove("$id.expires").remove("$id.duration").apply()
            }
        }
        app.cacheDir.listFiles()?.filter { it.name.startsWith("snap_") && it.name.endsWith("-view.jpg") }
            ?.filter { now - it.lastModified() > 30_000L }?.forEach { it.delete() }
    }
}
