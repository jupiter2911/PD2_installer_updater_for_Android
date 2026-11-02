package com.pd2updater.install

/**
 * How Diablo II keeps the CD key and the owner name: each is an MPQ member
 * holding the text in Blizzard's own wrapper (64-byte blocks, IDEA every eighth
 * block, a running SHA-1 variant). Fixed seeds: same text, same bytes.
 * Ported from pd2-launcher `blizzard/keystore.rs`.
 */
object Keystore {
    const val BASE_KEY_MEMBER = "data\\global\\sfx\\cursor\\wavindx.wav"
    const val EXPANSION_KEY_MEMBER = "data\\global\\chars\\am\\cof\\amblxbow.cof"
    const val OWNER_MEMBER = "data\\global\\sfx\\cursor\\curindx.wav"
    const val OWNER = "Player"

    private const val TRAILER = 8
    private const val BLOCK = 64

    /** The Microsoft C runtime's rand: its values are part of the format. */
    private class Rand(private var state: Int) {
        fun nextByte(): Byte {
            state = state * 214013 + 2531011
            return ((state ushr 16) and 0x7fff).toByte()
        }
    }

    internal fun password(): ByteArray {
        val rand = Rand(0x150b)
        val out = ByteArray(19)
        var filled = 0
        while (filled < out.size) {
            val byte = rand.nextByte()
            if (byte.toInt() != 0) out[filled++] = byte
        }
        return out
    }

    internal fun scheduleSeed(): ByteArray {
        val rand = Rand(0x4fa7)
        return ByteArray(112) { rand.nextByte() }
    }

    /** The password tiled across 64 bytes, restarting at the first NUL. */
    internal fun tile(password: ByteArray): ByteArray {
        var at = 0
        return ByteArray(BLOCK) {
            if (at >= password.size || password[at].toInt() == 0) at = 0
            password[at++]
        }
    }

    /** A left shift ORed with an arithmetic right shift, as the game does. */
    internal fun smear(value: Int, left: Int): Int = (value shl left) or (value shr (32 - left))

    /** SHA-1's rounds without the expansion's rotation; no padding. */
    internal class Hash {
        private val state = intArrayOf(0x67452301, 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476, 0xc3d2e1f0.toInt())

        fun block(data: ByteArray) {
            val w = IntArray(80)
            for (i in 0 until 16) w[i] = littleInt(data, i * 4)
            for (i in 16 until 80) w[i] = w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]
            var a = state[0]
            var b = state[1]
            var c = state[2]
            var d = state[3]
            var e = state[4]
            for (i in 0 until 80) {
                val f: Int
                val k: Int
                when (i / 20) {
                    0 -> { f = (b and c) or (b.inv() and d); k = 0x5a827999 }
                    1 -> { f = b xor c xor d; k = 0x6ed9eba1 }
                    2 -> { f = (b and c) or (b and d) or (c and d); k = 0x8f1bbcdc.toInt() }
                    else -> { f = b xor c xor d; k = 0xca62c1d6.toInt() }
                }
                val t = smear(a, 5) + f + e + k + w[i]
                e = d
                d = c
                c = smear(b, 30)
                b = a
                a = t
            }
            state[0] += a
            state[1] += b
            state[2] += c
            state[3] += d
            state[4] += e
            w.fill(0)
        }

