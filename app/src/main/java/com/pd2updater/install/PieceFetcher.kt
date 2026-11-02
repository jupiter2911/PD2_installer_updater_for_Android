package com.pd2updater.install

import com.pd2updater.Cancelled
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** How one product's download ended. */
enum class FetchOutcome {
    /** Every needed piece is on disk and checked. */
    COMPLETE,

    /** Nothing arrived and the host refused (403). */
    REFUSED,

    /** Pieces arrived, then the host refused the rest: the cookie expired. */
    EXPIRED,

    /** Pieces are still missing after every round: the connection. */
    INCOMPLETE,

    /** A piece could not be written: the disk. */
    STORE_FAILED,
}

/** A kept file of the payload; written from several threads at their own offsets. */
class PayloadFile private constructor(private val channel: FileChannel, val fresh: Boolean) : Closeable {
    fun write(data: ByteArray, from: Int, length: Int, at: Long) {
        val buffer = ByteBuffer.wrap(data, from, length)
        var position = at
        while (buffer.hasRemaining()) position += channel.write(buffer, position)
    }

    /** Read exactly [length] bytes, or false when the file has fewer. */
    fun read(data: ByteArray, from: Int, length: Int, at: Long): Boolean {
        val buffer = ByteBuffer.wrap(data, from, length)
        var position = at
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, position)
            if (count < 0) return false
            position += count
        }
        return true
    }

    fun sync() = channel.force(false)

    override fun close() = channel.close()

    companion object {
        /** Open at its full length; [fresh] says no earlier bytes are worth reading back. */
        fun open(file: File, length: Long, fresh: Boolean): PayloadFile {
            file.parentFile?.mkdirs()
            val access = RandomAccessFile(file, "rw")
            try {
                if (access.length() != length) access.setLength(length)
            } catch (error: IOException) {
                access.close()
                throw error
            }
            return PayloadFile(access.channel, fresh)
        }
    }
}

/** One product to download; [files] follows the torrent's order, null for a file not kept. */
class Download(val torrent: Torrent, val cookie: AccessCookie, val base: String, val files: List<PayloadFile?>) {
    fun needed(piece: Int): Boolean = torrent.spans(piece).any { files[it.file] != null }

    fun write(piece: Int, data: ByteArray, length: Int) {
        var at = 0
        for (span in torrent.spans(piece)) {
            val size = span.length.toInt()
            files[span.file]?.write(data, at, size, span.offset)
            at += size
        }
        check(at == length)
    }

    /** The piece as an earlier run left it, when all its bytes are kept. */
    fun readBack(piece: Int, data: ByteArray): Boolean {
        var at = 0
        for (span in torrent.spans(piece)) {
            val file = files[span.file] ?: return false
            if (file.fresh) return false
            val size = span.length.toInt()
            if (!file.read(data, at, size, span.offset)) return false
            at += size
        }
        return true
    }
}

/**
 * Fetches pieces over HTTP from the one piece host: `<base>/<n>` with the access
 * cookie, each checked by SHA-1 before it is written. A fixed number of
 * connections, retries with a cache-buster, rounds for what failed. What an
 * earlier run left on disk and checks is kept.
 * Ported from pd2-launcher `blizzard/fetch.rs`, without its adaptive connection count.
 */
