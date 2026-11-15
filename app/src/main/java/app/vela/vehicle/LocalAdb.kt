package app.vela.vehicle

import android.content.Context
import android.util.Log
import app.vela.core.vehicle.AdbWire
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/**
 * A minimal adb client to the head unit's own adbd on 127.0.0.1 (SPEC 10.9, The adb cluster
 * route). It runs shell commands as the shell user, which on some BYD firmware is the only user
 * that sees the cluster displays. It needs network debugging on in the car, and the first
 * connection shows the car's own "Allow debugging?" prompt for Vela's key; with "Always allow"
 * ticked, later connections go straight through.
 *
 * Every call opens a connection, runs one command and closes. Call only off the main thread.
 * The key lives in no-backup storage, so a restored backup never carries a trusted key to another
 * device.
 */
internal class LocalAdb(private val context: Context) {

    class AuthPending : Exception("waiting for the debugging prompt on the car")

    /**
     * Runs [command] through `shell:` and returns what it printed. [onPrompt] runs when adbd
     * did not know Vela's key and the car is showing its prompt. Throws [AuthPending] when the
     * prompt was not answered within [approvalMs], and an IOException when adbd is not listening.
     */
    fun shell(command: String, approvalMs: Int = 60_000, onPrompt: () -> Unit = {}): String {
        Socket().use { s ->
            s.connect(InetSocketAddress(HOST, PORT), CONNECT_MS)
            s.tcpNoDelay = true
            s.soTimeout = READ_MS
            val input = DataInputStream(s.getInputStream().buffered())
            val out = s.getOutputStream()
            handshake(input, out, s, approvalMs, onPrompt)
            return run(input, out, command)
        }
    }

    private fun handshake(input: DataInputStream, out: OutputStream, s: Socket, approvalMs: Int, onPrompt: () -> Unit) {
        val keys = keyPair()
        send(out, AdbWire.connect())
        var signed = false
        while (true) {
            val m = read(input)
            when (m.command) {
                AdbWire.CNXN -> return
                AdbWire.AUTH -> {
                    if (m.arg0 != AdbWire.AUTH_TOKEN) error("unexpected auth type ${m.arg0}")
                    if (!signed) {
                        signed = true
                        send(out, AdbWire.Message(AdbWire.AUTH, AdbWire.AUTH_SIGNATURE, 0, sign(keys, m.data)))
                    } else {
                        // The signature was not accepted: adbd does not know this key yet. Offer
                        // it, which shows the car's prompt, and wait for the driver to answer.
                        val pub = AdbWire.publicKey(keys.public as RSAPublicKey, KEY_NAME)
                        send(out, AdbWire.Message(AdbWire.AUTH, AdbWire.AUTH_RSAPUBLICKEY, 0, pub))
                        onPrompt()
                        s.soTimeout = approvalMs
                        val reply = try { read(input) } catch (e: java.net.SocketTimeoutException) { throw AuthPending() }
                        s.soTimeout = READ_MS
                        if (reply.command == AdbWire.CNXN) return
                        throw AuthPending()
                    }
                }
                else -> error("unexpected ${AdbWire.name(m.command)} during connect")
            }
        }
    }

    private fun run(input: DataInputStream, out: OutputStream, command: String): String {
        val local = 1
        send(out, AdbWire.open(local, "shell:$command"))
        val text = StringBuilder()
        var remote = 0
        while (true) {
            val m = read(input)
            when (m.command) {
                AdbWire.OKAY -> remote = m.arg0
                AdbWire.WRTE -> {
                    text.append(String(m.data))
                    send(out, AdbWire.Message(AdbWire.OKAY, local, m.arg0))
                    if (text.length > MAX_OUTPUT) error("shell output too large")
                }
                AdbWire.CLSE -> {
                    if (remote != 0) runCatching { send(out, AdbWire.Message(AdbWire.CLSE, local, remote)) }
                    return text.toString()
                }
                else -> Log.d(TAG, "ignored ${AdbWire.name(m.command)}")
            }
        }
    }

    private fun send(out: OutputStream, m: AdbWire.Message) {
        out.write(AdbWire.encode(m))
        out.flush()
    }

    private fun read(input: DataInputStream): AdbWire.Message {
        val h = ByteArray(AdbWire.HEADER)
        input.readFully(h)
        val (cmd, a0, a1, len) = AdbWire.decodeHeader(h).let { listOf(it[0], it[1], it[2], it[3]) }
        val data = ByteArray(len)
        input.readFully(data)
        return AdbWire.Message(cmd, a0, a1, data)
    }

    private fun sign(keys: KeyPair, token: ByteArray): ByteArray {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, keys.private)
        return c.doFinal(AdbWire.digestInfo(token))
    }

    /** Vela's adb key, made once (2048-bit RSA) and kept in no-backup storage. */
    @Synchronized
    private fun keyPair(): KeyPair {
        val file = File(context.noBackupFilesDir, KEY_FILE)
        if (file.exists()) {
            runCatching {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(file.readBytes())) as RSAPrivateCrtKey
                val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent))
                return KeyPair(pub, priv)
            }.onFailure { Log.w(TAG, "adb key unreadable, making a new one") }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        file.writeBytes(kp.private.encoded)
        return kp
    }

    companion object {
        private const val TAG = "VelaClusterAdb"
        const val HOST = "127.0.0.1"
        const val PORT = 5555
        private const val CONNECT_MS = 2_000
        private const val READ_MS = 10_000
        private const val MAX_OUTPUT = 4 * 1024 * 1024
        private const val KEY_FILE = "vela_adbkey"
        private const val KEY_NAME = "Vela cluster"
    }
}
