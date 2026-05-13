package top.wsdx233.r2droid.util

import android.content.Context
import android.net.Uri
import top.wsdx233.r2droid.core.data.prefs.SettingsManager
import java.io.File
import java.io.FileOutputStream

/** Utilities for files that should be safe to keep as project-owned binaries. */
object ProjectFileUtils {
    private const val PROJECT_BINARIES_DIR = "project_binaries"

    fun projectBinariesDir(context: Context): File {
        val customHome = SettingsManager.projectHome?.takeIf { it.isNotBlank() }
        val base = customHome?.let { File(it) }?.takeIf {
            (it.exists() || it.mkdirs()) && it.isDirectory && it.canWrite()
        } ?: context.filesDir
        return File(base, PROJECT_BINARIES_DIR).apply { mkdirs() }
    }

    fun safeFileName(name: String, fallback: String = "binary"): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\r\\n\\t]"), "_")
            .replace(Regex("[\\u0000-\\u001F]"), "_")
            .take(120)
            .trim()
        return cleaned.ifBlank { fallback }
    }

    fun uniqueFile(dir: File, preferredName: String): File {
        val safeName = safeFileName(preferredName)
        val base = safeName.substringBeforeLast('.', safeName)
        val ext = safeName.substringAfterLast('.', "").let { if (it.isBlank() || it == safeName) "" else ".$it" }
        var candidate = File(dir, safeName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_${index}${ext}")
            index++
        }
        return candidate
    }

    fun copyFileToProjectBinaries(context: Context, sourcePath: String, preferredName: String = File(sourcePath).name): File {
        val source = File(sourcePath)
        require(source.exists() && source.canRead()) { "Source file is not readable: $sourcePath" }
        val target = uniqueFile(projectBinariesDir(context), preferredName)
        source.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        target.setReadable(true, true)
        target.setWritable(true, true)
        return target
    }

    fun copyUriToProjectBinaries(context: Context, uri: Uri, preferredName: String): File {
        val target = uniqueFile(projectBinariesDir(context), preferredName)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to open selected file")
        input.use { inStream ->
            FileOutputStream(target).use { output -> inStream.copyTo(output) }
        }
        target.setReadable(true, true)
        target.setWritable(true, true)
        return target
    }
}
