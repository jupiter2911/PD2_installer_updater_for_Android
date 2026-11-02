package com.pd2updater.install

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeystoreTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun passwordAndSeedAreFixed() {
        assertArrayEquals(
            bytes(0xde, 0x88, 0x2a, 0x14, 0xdb, 0x23, 0xe3, 0x8f, 0xb3, 0xfb, 0x7b, 0xa4, 0x22, 0xeb, 0x34, 0x18, 0x22, 0x15, 0x7a),
            Keystore.password(),
        )
        val seed = Keystore.scheduleSeed()
        assertArrayEquals(bytes(0x43, 0xea, 0x16, 0xdb, 0x9e, 0x52, 0xb2, 0xcf), seed.copyOfRange(0, 8))
        assertArrayEquals(bytes(0x75, 0x5d, 0x20, 0x13, 0x71, 0xe3, 0x5c, 0x39), seed.copyOfRange(104, 112))
    }

    @Test
    fun hashMatchesTheGame() {
        val zero = Keystore.Hash()
        zero.block(ByteArray(64))
        assertArrayEquals(
            bytes(0xd4, 0x0b, 0xe5, 0x96, 0x94, 0xf9, 0xf4, 0x09, 0x96, 0xc1, 0xa6, 0x7c, 0x60, 0x32, 0x41, 0xf3, 0xe3, 0x83, 0x9d, 0x0f),
            zero.digest(),
        )
        val tiled = Keystore.Hash()
        tiled.block(Keystore.tile(Keystore.password()))
        assertArrayEquals(
            bytes(0x4e, 0x3a, 0x79, 0xda, 0xd2, 0x97, 0x28, 0x30, 0xe2, 0x00, 0x3d, 0x92, 0xf3, 0x54, 0xd2, 0xf0, 0xe4, 0x8e, 0xa3, 0x9e),
            tiled.digest(),
        )
        assertEquals(0xfffffff0.toInt(), Keystore.smear(0x80000000.toInt(), 5))
    }

    @Test
    fun scheduleOpeningHashMatchesTheGame() {
        val (_, hash) = Keystore.schedule(Keystore.password())
        val digest = hash.digest()
        val words = (0 until 5).map { i ->
            (digest[i * 4].toInt() and 0xff) or
                ((digest[i * 4 + 1].toInt() and 0xff) shl 8) or
                ((digest[i * 4 + 2].toInt() and 0xff) shl 16) or
                ((digest[i * 4 + 3].toInt() and 0xff) shl 24)
        }
        assertEquals(
            listOf(0x846e6d34.toInt(), 0x658ad5c7, 0xef9cf2b5.toInt(), 0x2f8802c6, 0xa45b7ed4.toInt()),
            words,
        )
    }

    @Test
    fun wrappedLengths() {
        assertEquals(72, Keystore.wrappedLength(6))
        assertEquals(72, Keystore.wrappedLength(26))
        assertEquals(72, Keystore.wrappedLength(63))
        assertEquals(136, Keystore.wrappedLength(64))
    }

    @Test
    fun decryptSubkeysMatchTheGame() {
        val stream = openingStream()
        val subkeys = decryptSchedule(stream.copyOfRange(0, 16))
        assertEquals(listOf(0xa775, 0xacc1, 0x195c, 0x8293, 0xf287, 0xe665, 0x1a48, 0xad96), subkeys.take(8))
        assertEquals(listOf(0x3ab4, 0x4f89), subkeys.drop(50))
    }

    @Test
    fun wrappedTextComesBackAndIsNotInTheClear() {
        val texts = listOf(
            "Player".toByteArray(),
            "FAKE-NOT-A-KEY-FAKE-NOT-A".toByteArray(),
            ByteArray(63) { 'x'.code.toByte() },
            ByteArray(64) { 'y'.code.toByte() },
            ByteArray(1100) { 'z'.code.toByte() },
        )
        for (text in texts) {
            val blob = Keystore.wrap(text)
            assertEquals(Keystore.wrappedLength(text.size), blob.size)
            assertEquals(0, (blob.size - 8) % 64)
            if (text.size > 4) {
                assertFalse(String(blob, Charsets.ISO_8859_1).contains(String(text, Charsets.ISO_8859_1)))
            }
            val back = unwrap(blob)
            assertArrayEquals(text, back.copyOfRange(0, text.size))
            assertEquals(0, back[text.size].toInt())
        }
        assertArrayEquals(Keystore.wrap("Player".toByteArray()), Keystore.wrap("Player".toByteArray()))
    }

    private fun openingStream(): ByteArray {
        val stream = Keystore.scheduleSeed()
        val seed = Keystore.Hash()
        seed.block(Keystore.tile(Keystore.password()))
        val digest = seed.digest()
        for (i in stream.indices) stream[i] = (stream[i].toInt() xor digest[i % 20].toInt()).toByte()
        return stream
    }

    private fun inverse(x: Int): Int {
        if (x <= 1) return x
        var t0 = 1
        var t1 = 0x10001 / x
        var v = x
        var y = 0x10001 % x
        if (y == 1) return (1 - t1) and 0xffff
        while (y != 1) {
            var q = v / y
            v %= y
            t0 += q * t1
            if (v == 1) return t0 and 0xffff
            q = y / v
            y %= v
            t1 += q * t0
        }
        return (1 - t1) and 0xffff
    }

    private fun neg(x: Int) = (-x) and 0xffff

    private fun decryptSchedule(key: ByteArray): List<Int> {
        val e = Keystore.encryptSchedule(key)
        val d = IntArray(52)
        var p = 0
        var q = 52
        d[p] = inverse(e[q - 4]); d[p + 1] = neg(e[q - 3]); d[p + 2] = neg(e[q - 2]); d[p + 3] = inverse(e[q - 1])
        p += 4; q -= 4
        repeat(7) {
            d[p] = e[q - 2]; d[p + 1] = e[q - 1]
            p += 2; q -= 2
            d[p] = inverse(e[q - 4]); d[p + 1] = neg(e[q - 2]); d[p + 2] = neg(e[q - 3]); d[p + 3] = inverse(e[q - 1])
            p += 4; q -= 4
        }
        d[p] = e[q - 2]; d[p + 1] = e[q - 1]
        p += 2; q -= 2
        d[p] = inverse(e[q - 4]); d[p + 1] = neg(e[q - 3]); d[p + 2] = neg(e[q - 2]); d[p + 3] = inverse(e[q - 1])
        return d.toList()
    }

    /** The game's read path, to prove wrap can be read back. */
    private fun unwrap(blob: ByteArray): ByteArray {
        val body = blob.size - 8
        val stream = openingStream()
        val subkeys = decryptSchedule(stream.copyOfRange(0, 16)).toIntArray()
        val hash = Keystore.Hash()
        hash.block(stream.copyOfRange(48, 112))
        val out = blob.copyOfRange(0, body)
        for (number in 0 until body / 64) {
            val at = number * 64
            val digest = hash.digest()
            if (number and 7 == 0) {
                for (chunk in 0 until 8) {
                    val start = at + chunk * 8
                    Keystore.idea(subkeys, out.copyOfRange(start, start + 8)).copyInto(out, start)
                }
            }
            for (i in 0 until 64) out[at + i] = (out[at + i] - digest[(63 - i) % 20]).toByte()
            if (number and 0xf == 0) hash.block(out.copyOfRange(at, at + 64))
        }
        assertEquals(0, blob[body + 4].toInt())
        assertArrayEquals(hash.digest().copyOfRange(0, 4), blob.copyOfRange(body, body + 4))
        return out.copyOfRange(0, body - 64 + (blob[body + 5].toInt() and 0xff))
    }
}
