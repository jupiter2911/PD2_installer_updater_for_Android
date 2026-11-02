package com.pd2updater.install

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Builds synthetic downloader wrappers for tests. */
object TorrentTestSupport {
    const val FAKE_COOKIE = "fake_cookie=expires=1900000000~access=/applications/x/*~md5=0123456789abcdef0123456789ABCDEF"

    fun text(value: ByteArray): ByteArray = "${value.size}:".toByteArray() + value

    fun text(value: String): ByteArray = text(value.toByteArray())

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    fun torrentBytes(
        name: String,
        base: String,
        pieceLength: Int,
        files: List<Pair<String, Int>>,
        payload: ByteArray,
    ): ByteArray {
        check(files.sumOf { it.second } == payload.size)
        val pieces = ByteArrayOutputStream()
        var from = 0
        while (from < payload.size) {
            pieces.write(sha1(payload.copyOfRange(from, minOf(from + pieceLength, payload.size))))
            from += pieceLength
        }
        val out = ByteArrayOutputStream()
        out.write("d8:announce".toByteArray())
        out.write(text("http://tracker.invalid:3724/announce"))
        out.write("15:direct download".toByteArray())
        out.write(text(base))
        out.write("11:disable p2pi1e4:info".toByteArray())
        out.write("d5:filesl".toByteArray())
        for ((path, length) in files) {
            out.write("d6:lengthi${length}e4:pathl".toByteArray())
            for (part in path.split('/')) out.write(text(part))
            out.write("ee".toByteArray())
        }
        out.write("e4:name".toByteArray())
        out.write(text(name))
        out.write("12:piece lengthi${pieceLength}e6:pieces".toByteArray())
        out.write(text(pieces.toByteArray()))
        out.write('e'.code)
        out.write('e'.code)
        return out.toByteArray()
    }

    private fun latin(value: String) = value.toByteArray(Charsets.ISO_8859_1)

    /** Program bytes, a false start, the torrent, the cookie between two binary tags. */
    fun stubBytes(torrent: ByteArray, cookie: String = FAKE_COOKIE): ByteArray =
        latin("MZ\u0090\u0000 not a torrent: d8:announce 3:abc") + ByteArray(64) + torrent +
            latin("TAGA\u0013\u0018þÿ") + cookie.toByteArray() + latin("TAGB\u0097ÿÿÿ")

    fun payload(length: Int): ByteArray = ByteArray(length) { (it * 7 % 251).toByte() }
}
