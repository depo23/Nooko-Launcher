package dev.nooko.launcher

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one place the app talks to the web by hand: TMDB, Trakt, Simkl, MDBList, Bing, Wallhaven and the pictures all go through
 * here, so the timeouts, the user agent and the way a failure is read are the same everywhere.
 */
internal object Http {
    const val AGENT = "Nooko-Launcher"

    class Response(val code: Int, val body: String) {
        val ok get() = code in 200..299
    }

    /** One request whose answer is read as text. A failed status still gives its code and the body it came with. */
    fun send(
        url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), json: String? = null, form: String? = null,
        connectMs: Int = 8000, readMs: Int = 12000,
    ): Response {
        val c = open(url, headers, connectMs, readMs)
        try {
            c.requestMethod = method
            c.setRequestProperty("Accept", "application/json")
            if (json != null || form != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", if (form != null) "application/x-www-form-urlencoded" else "application/json")
                c.outputStream.use { it.write((form ?: json!!).encodeToByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Response(code, text)
        } finally {
            c.disconnect()
        }
    }

    /** A connection to read as a stream (a picture, a file, a feed); the caller reads it and may ask it for the length. */
    fun open(url: String, headers: Map<String, String> = emptyMap(), connectMs: Int = 8000, readMs: Int = 20000): HttpURLConnection {
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("not a web address: $url")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", AGENT)
            for ((k, v) in headers) setRequestProperty(k, v)
        }
    }
}
