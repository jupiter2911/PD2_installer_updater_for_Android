package com.pd2updater.install

import com.pd2updater.Cancelled
import com.pd2updater.install.TorrentTestSupport.FAKE_COOKIE
import com.pd2updater.install.TorrentTestSupport.payload
import com.pd2updater.install.TorrentTestSupport.torrentBytes
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PieceFetcherTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val pieceLength = 256
    private val data = payload(1000)
    private val files = listOf("keep/one" to 300, "skip/mac" to 450, "two" to 250)

    private lateinit var server: HttpServer
    private val requests = Collections.synchronizedList(ArrayList<String>())
    private val cookies = Collections.synchronizedSet(HashSet<String>())

    /** What the server answers for a request; null serves the piece. */
    @Volatile
    private var answer: (piece: Int, query: String?) -> Int? = { _, _ -> null }

    @Volatile
    private var corruptOnce = AtomicBoolean(false)

    private val limits = PieceFetcher.Limits(
        connectMillis = 2_000,
        readMillis = 2_000,
        pieceMillis = 5_000,
        retries = 2,
        backoffMillis = 1,
        extraRounds = 1,
        roundPauseMillis = 1,
        giveUpFailures = 3,
        expiredRefusals = 3,
    )

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/p/") { exchange ->
            val piece = exchange.requestURI.path.substringAfterLast('/').toInt()
            requests.add(exchange.requestURI.toString())
            exchange.requestHeaders.getFirst("Cookie")?.let { cookies.add(it) }
            val status = answer(piece, exchange.requestURI.query)
            if (status != null) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                val from = piece * pieceLength
                val body = data.copyOfRange(from, minOf(from + pieceLength, data.size))
                if (corruptOnce.compareAndSet(true, false)) body[0] = (body[0] + 1).toByte()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}/p"

    private fun torrent() = Torrent.fromStub(torrentBytes("n", base, pieceLength, files, data))

    private fun cookie() = AccessCookie.find(FAKE_COOKIE.toByteArray())!!

    private fun fetcher(cancel: AtomicBoolean = AtomicBoolean(false), connections: Int = 2) =
        PieceFetcher(limits, connections, cancel)

    private fun openPayload(folder: File, fresh: Boolean = true): List<PayloadFile?> {
        val torrent = torrent()
        return torrent.files.map { file ->
            if (file.path.startsWith("skip/")) {
                null
            } else {
                val target = File(folder, file.path.replace('/', '_'))
                PayloadFile.open(target, file.length, fresh)
            }
        }
    }

    private fun downloaded(folder: File) = Pair(File(folder, "keep_one").readBytes(), File(folder, "two").readBytes())

    @Test
    fun allNeededPiecesArriveCheckedAndWithTheCookie() {
        val folder = temp.newFolder()
        val seen = Collections.synchronizedList(ArrayList<Long>())
        val files = openPayload(folder)
        val outcome = fetcher().fetch(Download(torrent(), cookie(), base, files)) { done, total ->
            seen.add(done)
            assertEquals(1000L, total)
        }
        files.forEach { it?.close() }
        assertEquals(FetchOutcome.COMPLETE, outcome)
        val (one, two) = downloaded(folder)
        assertArrayEquals(data.copyOfRange(0, 300), one)
        assertArrayEquals(data.copyOfRange(750, 1000), two)
        assertEquals(setOf(FAKE_COOKIE), cookies.toSet())
        assertEquals(1000L, seen.max())
    }

    @Test
    fun aPieceOnlyInSkippedFilesIsNotFetched() {
        val longer = listOf("keep" to 256, "skip/mac" to 512, "two" to 232)
        val torrent = Torrent.fromStub(torrentBytes("n", base, pieceLength, longer, data))
        val folder = temp.newFolder()
        val out = torrent.files.map { file ->
            if (file.path.startsWith("skip/")) null else PayloadFile.open(File(folder, file.path), file.length, true)
        }
        assertEquals(FetchOutcome.COMPLETE, fetcher().fetch(Download(torrent, cookie(), base, out)) { _, _ -> })
        out.forEach { it?.close() }
        assertEquals(listOf("/p/0", "/p/3"), requests.sorted())
    }

    @Test
    fun aBadPieceIsAskedAgainWithACacheBuster() {
        corruptOnce.set(true)
        val folder = temp.newFolder()
        val files = openPayload(folder)
        val outcome = fetcher(connections = 1).fetch(Download(torrent(), cookie(), base, files)) { _, _ -> }
        files.forEach { it?.close() }
        assertEquals(FetchOutcome.COMPLETE, outcome)
        assertTrue(requests.toString(), requests.any { it.contains("?") })
        assertArrayEquals(data.copyOfRange(0, 300), downloaded(folder).first)
    }

    @Test
    fun aRunResumesFromWhatIsOnDisk() {
        val folder = temp.newFolder()
        val first = openPayload(folder)
        answer = { piece, _ -> if (piece == 3) 500 else null }
        val partial = fetcher().fetch(Download(torrent(), cookie(), base, first)) { _, _ -> }
        first.forEach { it?.close() }
        assertEquals(FetchOutcome.INCOMPLETE, partial)

        requests.clear()
        answer = { _, _ -> null }
        val second = openPayload(folder, fresh = false)
        val seen = Collections.synchronizedList(ArrayList<Long>())
        val outcome = fetcher().fetch(Download(torrent(), cookie(), base, second)) { done, _ -> seen.add(done) }
        second.forEach { it?.close() }
        assertEquals(FetchOutcome.COMPLETE, outcome)
        // Piece 0 lies wholly in a kept file, so it stays; pieces 1 and 2 touch the
        // skipped file and cannot be read back; piece 3 was missing.
        assertFalse(requests.toString(), requests.any { it.substringBefore('?') == "/p/0" })
        assertTrue(requests.toString(), requests.any { it.substringBefore('?') == "/p/3" })
        assertArrayEquals(data.copyOfRange(750, 1000), downloaded(folder).second)
    }

    @Test
    fun allRefusedWithNothingFetchedIsARefusal() {
        answer = { _, _ -> 403 }
        val files = openPayload(temp.newFolder())
        val outcome = fetcher().fetch(Download(torrent(), cookie(), base, files)) { _, _ -> }
        files.forEach { it?.close() }
        assertEquals(FetchOutcome.REFUSED, outcome)
    }

    @Test
    fun refusalsAfterProgressMeanTheCookieExpired() {
        val many = payload(256 * 20)
        val single = listOf("all" to many.size)
        answer = { piece, _ -> if (piece >= 2) 403 else null }
        val server2 = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server2.createContext("/q/") { exchange ->
            val piece = exchange.requestURI.path.substringAfterLast('/').toInt()
            if (piece >= 2) {
                exchange.sendResponseHeaders(403, -1)
            } else {
                val body = many.copyOfRange(piece * 256, piece * 256 + 256)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
            exchange.close()
        }
        server2.start()
        try {
            val url = "http://127.0.0.1:${server2.address.port}/q"
            val torrent = Torrent.fromStub(torrentBytes("n", url, 256, single, many))
            val out = listOf(PayloadFile.open(File(temp.newFolder(), "all"), many.size.toLong(), true))
            val outcome = fetcher(connections = 1).fetch(Download(torrent, cookie(), url, out)) { _, _ -> }
            out.forEach { it?.close() }
            assertEquals(FetchOutcome.EXPIRED, outcome)
        } finally {
            server2.stop(0)
        }
    }

    @Test
    fun aStopThrowsAndKeepsTheFiles() {
        val cancel = AtomicBoolean(false)
        val count = AtomicInteger()
        val files = openPayload(temp.newFolder())
        assertThrows(Cancelled::class.java) {
            fetcher(cancel).fetch(Download(torrent(), cookie(), base, files)) { _, _ ->
                if (count.incrementAndGet() >= 1) cancel.set(true)
            }
        }
        files.forEach { it?.close() }
    }

    /** A server that sends every piece request to [location] of the piece. */
    private fun redirecting(location: (Int) -> String): HttpServer {
        val redirect = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        redirect.createContext("/r/") { exchange ->
            val piece = exchange.requestURI.path.substringAfterLast('/').toInt()
            exchange.responseHeaders.add("Location", location(piece))
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        redirect.start()
        return redirect
    }

    @Test
    fun aRedirectIsFollowedOnThePieceHostOnly() {
        val same = redirecting { "$base/$it" }
        try {
            val url = "http://127.0.0.1:${same.address.port}/r"
            val torrent = Torrent.fromStub(torrentBytes("n", url, pieceLength, files, data))
            val out = openPayload(temp.newFolder())
            assertEquals(FetchOutcome.COMPLETE, fetcher().fetch(Download(torrent, cookie(), url, out)) { _, _ -> })
            out.forEach { it?.close() }
        } finally {
            same.stop(0)
        }
        requests.clear()
        val other = redirecting { "http://localhost:${server.address.port}/p/$it" }
        try {
            val url = "http://127.0.0.1:${other.address.port}/r"
            val torrent = Torrent.fromStub(torrentBytes("n", url, pieceLength, files, data))
            val out = openPayload(temp.newFolder())
            assertEquals(FetchOutcome.INCOMPLETE, fetcher().fetch(Download(torrent, cookie(), url, out)) { _, _ -> })
            out.forEach { it?.close() }
            assertTrue(requests.toString(), requests.isEmpty())
        } finally {
            other.stop(0)
        }
    }

    @Test
    fun aDiskErrorIsAStoreFailure() {
        val folder = temp.newFolder()
        val out = openPayload(folder)
        out.forEach { it?.close() } // A closed file cannot be written.
        assertEquals(
            FetchOutcome.STORE_FAILED,
            fetcher().fetch(Download(torrent(), cookie(), base, out)) { _, _ -> },
        )
    }
}