class PieceFetcher(
    private val limits: Limits = Limits(),
    private val connections: Int = 16,
    private val cancel: AtomicBoolean = AtomicBoolean(false),
) {
    class Limits(
        val connectMillis: Int = 10_000,
        val readMillis: Int = 20_000,
        val pieceMillis: Long = 60_000,
        val retries: Int = 3,
        val backoffMillis: Long = 250,
        val extraRounds: Int = 3,
        val roundPauseMillis: Long = 2_000,
        val giveUpFailures: Int = 8,
        val expiredRefusals: Int = 8,
    )

    private class Refused : IOException("403")

    private class Run(val download: Download, val total: Long, val report: (Long, Long) -> Unit) {
        val done = AtomicLong()
        val fetched = AtomicLong()
        val failures = AtomicInteger()
        val refusalsInARow = AtomicInteger()
        val sawRefusal = AtomicBoolean(false)
        val storeFailed = AtomicBoolean(false)

        @Volatile
        var outcome: FetchOutcome? = null
    }

    /** Download what [download] needs; throws [Cancelled] when stopped, keeping the files. */
    fun fetch(download: Download, report: (done: Long, total: Long) -> Unit): FetchOutcome {
        val torrent = download.torrent
        var pending = (0 until torrent.pieceCount).filter { download.needed(it) }
        val run = Run(download, pending.sumOf { torrent.pieceSize(it) }, report)
        var round = 0
        while (true) {
            pending = runRound(run, pending, resume = round == 0)
            if (cancel.get()) throw Cancelled()
            run.outcome?.let { return it }
            if (run.storeFailed.get()) return FetchOutcome.STORE_FAILED
            if (pending.isEmpty()) return FetchOutcome.COMPLETE
            if (round >= limits.extraRounds) return FetchOutcome.INCOMPLETE
            round += 1
            pause(limits.roundPauseMillis * round)
        }
    }

    /** One pass over [pieces] with the pool; the pieces still missing. */
    private fun runRound(run: Run, pieces: List<Int>, resume: Boolean): List<Int> {
        val queue = ConcurrentLinkedQueue(pieces)
        val failed = ConcurrentLinkedQueue<Int>()
        val workers = List(connections.coerceAtMost(pieces.size.coerceAtLeast(1))) {
            Thread {
                val data = ByteArray(run.download.torrent.pieceLength.toInt() + 1)
                while (!cancel.get() && !run.storeFailed.get() && run.outcome == null) {
                    val piece = queue.poll() ?: break
                    try {
                        if (!piece(run, piece, data, resume)) failed.add(piece)
                    } catch (_: Cancelled) {
                        break
                    } catch (_: IOException) {
                        run.storeFailed.set(true)
                    }
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }
        return failed.toList()
    }

    /** Get one piece: true when it is on disk and checked. IOException means the disk. */
    private fun piece(run: Run, piece: Int, data: ByteArray, resume: Boolean): Boolean {
        val download = run.download
        val torrent = download.torrent
        val size = torrent.pieceSize(piece)
        if (resume) {
            val intact = try {
                download.readBack(piece, data) && torrent.verify(piece, data, size.toInt())
            } catch (_: IOException) {
                false
            }
            if (intact) {
                progress(run, size)
                return true
            }
        }
        var refused = false
        for (attempt in 0..limits.retries) {
            if (cancel.get()) throw Cancelled()
            if (attempt > 0) pause(limits.backoffMillis * attempt)
            val url = if (attempt == 0) "${download.base}/$piece" else "${download.base}/$piece?${System.nanoTime()}"
            refused = false
            val length = try {
                get(url, download.cookie, data)
            } catch (_: Refused) {
                refused = true
                continue
            } catch (error: IOException) {
                continue
            }
            if (length < 0 || !torrent.verify(piece, data, length)) continue
            download.write(piece, data, length)
            run.fetched.addAndGet(size)
            run.refusalsInARow.set(0)
            progress(run, size)
            return true
        }
        failed(run, refused)
        return false
    }

    private fun failed(run: Run, refused: Boolean) {
        val failures = run.failures.incrementAndGet()
        if (refused) run.sawRefusal.set(true)
        val fetchedNone = run.fetched.get() == 0L
        val inARow = if (refused) run.refusalsInARow.incrementAndGet() else run.refusalsInARow.getAndSet(0).let { 0 }
        when {
            fetchedNone && failures >= limits.giveUpFailures ->
                run.outcome = if (run.sawRefusal.get()) FetchOutcome.REFUSED else FetchOutcome.INCOMPLETE
            !fetchedNone && inARow >= limits.expiredRefusals -> run.outcome = FetchOutcome.EXPIRED
        }
    }

    private fun progress(run: Run, size: Long) {
        run.report(run.done.addAndGet(size), run.total)
    }

    /** The piece's body into [data]; its length, or -1 when longer than a piece. */
    private fun get(url: String, cookie: AccessCookie, data: ByteArray): Int {
        val deadline = System.currentTimeMillis() + limits.pieceMillis
        val allowedHost = URL(url).host
        var target = URL(url)
        var redirects = 0
        while (true) {
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = limits.connectMillis
                connection.readTimeout = limits.readMillis
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Cookie", cookie.headerValue)
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Pragma", "no-cache")
                val status = connection.responseCode
                if (status in REDIRECTS) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("redirect")
                    val next = URL(target, location)
                    if (next.host != allowedHost || redirects >= MAX_REDIRECTS) throw IOException("redirect")
                    redirects += 1
                    target = next
                    connection.disconnect()
                    continue
                }
                if (status == 403) throw Refused()
                if (status != 200) throw IOException("HTTP $status")
                return connection.inputStream.use { input ->
                    var length = 0
                    while (true) {
                        if (cancel.get()) throw Cancelled()
                        if (System.currentTimeMillis() > deadline) throw IOException("piece timeout")
                        if (length == data.size) return@use -1
                        val count = input.read(data, length, data.size - length)
                        if (count < 0) break
                        length += count
                    }
                    length
                }
            } catch (error: IOException) {
                connection.disconnect()
                throw error
            }
        }
    }

    private fun pause(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (true) {
            if (cancel.get()) throw Cancelled()
            val left = until - System.currentTimeMillis()
            if (left <= 0) return
            Thread.sleep(minOf(left, 50))
        }
    }

    companion object {
        private const val USER_AGENT = "pd2-updater-android"
        private const val MAX_REDIRECTS = 3
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
