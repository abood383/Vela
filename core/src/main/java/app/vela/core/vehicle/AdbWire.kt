package app.vela.core.vehicle

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * The parts of the adb wire protocol the cluster's adb route needs (SPEC 10.9, The adb cluster
 * route): message framing, the RSA public key in adb's own format, the token signature padding,
 * and reading the display list out of `dumpsys display`. Pure JVM, no sockets, so it is unit-tested.
 */
object AdbWire {
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257

    const val VERSION = 0x01000001
    const val MAX_PAYLOAD = 256 * 1024

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val HEADER = 24

    class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0)) {
        override fun toString() = "${name(command)}($arg0, $arg1, ${data.size} bytes)"
    }

    /** One message: the 24-byte little-endian header, then the payload. */
    fun encode(m: Message): ByteArray {
        val b = ByteBuffer.allocate(HEADER + m.data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(m.command).putInt(m.arg0).putInt(m.arg1).putInt(m.data.size)
        b.putInt(checksum(m.data)).putInt(m.command.inv())
        b.put(m.data)
        return b.array()
    }

    /** The header's fields: command, arg0, arg1, payload length. Throws on a bad magic. */
    fun decodeHeader(h: ByteArray): IntArray {
        require(h.size >= HEADER) { "short header" }
        val b = ByteBuffer.wrap(h, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        b.int // checksum: adbd at version 0x01000001 and later does not check it
        val magic = b.int
        require(magic == cmd.inv()) { "bad magic for ${name(cmd)}" }
        require(len in 0..MAX_PAYLOAD) { "bad payload length $len" }
        return intArrayOf(cmd, a0, a1, len)
    }

    fun checksum(data: ByteArray): Int = data.fold(0) { acc, x -> acc + (x.toInt() and 0xff) }

    fun connect(): Message = Message(CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())

    fun open(localId: Int, service: String): Message =
        Message(OPEN, localId, 0, (service + "\u0000").toByteArray())

    /**
     * adbd verifies the token signature as an RSA PKCS#1 v1.5 signature over a SHA-1 digest. The
     * token stands in for the digest, so what is encrypted with the private key is the SHA-1
     * DigestInfo prefix followed by the 20 token bytes.
     */
    fun digestInfo(token: ByteArray): ByteArray = SHA1_DIGEST_INFO + token

    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    /**
     * The public key as adbd stores it in `adb_keys`: base64 of the 524-byte mincrypt struct
     * (word count, n0inv, the modulus and R squared mod n as little-endian 32-bit words, the
     * exponent), then a space and a name, NUL-terminated. Only 2048-bit keys.
     */
    fun publicKey(key: RSAPublicKey, name: String): ByteArray {
        val words = 64
        val n = key.modulus
        require(n.bitLength() <= 2048) { "adb takes a 2048-bit key" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).toInt()
        // BigInteger.TWO is API 33; minSdk is 26.
        val rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(n0inv)
        putWords(b, n, words)
        putWords(b, rr, words)
        b.putInt(key.publicExponent.toInt())
        val text = Base64.getEncoder().encodeToString(b.array()) + " " + name + "\u0000"
        return text.toByteArray()
    }

    private fun putWords(b: ByteBuffer, v: BigInteger, words: Int) {
        val mask = BigInteger.valueOf(0xffffffffL)
        var x = v
        repeat(words) {
            b.putInt(x.and(mask).toInt())
            x = x.shiftRight(32)
        }
    }

    /** `(id, name)` for every display in a `dumpsys display` dump, in id order, each id once. */
    fun displays(dump: String): List<Pair<Int, String>> =
        DISPLAY.findAll(dump)
            .map { it.groupValues[2].toInt() to it.groupValues[1] }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .toList()

    private val DISPLAY = Regex("""mBaseDisplayInfo=DisplayInfo\{"([^"]*)", displayId (\d+)""")

    fun name(cmd: Int): String = when (cmd) {
        CNXN -> "CNXN"; AUTH -> "AUTH"; OPEN -> "OPEN"; OKAY -> "OKAY"; CLSE -> "CLSE"; WRTE -> "WRTE"
        else -> "0x" + Integer.toHexString(cmd)
    }
}
