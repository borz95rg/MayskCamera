package com.maysk.camera.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.maysk.camera.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
)

sealed class UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck()
    data object UpToDate : UpdateCheck()
    data class Failed(val reason: String) : UpdateCheck()
}

/**
 * Автообновление: каждая сборка GitHub Actions публикуется как релиз v1.0.N,
 * приложение сравнивает N со своим номером версии, скачивает APK и запускает установку.
 */
object Updater {

    val repo: String get() = BuildConfig.UPDATE_REPO

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        if (repo.isBlank()) return@withContext UpdateCheck.Failed("Сборка без привязки к GitHub")
        try {
            val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MayskCamera/${BuildConfig.VERSION_NAME}")
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            when (conn.responseCode) {
                200 -> Unit
                404 -> return@withContext UpdateCheck.Failed("Релизы не найдены. Если репозиторий приватный — сделайте его публичным, иначе телефон не может проверить обновления")
                else -> return@withContext UpdateCheck.Failed("GitHub ответил кодом ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val tag = json.optString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull()
                ?: return@withContext UpdateCheck.Failed("Непонятный номер версии: $tag")
            if (code <= BuildConfig.VERSION_CODE) return@withContext UpdateCheck.UpToDate
            val assets = json.optJSONArray("assets")
            var url: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            if (url.isNullOrBlank()) return@withContext UpdateCheck.Failed("В релизе нет APK")
            UpdateCheck.Available(UpdateInfo(code, tag.removePrefix("v"), json.optString("body").trim(), url))
        } catch (t: Throwable) {
            UpdateCheck.Failed("Нет связи с GitHub: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "MayskCamera-${info.versionName}.apk")
            var conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MayskCamera")
            // GitHub отдаёт файл через перенаправление на другой домен
            var hops = 0
            while (conn.responseCode in 300..399 && hops < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(loc).openConnection() as HttpURLConnection
                hops++
            }
            if (conn.responseCode != 200) return@withContext null
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            conn.disconnect()
            file
        } catch (t: Throwable) {
            null
        }
    }

    /** Нужно ли разрешение «Установка неизвестных приложений» для Maysk. */
    fun needsInstallPermission(context: Context): Boolean = !context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
