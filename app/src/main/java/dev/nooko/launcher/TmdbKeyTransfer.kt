package dev.nooko.launcher

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64

/** The words on a page the TV serves to a phone: the TV's language for a key, or the English given beside it. */
internal typealias PhoneText = (key: String, english: String) -> String

internal fun htmlText(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** A one-use form on the TV, open for five minutes or until the user leaves the QR screen. */
internal class TmdbKeyTransfer(address: InetAddress, private val t: PhoneText = { _, english -> english }, private val receive: (String) -> Unit) {
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
    private val server = ServerSocket(0, 2, address)
    private val host = "${address.hostAddress}:${server.localPort}"
    val url = "http://$host/$token"

    init {
        Thread(::serve, "tmdb-key-transfer").start()
    }

    fun stop() = server.close()

    private fun serve() {
        val expires = System.nanoTime() + 300_000_000_000L
        server.use {
            while (!it.isClosed) {
                val remaining = (expires - System.nanoTime()) / 1_000_000
                if (remaining <= 0) return
                val client = try {
                    it.soTimeout = remaining.toInt()
                    it.accept()
                } catch (_: IOException) {
                    return
                }
                try {
                    client.use { s ->
                        s.soTimeout = minOf(5_000, remaining.toInt())
                        handle(s.getInputStream().buffered(), s.getOutputStream(), expires)
                    }
                } catch (_: IOException) {
                    // A stalled or disconnected phone does not consume the pairing session.
                }
            }
        }
    }

    private fun handle(input: InputStream, output: OutputStream, expires: Long) {
        val request = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        var complete = false
        repeat(24) {
            if (!complete) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) complete = true
                else headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
        }
        if (!complete || headers["host"] != host) return
        when (request) {
            "GET /$token HTTP/1.1" -> reply(output, "200 OK", t("key_title", "Send a TMDB key"), form())
            "POST /$token HTTP/1.1" -> {
                val validForm = headers["origin"] == "http://$host" && headers["content-length"] == "36" &&
                    headers["content-type"] == "application/x-www-form-urlencoded"
                val body = if (validForm) ByteArray(36) { input.read().toByte() }.decodeToString() else ""
                val key = body.takeIf { it.startsWith("key=") }?.removePrefix("key=")?.takeIf(Tmdb::validKey)
                if (key == null || server.isClosed || System.nanoTime() >= expires) {
                    return reply(output, "400 Bad Request", t("key_bad_title", "Key not sent"),
                        """<p>${htmlText(t("key_bad", "Check the key or scan a new code."))}</p><a href="/$token">${htmlText(t("back_form", "Back to the form"))}</a>""")
                }
                stop()
                receive(key)
                reply(output, "200 OK", t("key_sent_title", "Key sent"),
                    "<p>" + htmlText(t("key_sent", "Select %1\$s on the TV.")).replace("%1\$s", "<strong>${htmlText(t("done", "Done"))}</strong>") + "</p>")
            }
            else -> reply(output, "404 Not Found", t("code_gone_title", "Code unavailable"), "<p>${htmlText(t("scan_tv", "Scan the code on the TV."))}</p>")
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        repeat(1024) {
            when (val c = input.read()) {
                -1 -> return null
                '\n'.code -> return line.toString().trimEnd('\r')
                else -> line.append(c.toChar())
            }
        }
        return null
    }

    private fun reply(out: OutputStream, status: String, title: String, body: String) {
        val bytes = """
            <!doctype html>
            <html lang="${t("lang", "en")}" dir="${t("dir", "ltr")}">
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${htmlText(title)} · Nooko Launcher</title>
            <style>$STYLE</style>
            <main>
              <p class="brand">Nooko Launcher</p>
              <h1>${htmlText(title)}</h1>
              $body
            </main>
            </html>
        """.encodeToByteArray()
        out.write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
            "Referrer-Policy: same-origin\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").encodeToByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun form(): String {
        val link = """<a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noopener">${htmlText(t("key_link", "TMDB API settings"))}</a>"""
        return """
            <p>${htmlText(t("key_copy", "Copy your v3 key from %1\$s.")).replace("%1\$s", link)}</p>
            <form method="post">
              <label for="key">${htmlText(t("key_label", "TMDB API key (v3)"))}</label>
              <input id="key" name="key" type="password" required pattern="[a-fA-F0-9]{32}" autocomplete="off" autocapitalize="none" spellcheck="false">
              <button>${htmlText(t("send", "Send to TV"))}</button>
            </form>
            <p class="connection">${htmlText(t("wifi", "Use trusted Wi-Fi. The connection to the TV is unencrypted."))}</p>
        """
    }

    private companion object {

        const val STYLE = """
            :root {
              color-scheme: light dark;
              font: 16px/1.5 system-ui, sans-serif;
              --ground: #e5effb;
              --halo: #bcd7f3;
              --ink: #0d2540;
              --muted: #37516a;
              --glass: rgba(250, 253, 255, .8);
              --line: #afc1d5;
              --field: #f6faff;
              --button: #0d2540;
              --button-ink: #fff;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --ground: #0f1c33;
                --halo: #304d6c;
                --ink: #f4f8ff;
                --muted: #c0cede;
                --glass: rgba(24, 40, 62, .9);
                --line: #637b96;
                --field: #192b43;
                --button: #f4f8ff;
                --button-ink: #0d2540;
              }
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              min-height: 100vh;
              min-height: 100svh;
              padding: 24px 16px;
              display: grid;
              place-items: center;
              background: radial-gradient(ellipse at 28% 12%, var(--halo), transparent 72%), var(--ground);
              color: var(--ink);
            }
            main {
              width: min(100%, 400px);
              padding: 28px;
              border: 1px solid var(--line);
              border-radius: 26px;
              background: var(--glass);
              box-shadow: 0 22px 50px -20px rgba(8, 22, 40, .4), inset 0 1px rgba(255, 255, 255, .18);
              backdrop-filter: blur(20px);
            }
            h1 { margin: 8px 0 12px; font-size: 1.75rem; line-height: 1.12; letter-spacing: -.035em; }
            p { margin: 0 0 20px; color: var(--muted); }
            main > :last-child { margin-bottom: 0; }
            a { color: var(--ink); text-underline-offset: 3px; }
            .brand, label { color: var(--ink); font-size: .875rem; font-weight: 650; }
            .brand { margin-bottom: 18px; }
            label { display: block; margin-bottom: 6px; }
            input, button { width: 100%; min-height: 48px; padding: 10px 14px; font: inherit; }
            input { border: 1px solid var(--line); border-radius: 12px; background: var(--field); color: var(--ink); }
            button {
              margin-top: 12px;
              border: 0;
              border-radius: 999px;
              background: var(--button);
              color: var(--button-ink);
              font-weight: 650;
              cursor: pointer;
            }
            :focus-visible { outline: 2px solid var(--ink); outline-offset: 3px; }
            .connection { margin: 16px 0 0; font-size: .8125rem; }
        """
    }
}
