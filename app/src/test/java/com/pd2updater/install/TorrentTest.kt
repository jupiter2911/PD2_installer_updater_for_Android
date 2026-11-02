package com.pd2updater.install

import com.pd2updater.install.TorrentTestSupport.FAKE_COOKIE
import com.pd2updater.install.TorrentTestSupport.payload
import com.pd2updater.install.TorrentTestSupport.sha1
import com.pd2updater.install.TorrentTestSupport.stubBytes
import com.pd2updater.install.TorrentTestSupport.torrentBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentTest {
    private fun malformed(bytes: ByteArray) {
        assertThrows(TorrentException::class.java) { Bencode.decode(bytes, 0) }
    }

    @Test
    fun bencodeKeepsTheSpanOfEveryValue() {
        val bytes = "xxd3:cow3:moo4:spami-42e4:listli1ei2eee".toByteArray()
        val root = Bencode.decode(bytes, 2)
        assertEquals(2, root.start)
        assertEquals(bytes.size, root.end)
        assertEquals("moo", root["cow"]!!.text())
        assertEquals(-42L, root["spam"]!!.integer())
        val list = root["list"]!!
        assertEquals(2, list.list()!!.size)
        assertEquals("li1ei2ee", String(bytes, list.start, list.end - list.start))
        for (broken in listOf("d3:cow", "i12", "5:abc", "l", "di1e1:ae", "x", "i1x2e")) {
            malformed(broken.toByteArray())
        }
        malformed(("l".repeat(34) + "e".repeat(34)).toByteArray())
        malformed(("l" + "i0e".repeat(1 shl 20) + "e").toByteArray())
        assertNotNull(Bencode.decode(("l" + "i0e".repeat(1000) + "e").toByteArray(), 0))
    }

    @Test
    fun aSyntheticWrapperGivesItsTorrentInfohashAndCookie() {
        val data = payload(1000)
        val base = "http://pieces.invalid/applications/x/enUS"
        val torrent = torrentBytes(
            "Synthetic-Installer", base, 256,
            listOf("a/one.bin" to 300, "two.bin" to 450, "Mac.app/three" to 250), data,
        )
        val stub = stubBytes(torrent)
        val read = Torrent.fromStub(stub)
        assertEquals("Synthetic-Installer", read.name)
        assertEquals(256L, read.pieceLength)
        assertEquals(4, read.pieceCount)
        assertEquals(1000L, read.total)
        assertEquals("a/one.bin", read.files[0].path)
        assertEquals(listOf(base), read.servers)

        val at = String(torrent, Charsets.ISO_8859_1).indexOf("4:infod") + 6
        val info = Bencode.decode(torrent, at)
        assertArrayEquals(sha1(torrent.copyOfRange(info.start, info.end)), read.infohash)
        assertEquals(40, read.infohashHex.length)

        for (index in 0 until 4) {
            val from = index * 256
            assertTrue(read.verify(index, data.copyOfRange(from, minOf(from + 256, 1000))))
        }
        assertFalse(read.verify(1, data.copyOfRange(0, 256)))
        assertFalse(read.verify(3, data.copyOfRange(768, 999)))

        val cookie = AccessCookie.find(stub)!!
        assertEquals(FAKE_COOKIE, cookie.headerValue)
        assertEquals(1_900_000_000L, cookie.expires)
        assertFalse(cookie.toString().contains("md5"))
        assertFalse(cookie.toString().contains("fake_cookie"))
    }

    @Test
    fun aCookieMustNameItsExpiryAndItsPath() {
        val digest = "0123456789abcdef0123456789abcdef"
        for (chance in listOf(
            "x=~md5=$digest",
            "x=expires=5~md5=$digest",
            "x=~access=/a~md5=$digest",
            "x=expires=5~access=/a~md5=0123",
            "x=expires=5~access=/a~md5=" + "z".repeat(32),
        )) {
            assertNull(chance, AccessCookie.find(chance.toByteArray()))
        }
        val stub = "\u0000~md5=" + "g".repeat(32) + "\u0000name=expires=7~access=/a/*~md5=$digest\u0000"
        assertEquals(7L, AccessCookie.find(stub.toByteArray())!!.expires)
    }

    @Test
    fun noTorrentAndABrokenTorrentAreToldApart() {
        assertEquals("no torrent", assertThrows(TorrentException::class.java) { Torrent.fromStub("MZ nothing here".toByteArray()) }.message)
        val data = payload(100)
        val good = String(torrentBytes("n", "http://h/p", 64, listOf("f" to 100), data), Charsets.ISO_8859_1)
        val at = good.indexOf("6:pieces40:")
        val fewer = good.substring(0, at) + "6:pieces20:" + good.substring(at + 11 + 20)
        assertEquals("malformed", assertThrows(TorrentException::class.java) { Torrent.fromStub(fewer.toByteArray(Charsets.ISO_8859_1)) }.message)
        val unsafe = torrentBytes("n", "http://h/p", 64, listOf("../f" to 100), data)
        assertEquals("malformed", assertThrows(TorrentException::class.java) { Torrent.fromStub(unsafe) }.message)
    }

    @Test
    fun piecesCrossFileBorders() {
        val data = payload(1000)
        val torrent = Torrent.fromStub(
            torrentBytes("n", "http://h/p", 256, listOf("one" to 300, "two" to 450, "three" to 250), data),
        )
        assertEquals(listOf(Span(0, 0, 256)), torrent.spans(0))
        assertEquals(listOf(Span(0, 256, 44), Span(1, 0, 212)), torrent.spans(1))
        assertEquals(listOf(Span(1, 212, 238), Span(2, 0, 18)), torrent.spans(2))
        assertEquals(232L, torrent.pieceSize(3))
        assertEquals(listOf(Span(2, 18, 232)), torrent.spans(3))

        val gap = Torrent.fromStub(
            torrentBytes("n", "http://h/p", 8, listOf("a" to 5, "empty" to 0, "b" to 5), data.copyOfRange(0, 10)),
        )
        assertEquals(listOf(Span(0, 0, 5), Span(2, 0, 3)), gap.spans(0))
    }

    @Test
    fun theRealProductPinsNameTheirPieceHost() {
        assertEquals("84ce44e25d34d9a292d7aad5d31e54e866c10c9c", Products.BASE_GAME.pin.infohash)
        assertEquals(6088, Products.BASE_GAME.pin.pieces)
        assertEquals(1_595_721_859L, Products.BASE_GAME.pin.total)
        assertEquals("82c051339f174d5cf9c383bbf752b9b6b1db2b54", Products.EXPANSION.pin.infohash)
        assertEquals(2039, Products.EXPANSION.pin.pieces)
        assertEquals(534_264_111L, Products.EXPANSION.pin.total)
        for (product in listOf(Products.BASE_GAME, Products.EXPANSION)) {
            assertTrue(product.pin.base.startsWith("http://${Products.PIECE_HOST}/applications/Diablo2/1.14B/"))
        }
    }

    private val files = listOf(
        "DirectX/a.cab" to 100,
        "Installer Tome.mpq" to 300,
        "Installer.app/Contents/x" to 50,
        "Installer.exe" to 70,
    )
    private val kept = listOf(
        KeptFile("DirectX/a.cab", 100, ""),
        KeptFile("Installer Tome.mpq", 300, ""),
        KeptFile("Installer.exe", 70, ""),
    )
    private val base = "http://pieces.invalid/applications/x/enUS"

    private fun synthetic(files: List<Pair<String, Int>>, url: String = base): Torrent =
        Torrent.fromStub(torrentBytes("Synthetic", url, 64, files, ByteArray(files.sumOf { it.second }) { 7 }))

    private fun pinOf(torrent: Torrent) =
        ProductPin(torrent.infohashHex, "Synthetic", 64, torrent.pieceCount, torrent.total, base)

    @Test
    fun aTorrentMustMatchItsPin() {
        val torrent = synthetic(files)
        val pin = pinOf(torrent)
        assertNull(Products.pinMismatch(torrent, pin, kept))
        assertEquals("infohash", Products.pinMismatch(torrent, pin.copy(infohash = "00"), kept))
        assertEquals("name", Products.pinMismatch(torrent, pin.copy(name = "other"), kept))
        assertEquals("pieces", Products.pinMismatch(torrent, pin.copy(pieces = 1), kept))
        assertEquals("server", Products.pinMismatch(synthetic(files, "http://evil.invalid/x"), pinOf(synthetic(files, "http://evil.invalid/x")), kept))
        assertEquals("files", Products.pinMismatch(torrent, pin, kept.drop(1)))
        assertEquals("files", Products.pinMismatch(torrent, pin, kept.map { it.copy(size = 1) }))
    }
}