        fun digest(): ByteArray {
            val out = ByteArray(20)
            for (i in 0 until 5) {
                val value = state[i]
                out[i * 4] = value.toByte()
                out[i * 4 + 1] = (value ushr 8).toByte()
                out[i * 4 + 2] = (value ushr 16).toByte()
                out[i * 4 + 3] = (value ushr 24).toByte()
            }
            return out
        }
    }

    private fun littleInt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xff) or
            ((data[at + 1].toInt() and 0xff) shl 8) or
            ((data[at + 2].toInt() and 0xff) shl 16) or
            ((data[at + 3].toInt() and 0xff) shl 24)

    /** Multiplication modulo 2^16+1, a stored zero meaning 2^16. */
    private fun mul(a: Int, b: Int): Int {
        if (a == 0) return (1 - b) and 0xffff
        if (b == 0) return (1 - a) and 0xffff
        val product = a * b
        val low = product and 0xffff
        val high = product ushr 16
        return (low - high + (if (low < high) 1 else 0)) and 0xffff
    }

    /** IDEA's schedule, words little-endian; values are 16-bit in Ints. */
    internal fun encryptSchedule(key: ByteArray): IntArray {
        val subkeys = IntArray(52)
        for (i in 0 until 8) {
            subkeys[i] = (key[i * 2].toInt() and 0xff) or ((key[i * 2 + 1].toInt() and 0xff) shl 8)
        }
        for (i in 8 until 52) {
            val base = (i / 8 - 1) * 8
            val a = subkeys[base + (i + 1) % 8]
            val b = subkeys[base + (i + 2) % 8]
            subkeys[i] = ((a shl 9) or (b ushr 7)) and 0xffff
        }
        return subkeys
    }

    /** One IDEA block, words little-endian. */
    internal fun idea(subkeys: IntArray, input: ByteArray): ByteArray {
        fun word(i: Int) = (input[i * 2].toInt() and 0xff) or ((input[i * 2 + 1].toInt() and 0xff) shl 8)
        var x0 = word(0)
        var x1 = word(1)
        var x2 = word(2)
        var x3 = word(3)
        for (round in 0 until 8) {
            val r = round * 6
            x0 = mul(x0, subkeys[r])
            x1 = (x1 + subkeys[r + 1]) and 0xffff
            x2 = (x2 + subkeys[r + 2]) and 0xffff
            x3 = mul(x3, subkeys[r + 3])
            val t0 = mul(x0 xor x2, subkeys[r + 4])
            val t1 = mul((t0 + (x1 xor x3)) and 0xffff, subkeys[r + 5])
            val t2 = (t0 + t1) and 0xffff
            x0 = x0 xor t1
            x3 = x3 xor t2
            val swap = x1 xor t2
            x1 = x2 xor t1
            x2 = swap
        }
        val words = intArrayOf(
            mul(x0, subkeys[48]),
            (x2 + subkeys[49]) and 0xffff,
            (x1 + subkeys[50]) and 0xffff,
            mul(x3, subkeys[51]),
        )
        val out = ByteArray(8)
        for (i in 0 until 4) {
            out[i * 2] = words[i].toByte()
            out[i * 2 + 1] = (words[i] ushr 8).toByte()
        }
        return out
    }

    /** The first IDEA schedule and the opening hash, from the password. */
    internal fun schedule(password: ByteArray): Pair<IntArray, Hash> {
        val stream = scheduleSeed()
        val seed = Hash()
        seed.block(tile(password))
        val digest = seed.digest()
        for (i in stream.indices) stream[i] = (stream[i].toInt() xor digest[i % 20].toInt()).toByte()
        val hash = Hash()
        hash.block(stream.copyOfRange(48, 112))
        return encryptSchedule(stream.copyOfRange(0, 16)) to hash
    }

    /** The wrapper's size: the text and its NUL in whole blocks, and the trailer. */
    fun wrappedLength(textLength: Int): Int {
        val length = textLength + 1
        return if (length % BLOCK == 0) length + TRAILER else length + (BLOCK + TRAILER - length % BLOCK)
    }

    /** Wrap text (a key or the owner name) as the installer does. */
    fun wrap(text: ByteArray): ByteArray {
        val out = ByteArray(wrappedLength(text.size))
        text.copyInto(out)
        val body = out.size - TRAILER
        val (subkeys, hash) = schedule(password())
        for (number in 0 until body / BLOCK) {
            val at = number * BLOCK
            val digest = hash.digest()
            val plain = out.copyOfRange(at, at + BLOCK)
            for (i in 0 until BLOCK) out[at + i] = (out[at + i] + digest[(63 - i) % 20]).toByte()
            if (number and 7 == 0) {
                for (chunk in 0 until 8) {
                    val start = at + chunk * 8
                    idea(subkeys, out.copyOfRange(start, start + 8)).copyInto(out, start)
                }
            }
            if (number and 0xf == 0) hash.block(plain)
            plain.fill(0)
        }
        hash.digest().copyInto(out, body, 0, 4)
        out[body + 4] = 0
        out[body + 5] = (text.size + 1 - (body / BLOCK - 1) * BLOCK).toByte()
        return out
    }
}
