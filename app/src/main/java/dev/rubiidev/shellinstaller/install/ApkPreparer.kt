package dev.rubiidev.shellinstaller.install

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import dev.rubiidev.shellinstaller.util.versionCodeCompat
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

data class ApkInfo(
    val label: String,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val totalSize: Long,
    val splitCount: Int,
    val baseApk: File,
    val allApks: List<File>,
    val isBundle: Boolean,
    val fileName: String,
    val icon: Drawable?,
    val workDir: File
)

/**
 * Copies the incoming URI(s) into the cache, extracts split bundles (APKS / XAPK / APKM / ZIP),
 * and parses the base APK for display. Detection is content-based: a real APK has a root
 * AndroidManifest.xml, while a bundle contains one or more .apk entries.
 */
class ApkPreparer(private val context: Context) {

    fun prepare(uris: List<Uri>): ApkInfo {
        val workDir = File(context.cacheDir, "install-" + System.currentTimeMillis()).apply { mkdirs() }
        val copied = ArrayList<File>()
        for ((index, uri) in uris.withIndex()) {
            val name = displayName(uri) ?: "file-$index.apk"
            val dest = File(workDir, uniqueName(workDir, sanitize(name)))
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open the selected file.")
            input.use { source -> dest.outputStream().use { source.copyTo(it) } }
            copied.add(dest)
        }
        if (copied.isEmpty()) throw IOException("No files were selected.")

        val apks = ArrayList<File>()
        var isBundle = false
        for (file in copied) {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                val hasManifest = entries.any { it.name == "AndroidManifest.xml" }
                val apkEntries = entries.filter { it.name.endsWith(".apk", ignoreCase = true) }
                when {
                    hasManifest -> apks.add(file)
                    apkEntries.isNotEmpty() -> {
                        isBundle = true
                        for (entry in apkEntries) {
                            val outName = uniqueName(workDir, sanitize(entry.name.substringAfterLast('/')))
                            val out = File(workDir, outName)
                            zip.getInputStream(entry).use { source -> out.outputStream().use { source.copyTo(it) } }
                            apks.add(out)
                        }
                    }
                    else -> throw IOException("Not an APK or a supported split bundle: ${file.name}")
                }
            }
        }
        if (apks.isEmpty()) throw IOException("No APK files were found.")

        val pm = context.packageManager
        val base = findBase(pm, apks)
        val packageInfo = pm.getPackageArchiveInfo(base.absolutePath, 0)
        val appInfo = packageInfo?.applicationInfo
        val label = appInfo?.loadLabel(pm)?.toString() ?: base.name
        val icon = try {
            appInfo?.loadIcon(pm)
        } catch (t: Throwable) {
            null
        }

        return ApkInfo(
            label = label,
            packageName = packageInfo?.packageName,
            versionName = packageInfo?.versionName,
            versionCode = packageInfo?.versionCodeCompat() ?: 0L,
            minSdk = appInfo?.minSdkVersion ?: 0,
            targetSdk = appInfo?.targetSdkVersion ?: 0,
            totalSize = apks.sumOf { it.length() },
            splitCount = apks.size,
            baseApk = base,
            allApks = apks,
            isBundle = isBundle,
            fileName = copied.first().name,
            icon = icon,
            workDir = workDir
        )
    }

    private fun findBase(pm: PackageManager, apks: List<File>): File {
        // The base APK carries the <application> element; config splits do not.
        for (file in apks) {
            val info = pm.getPackageArchiveInfo(file.absolutePath, 0)
            if (info?.applicationInfo != null) return file
        }
        return apks.firstOrNull { it.name.contains("base", true) || it.name.contains("master", true) }
            ?: apks.maxByOrNull { it.length() }
            ?: apks.first()
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun uniqueName(dir: File, name: String): String {
        var candidate = name
        var i = 1
        while (File(dir, candidate).exists()) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) {
                name.substring(0, dot) + "-" + i + name.substring(dot)
            } else {
                "$name-$i"
            }
            i++
        }
        return candidate
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "file.apk" }
    }
}
