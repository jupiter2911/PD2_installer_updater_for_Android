package com.pd2updater.install

import com.pd2updater.install.MpqTestSupport.Member
import com.pd2updater.install.MpqTestSupport.build
import com.pd2updater.install.MpqTestSupport.payload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class MpqTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun fileOf(bytes: ByteArray): File = temp.newFile().apply { writeBytes(bytes) }

    @Test
    fun hashAndCipherMatchTheFormat() {
        assertEquals(0xc3af3770.toInt(), Mpq.hash("(hash table)", 3))
        assertEquals(0xec83b3a3.toInt(), Mpq.hash("(block table)", 3))
        assertEquals(Mpq.hash("data\\global\\x.wav", 1), Mpq.hash("DATA/GLOBAL/X.WAV", 1))
        val original = intArrayOf(1, 2, 3, 0xdeadbeef.toInt())
        val words = original.copyOf()
        Mpq.cipher(words, 0x12345678, false)
        assertFalse(words.contentEquals(original))
        Mpq.cipher(words, 0x12345678, true)
        assertArrayEquals(original, words)
    }

    @Test
    fun storedAndZlibMembersAreRead() {
        val stored = payload(10_000, 1)
        val packed = payload(20_000, 2)
        val file = fileOf(build(listOf(Member("Common\\a.mpq", stored), Member("PC\\b.dll", packed, true)), 16))
        Mpq.open(file).use { archive ->
            assertArrayEquals(stored, archive.member("common\\A.MPQ"))
            assertArrayEquals(packed, archive.member("PC\\b.dll"))
            assertEquals(20_000L, archive.size("PC\\b.dll"))
            val seen = ArrayList<Long>()
            val copied = ByteArrayOutputStream()
            archive.copyMember("PC\\b.dll", copied) { seen.add(it) }
            assertArrayEquals(packed, copied.toByteArray())
            assertEquals(20_000L, seen.last())
            val missing = assertThrows(MpqException::class.java) { archive.member("missing") }
            assertEquals(MpqProblem.MISSING, missing.problem)
        }
    }

    @Test
    fun addedMembersReplaceByNameAndKeepTheOthers() {
        val first = payload(3_000, 3)
        val second = payload(5_000, 4)
        val file = fileOf(build(listOf(Member("a.dll", first), Member("b.dll", second, true)), 8))
        val new = payload(1_234, 5)
        val key = payload(72, 6)
        Mpq.open(file, writable = true).use {
            it.add(listOf(NewMember("a.dll", new), NewMember("data\\global\\sfx\\cursor\\wavindx.wav", key)))
        }
        Mpq.open(file).use {
            assertArrayEquals(new, it.member("a.dll"))
            assertArrayEquals(second, it.member("b.dll"))
            assertArrayEquals(key, it.member("DATA\\GLOBAL\\SFX\\CURSOR\\WAVINDX.WAV"))
        }
        Mpq.open(file, writable = true).use { it.add(listOf(NewMember("a.dll", first))) }
        Mpq.open(file).use {
            assertArrayEquals(first, it.member("a.dll"))
            assertEquals(3, it.usedHashEntries())
        }
    }

    @Test
    fun aStopBeforeTheHeaderLeavesTheOldArchive() {
        val first = payload(3_000, 7)
        val bytes = build(listOf(Member("a.dll", first)), 8)
        val file = fileOf(bytes)
        Mpq.open(file, writable = true).use { it.add(listOf(NewMember("b.dll", payload(100, 8)))) }
        val stopped = file.readBytes()
        bytes.copyInto(stopped, 0, 0, 32)
        Mpq.open(fileOf(stopped)).use {
            assertArrayEquals(first, it.member("a.dll"))
            assertFalse(it.contains("b.dll"))
        }
        Mpq.open(file).use { assertTrue(it.contains("b.dll")) }
    }

    @Test
    fun aFullTableAndUnsupportedMembersFailClosed() {
        val data = payload(10, 9)
        val bytes = build(listOf(Member("a", data), Member("b", data)), 2)
        val file = fileOf(bytes)
        Mpq.open(file, writable = true).use { archive ->
            val new = listOf(NewMember("c", data))
            assertEquals(MpqProblem.FULL, assertThrows(MpqException::class.java) { archive.checkAdd(new) }.problem)
            assertEquals(MpqProblem.FULL, assertThrows(MpqException::class.java) { archive.add(new) }.problem)
        }
        assertArrayEquals(bytes, file.readBytes())

        val broken = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
        val format = assertThrows(MpqException::class.java) { Mpq.open(fileOf(broken)) }
        assertEquals(MpqProblem.FORMAT, format.problem)
        assertThrows(MpqException::class.java) { Mpq.open(temp.newFile()) }

        val unsupported = assertThrows(MpqException::class.java) { Mpq.inflate(byteArrayOf(0x08, 1, 2), 10) }
        assertEquals(MpqProblem.UNSUPPORTED, unsupported.problem)
    }
}
