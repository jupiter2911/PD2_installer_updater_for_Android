package com.pd2updater.install

import java.security.MessageDigest

class TorrentException(message: String) : Exception(message)

private fun malformed() = TorrentException("malformed")

private fun find(haystack: ByteArray, from: Int, needle: ByteArray): Int {
    var at = from
    val last = haystack.size - needle.size
    while (at <= last) {
        var matched = 0
        while (matched < needle.size && haystack[at + matched] == needle[matched]) matched++
        if (matched == needle.size) return at
        at++
    }
    return -1
}

/** One bencoded value and the bytes it was read from: [start] until [end]. */
class BencodeNode(private val value: Any, val start: Int, val end: Int) {
    @Suppress("UNCHECKED_CAST")
    operator fun get(key: String): BencodeNode? {
        val entries = value as? List<*> ?: return null
        if (!isDictionary) return null
        val wanted = key.toByteArray()
        return (entries as List<Pair<ByteArray, BencodeNode>>).firstOrNull { it.first.contentEquals(wanted) }?.second
    }

    var isDictionary = false
        internal set

    fun bytes(): ByteArray? = value as? ByteArray

    fun integer(): Long? = value as? Long

    fun text(): String? = bytes()?.let { String(it, Charsets.UTF_8) }

    fun unsigned(): Long? = integer()?.takeIf { it >= 0 }

    @Suppress("UNCHECKED_CAST")
    fun list(): List<BencodeNode>? = if (isDictionary) null else value as? List<BencodeNode>
}

object Bencode {
    private const val MAX_DEPTH = 32
    private const val MAX_NODES = 1 shl 20

    /** Decode the value at [at]; its end is just past it, so a caller can hash its exact bytes. */
    fun decode(bytes: ByteArray, at: Int): BencodeNode = Decoder(bytes).decode(at, 0)

    private class Decoder(private val bytes: ByteArray) {
        private var nodes = 0

        fun decode(at: Int, depth: Int): BencodeNode {
            nodes += 1
            if (depth > MAX_DEPTH || nodes > MAX_NODES || at >= bytes.size) throw malformed()
            return when (bytes[at].toInt().toChar()) {
                'i' -> {
                    val end = find(bytes, at + 1, byteArrayOf('e'.code.toByte()))
                    if (end < 0) throw malformed()
                    val value = String(bytes, at + 1, end - at - 1, Charsets.ISO_8859_1).toLongOrNull() ?: throw malformed()
                    BencodeNode(value, at, end + 1)
                }
                'l' -> {
                    val items = ArrayList<BencodeNode>()
                    var next = at + 1
                    while (true) {
                        if (next >= bytes.size) throw malformed()
                        if (bytes[next].toInt().toChar() == 'e') break
                        val item = decode(next, depth + 1)
                        next = item.end
                        items.add(item)
                    }
                    BencodeNode(items, at, next + 1)
                }
                'd' -> {
                    val entries = ArrayList<Pair<ByteArray, BencodeNode>>()
                    var next = at + 1
                    while (true) {
                        if (next >= bytes.size) throw malformed()
                        if (bytes[next].toInt().toChar() == 'e') break
                        val key = decode(next, depth + 1)
                        val name = key.bytes() ?: throw malformed()
                        val value = decode(key.end, depth + 1)
                        next = value.end
                        entries.add(name to value)
                    }
                    BencodeNode(entries, at, next + 1).also { it.isDictionary = true }
                }
                in '0'..'9' -> {
                    val colon = find(bytes, at, byteArrayOf(':'.code.toByte()))
                    if (colon < 0) throw malformed()
                    val length = String(bytes, at, colon - at, Charsets.ISO_8859_1).toLongOrNull() ?: throw malformed()
                    val start = colon + 1
                    if (start + length > bytes.size) throw malformed()
                    val end = start + length.toInt()
                    BencodeNode(bytes.copyOfRange(start, end), at, end)
                }
                else -> throw malformed()
            }
        }
    }
}

/** One file of the payload, in torrent order; [path] joins its parts with `/`. */
data class TorrentFile(val path: String, val length: Long)

/** Where in which file a run of a piece's bytes belongs. */
data class Span(val file: Int, val offset: Long, val length: Long)

/**
 * The torrent inside Blizzard's legacy downloader wrapper. Only its metadata is
 * used: pieces come over HTTP. Ported from pd2-launcher `blizzard/torrent.rs`;
 * host groups in `direct download` are not expanded, since the pin takes one exact URL.
 */
