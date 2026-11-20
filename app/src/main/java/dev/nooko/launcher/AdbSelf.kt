package dev.nooko.launcher

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

/**
 * Gives this app a permission that only adb can grant (WRITE_SECURE_SETTINGS), by talking to the TV's own adb over the network, the
 * way a computer would. It needs "ADB debugging" turned on in Developer options, and the TV asks the user once to allow this app.
 * No library: the adb wire protocol is a 24-byte header and a payload.
 */
internal object AdbSelf {
    enum class Result { DONE, NOT_LISTENING, NOT_ALLOWED, FAILED }

    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f
    private const val CLSE = 0x45534c43
    private const val WRTE = 0x45545257
    private const val VERSION = 0x01000001
    private const val PORT = 5555
    /** The bytes that go before a SHA-1 in an RSA signature (PKCS#1 v1.5). adb signs its 20-byte token as if it were the hash. */
    private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private class Wire(val socket: Socket) {
        private val input = socket.getInputStream().buffered()
        private val output = socket.getOutputStream().buffered()

        fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(0).putInt(command xor -1).array()
            output.write(header)
            output.write(data)
            output.flush()
        }

        fun read(): Message {
            val h = ByteArray(24)
            readFully(h)
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val command = b.int
            val arg0 = b.int
            val arg1 = b.int
            val length = b.int
            if (length < 0 || length > 1 shl 20) throw IOException("adb message too long")
            return Message(command, arg0, arg1, ByteArray(length).also(::readFully))
        }

        private fun readFully(into: ByteArray) {
            var at = 0
            while (at < into.size) {
                val n = input.read(into, at, into.size - at)
                if (n < 0) throw IOException("adb closed")
                at += n
            }
        }
    }

    private fun privateKey(c: Context): PrivateKey {
        val file = File(c.noBackupFilesDir, "adb-key")
        if (file.isFile) try { return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(file.readBytes())) } catch (_: Exception) { file.delete() }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        file.writeBytes(pair.private.encoded)
        return pair.private
    }

    /** The public key the way adb wants it: a small struct of 32-bit words, in base64, then a name for the TV's question. */
    internal fun publicKeyPayload(key: RSAPrivateCrtKey, name: String): ByteArray {
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).toInt()
        fun le(v: BigInteger): ByteArray = ByteArray(256).also { out ->
            val be = v.toByteArray().reversedArray()
            for (i in 0 until minOf(256, be.size)) out[i] = be[i]
        }
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val struct = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n0inv).put(le(n)).put(le(rr)).putInt(key.publicExponent.toInt()).array()
        return (Base64.encodeToString(struct, Base64.NO_WRAP) + " $name\u0000").toByteArray()
    }

    /** [onApproval] runs when the TV is asking the user, so the screen can say what to press. Blocks: call it off the main thread. */
    fun grant(c: Context, permission: String, hosts: List<String>, onApproval: () -> Unit): Result {
        val key = try { privateKey(c) } catch (e: Exception) { android.util.Log.w("Nooko Launcher", "adb key failed ($e)"); return Result.FAILED }
        var last = Result.NOT_LISTENING
        for (host in hosts.distinct()) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, PORT), 3000)
                socket.soTimeout = 5000
                last = talk(c, Wire(socket), key, permission, onApproval)
                if (last == Result.DONE) return last
            } catch (e: java.net.ConnectException) {
                android.util.Log.i("Nooko Launcher", "adb not listening on $host ($e)")
            } catch (e: IOException) {
                android.util.Log.w("Nooko Launcher", "adb talk failed on $host ($e)")
                last = Result.FAILED
            } finally {
                try { socket.close() } catch (_: IOException) { }
            }
        }
        return last
    }

    private fun talk(c: Context, wire: Wire, key: PrivateKey, permission: String, onApproval: () -> Unit): Result {
        val crt = key as RSAPrivateCrtKey
        wire.send(CNXN, VERSION, 4096, "host::nooko\u0000".toByteArray())
        var signed = false
        var connected = false
        val deadline = System.currentTimeMillis() + 90_000
        while (!connected) {
            if (System.currentTimeMillis() > deadline) return Result.NOT_ALLOWED
            val m = try { wire.read() } catch (e: java.net.SocketTimeoutException) { continue }
            when (m.command) {
                CNXN -> connected = true
                AUTH -> if (m.arg0 == 1) {
                    if (!signed) {
                        signed = true
                        val sig = Signature.getInstance("NONEwithRSA").apply { initSign(key); update(SHA1_PREFIX + m.data) }.sign()
                        wire.send(AUTH, 2, 0, sig)
                    } else {
                        // The TV does not know this key yet: it shows its question once the key arrives.
                        wire.send(AUTH, 3, 0, publicKeyPayload(crt, "nooko@tv"))
                        wire.socket.soTimeout = 5000
                        onApproval()
                    }
                }
            }
        }
        wire.send(OPEN, 1, 0, "shell:pm grant ${c.packageName} $permission\u0000".toByteArray())
        val out = StringBuilder()
        while (true) {
            val m = try { wire.read() } catch (e: IOException) { break }
            when (m.command) {
                WRTE -> { out.append(m.data.decodeToString()); wire.send(OKAY, 1, m.arg0) }
                CLSE -> break
            }
        }
        android.util.Log.i("Nooko Launcher", "adb grant answer: ${out.toString().trim()}")
        return if (c.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) Result.DONE else Result.FAILED
    }
}
