package dev.nooko.launcher

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Pictures from the web, kept on disk: a TMDB backdrop or a service logo is downloaded once and read from the file after that, also
 * after the app has been closed. The cache folder (the TV can clear it) is trimmed to [MAX] bytes, oldest first.
 */
internal object ImageCache {
    private const val MAX = 48L * 1024 * 1024

    private fun name(url: String) = MessageDigest.getInstance("SHA-1").digest(url.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    /** The picture at [url] as a stream: from the cache when it is there, else downloaded into it first. */
    fun open(context: Context, url: String, connectMs: Int = 5000, readMs: Int = 8000): InputStream {
        val dir = File(context.cacheDir, "img").apply { mkdirs() }
        val file = File(dir, name(url))
        if (file.isFile && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis()) // read lately: it stays when the cache is trimmed
            return file.inputStream()
        }
        val part = File(dir, "${file.name}.${Thread.currentThread().id}.part")
        val conn = Http.open(url, connectMs = connectMs, readMs = readMs)
        try {
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(file)) { part.delete(); throw IOException("could not keep $url") }
        trim(dir)
        return file.inputStream()
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= MAX) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }
}
