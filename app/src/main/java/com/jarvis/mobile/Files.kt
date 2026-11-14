package com.jarvis.mobile

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File search and opening for "find the file called X" style commands. */
object Files {
    private val skipDirs = setOf("Android", ".thumbnails", ".cache", "cache", ".trash", ".Trash")

    fun find(query: String, limit: Int = 8): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val tokens = query.lowercase().split(Regex("[\\s_.\\-]+")).filter { it.isNotEmpty() }
        val hits = ArrayList<File>()
        var seen = 0
        fun walk(dir: File, depth: Int) {
            if (depth > 7 || seen > 60_000) return
            val kids = dir.listFiles() ?: return
            for (f in kids) {
                seen++
                if (f.isDirectory) {
                    if (f.name !in skipDirs) walk(f, depth + 1)
                } else {
                    val n = f.name.lowercase()
                    if (tokens.isEmpty() || tokens.all { n.contains(it) }) hits.add(f)
                }
            }
        }
        walk(root, 0)
        return hits.sortedByDescending { it.lastModified() }.take(limit)
    }

    fun describe(f: File): String {
        val d = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(f.lastModified()))
        return "${f.absolutePath}  (${f.length() / 1024} KB, $d)"
    }

    /** Opens with whatever app handles the type. Returns an error text or null on success. */
    fun open(ctx: Context, path: String): String? {
        val f = File(path)
        if (!f.exists() || !f.isFile) return "File not found: $path"
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            null
        } catch (e: Exception) {
            "No app can open ${f.name}: ${e.message}"
        }
    }
}