class Torrent private constructor(
    val name: String,
    val pieceLength: Long,
    private val pieces: ByteArray,
    val files: List<TorrentFile>,
    val total: Long,
    val infohash: ByteArray,
    val servers: List<String>,
) {
    val pieceCount: Int get() = pieces.size / 20

    val infohashHex: String get() = infohash.joinToString("") { "%02x".format(it) }

    fun pieceSize(index: Int): Long = minOf(maxOf(total - index * pieceLength, 0L), pieceLength)

    /** Whether [data] is piece [index]: its length, then its SHA-1. */
    fun verify(index: Int, data: ByteArray): Boolean = verify(index, data, data.size)

    fun verify(index: Int, data: ByteArray, length: Int): Boolean {
        if (length.toLong() != pieceSize(index) || index !in 0 until pieceCount) return false
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(data, 0, length)
        val hash = digest.digest()
        return (0 until 20).all { hash[it] == pieces[index * 20 + it] }
    }

    /** The runs of file bytes piece [index] covers, in order. */
    fun spans(index: Int): List<Span> {
        val spans = ArrayList<Span>(2)
        var wanted = pieceSize(index)
        var at = index * pieceLength
        var start = 0L
        for ((file, entry) in files.withIndex()) {
            if (wanted == 0L) break
            val end = start + entry.length
            if (at < end) {
                val length = minOf(wanted, end - at)
                spans.add(Span(file, at - start, length))
                at += length
                wanted -= length
            }
            start = end
        }
        return spans
    }

    companion object {
        /** Find and read the torrent a downloader wrapper carries. */
        fun fromStub(stub: ByteArray): Torrent {
            val anchor = "d8:announce".toByteArray()
            var search = 0
            while (true) {
                val at = find(stub, search, anchor)
                if (at < 0) throw TorrentException("no torrent")
                search = at + 1
                val root = try {
                    Bencode.decode(stub, at)
                } catch (_: TorrentException) {
                    continue
                }
                val info = root["info"]
                if (info == null || !info.isDictionary) continue
                return read(stub, root, info)
            }
        }

        private fun read(stub: ByteArray, root: BencodeNode, info: BencodeNode): Torrent {
            val infohash = MessageDigest.getInstance("SHA-1").digest(stub.copyOfRange(info.start, info.end))
            val name = info["name"]?.text() ?: throw malformed()
            val pieceLength = info["piece length"]?.unsigned()?.takeIf { it > 0 } ?: throw malformed()
            val pieces = info["pieces"]?.bytes() ?: throw malformed()
            if (pieces.isEmpty() || pieces.size % 20 != 0) throw malformed()
            val files = ArrayList<TorrentFile>()
            val list = info["files"]
            if (list != null) {
                for (file in list.list() ?: throw malformed()) {
                    val length = file["length"]?.unsigned() ?: throw malformed()
                    val parts = (file["path"]?.list() ?: throw malformed()).map { part ->
                        val text = part.text() ?: throw malformed()
                        if (text.isEmpty() || text == "." || text == ".." || text.any { it in "/\\\u0000" }) throw malformed()
                        text
                    }
                    if (parts.isEmpty()) throw malformed()
                    files.add(TorrentFile(parts.joinToString("/"), length))
                }
            } else {
                files.add(TorrentFile(name, info["length"]?.unsigned() ?: throw malformed()))
            }
            var total = 0L
            for (file in files) {
                total = Math.addExact(total, file.length)
            }
            if ((total + pieceLength - 1) / pieceLength != (pieces.size / 20).toLong()) throw malformed()
            val servers = ArrayList<String>()
            root["direct download"]?.let { value ->
                servers.addAll((value.text() ?: throw malformed()).split('|').filter { it.isNotEmpty() })
            }
            root["server list"]?.let { value ->
                for (entry in value.list() ?: throw malformed()) {
                    entry["url"]?.text()?.let { servers.add(it) }
                }
            }
            return Torrent(name, pieceLength, pieces, files, total, infohash, servers)
        }
    }
}

/**
 * The CDN access cookie a wrapper carries after its torrent:
 * `<name>=expires=<unix time>~access=<path glob>~md5=<32 hex>`. Never printed.
 */
class AccessCookie private constructor(val headerValue: String, val expires: Long) {
    override fun toString(): String = "AccessCookie(expires=$expires)"

    companion object {
        private const val COOKIE_CHARS = "=~/*._%-"

        private fun isCookieByte(byte: Byte): Boolean {
            val char = (byte.toInt() and 0xff).toChar()
            return char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in COOKIE_CHARS
        }

        private fun isHex(byte: Byte): Boolean {
            val char = (byte.toInt() and 0xff).toChar()
            return char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        }

        fun find(stub: ByteArray): AccessCookie? {
            val anchor = "~md5=".toByteArray()
            var search = 0
            while (true) {
                val at = find(stub, search, anchor)
                if (at < 0) return null
                search = at + 1
                val digest = at + anchor.size
                if (digest + 32 > stub.size) return null
                if (!(digest until digest + 32).all { isHex(stub[it]) }) continue
                var start = at
                while (start > 0 && isCookieByte(stub[start - 1])) start--
                val value = String(stub, start, digest + 32 - start, Charsets.ISO_8859_1)
                if (!value.contains("~access=")) continue
                val marker = value.indexOf("expires=")
                if (marker < 0) continue
                val expires = value.substring(marker + 8).takeWhile { it in '0'..'9' }.toLongOrNull() ?: continue
                return AccessCookie(value, expires)
            }
        }
    }
}
