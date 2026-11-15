package app.vela.core.vehicle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

class AdbWireTest {
    @Test fun connectMessageMatchesTheWireFormat() {
        val bytes = AdbWire.encode(AdbWire.connect())
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbWire.CNXN, h.int)
        assertEquals(0x01000001, h.int)
        assertEquals(256 * 1024, h.int)
        assertEquals(7, h.int) // "host::\0"
        assertEquals("host::\u0000".toByteArray().sumOf { it.toInt() and 0xff }, h.int)
        assertEquals(AdbWire.CNXN.inv(), h.int)
        assertEquals("host::\u0000", String(bytes, 24, 7))
    }

    @Test fun headerRoundTrips() {
        val m = AdbWire.Message(AdbWire.WRTE, 7, 9, byteArrayOf(1, 2, 3))
        val f = AdbWire.decodeHeader(AdbWire.encode(m))
        assertArrayEquals(intArrayOf(AdbWire.WRTE, 7, 9, 3), f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun badMagicIsRejected() {
        val bytes = AdbWire.encode(AdbWire.Message(AdbWire.OKAY, 1, 2))
        bytes[20] = 0
        AdbWire.decodeHeader(bytes)
    }

    @Test fun openNamesTheServiceWithATrailingNul() {
        val m = AdbWire.open(1, "shell:echo hi")
        assertEquals(AdbWire.OPEN, m.command)
        assertEquals(1, m.arg0)
        assertEquals("shell:echo hi\u0000", String(m.data))
    }

    @Test fun publicKeyIsTheMincryptStruct() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        val text = String(AdbWire.publicKey(pub, "Vela cluster"))
        assertTrue(text.endsWith(" Vela cluster\u0000"))
        val raw = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, raw.size)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.int)
        val n0inv = b.int.toLong() and 0xffffffffL
        val words = LongArray(64) { b.int.toLong() and 0xffffffffL }
        val rrWords = LongArray(64) { b.int.toLong() and 0xffffffffL }
        assertEquals(65537, b.int)
        assertEquals(pub.modulus, fromWords(words))
        // n0inv is -1/n mod 2^32: n0inv * n[0] == 2^32 - 1 (mod 2^32).
        assertEquals(0xffffffffL, (n0inv * words[0]) and 0xffffffffL)
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(pub.modulus), fromWords(rrWords))
    }

    @Test fun signatureVerifiesAsSha1Pkcs1() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val token = ByteArray(20) { it.toByte() }
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, kp.private) }
        val sig = c.doFinal(AdbWire.digestInfo(token))
        // adbd checks it as RSA_verify(NID_sha1, token, ...): a SHA1withRSA verify of the prehashed token.
        val d = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, kp.public) }
        val plain = d.doFinal(sig)
        assertEquals(35, plain.size)
        assertArrayEquals(token, plain.copyOfRange(15, 35))
    }

    @Test fun displaysAreReadFromTheDump() {
        val dump = """
              mBaseDisplayInfo=DisplayInfo{"ivi", displayId 0, displayGroupId 0, FLAG_SECURE
              mBaseDisplayInfo=DisplayInfo{"fse", displayId 2, displayGroupId 0
              mBaseDisplayInfo=DisplayInfo{"fission_bg_XDJAScreenProjection", displayId 3, displayGroupId 0
              mBaseDisplayInfo=DisplayInfo{"shared_fission_bg_XDJAScreenProjection_0", displayId 4, displayGroupId 0
              mBaseDisplayInfo=DisplayInfo{"shared_fission_bg_XDJAScreenProjection_1", displayId 5, displayGroupId 0
              mBaseDisplayInfo=DisplayInfo{"ivi", displayId 0, displayGroupId 0
        """.trimIndent()
        val d = AdbWire.displays(dump)
        assertEquals(listOf(0, 2, 3, 4, 5), d.map { it.first })
        assertEquals("shared_fission_bg_XDJAScreenProjection_1", d.last().second)
        // The adb route offers the stock map surface first.
        assertEquals(
            listOf(ClusterTargets.DILINK5_FULL, ClusterTargets.DILINK5_SIDE),
            ClusterTargets.adbCandidates(d.map { it.second }),
        )
    }

    private fun fromWords(w: LongArray): BigInteger =
        w.foldIndexed(BigInteger.ZERO) { i, acc, x -> acc.add(BigInteger.valueOf(x).shiftLeft(32 * i)) }
}
