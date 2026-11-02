package com.pd2updater.install

import com.pd2updater.Cancelled
import com.pd2updater.Progress
import com.pd2updater.install.MpqTestSupport.Member
import com.pd2updater.install.MpqTestSupport.build
import com.pd2updater.install.TorrentTestSupport.stubBytes
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
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GameInstallerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val baseKey = "BCDFGHJKMNPRTVWXYZ24678922"
    private val expansionKey = "CDFGHJKMNPRTVWXYZ246789BCD"
    private val pieceLength = 1024

    private val gameExe = MpqTestSupport.payload(1_500, 1)
    private val lodExe = MpqTestSupport.payload(1_700, 2)
    private val dll = MpqTestSupport.payload(900, 3)
    private val charDll = MpqTestSupport.payload(800, 4)
    private val keyMember = "data\\global\\base.wav"
    private val lodKeyMember = "data\\global\\lod.wav"

    private fun target(body: String) = """<target location="user">$body</target>"""

    private val baseScript = "<install><archive>" + target(
        """<repack_into type="file">
             <repack from="Common\data.mpq" to="data.mpq" />
             <repack from="Common\char.mpq" to="char.mpq" />
             <repack from="PC\Game.exe" to="Game.exe" />
           </repack_into>""",
    ) + target(
        """<repack_into type="mpq" container="data.mpq">
             <repack from="PC\a.dll" to="a.dll" />
             <encrypt object="cdkey26" into="$keyMember" product_id="24" />
             <encrypt object="user" into="${Keystore.OWNER_MEMBER}" />
           </repack_into>""",
    ) + "</archive></install>"

    private val lodScript = """<install><delete type="file" path="{OriginalInstallPath}Game.exe" />""" +
        "<archive>" + target(
            """<repack_into type="file"><repack from="PC\Game.exe" to="Game.exe" /></repack_into>""",
        ) + target(
            """<repack_into type="mpq" container="char.mpq">
             <repack from="PC\c.dll" to="c.dll" />
             <encrypt object="cdkey26" into="$lodKeyMember" product_id="25" />
             <encrypt object="user" into="${Keystore.OWNER_MEMBER}" />
           </repack_into>""",
        ) + "</archive></install>"

    private val emptyArchive = build(listOf(Member("x.txt", "kept".toByteArray())), 16)

    private fun tome(vararg members: Member): ByteArray = build(members.toList(), 64)

    private val baseTome = tome(
        Member(Products.SCRIPT_MEMBER, baseScript.toByteArray()),
        Member("Common\\data.mpq", emptyArchive),
        Member("Common\\char.mpq", emptyArchive),
        Member("PC\\Game.exe", gameExe, true),
        Member("PC\\a.dll", dll),
    )
    private val lodTome = tome(
        Member(Products.SCRIPT_MEMBER, ("﻿" + lodScript).toByteArray()),
        Member("PC\\Game.exe", lodExe, true),
        Member("PC\\c.dll", charDll),
    )

    private class Hosted(val product: Product, val wrapper: ByteArray, val payload: ByteArray)

    private lateinit var server: HttpServer
    private val hosted = HashMap<String, Hosted>()
    private val pieceRequests = Collections.synchronizedList(ArrayList<String>())
    private val wrapperRequests = Collections.synchronizedList(ArrayList<String>())

    @Volatile
    private var refusePieces = false

    /** How many wrapper requests get getLegacy's rate limit: an empty 200. */
    private val rateLimited = AtomicInteger()

    @Volatile
    private var redirectWrappers = false

    /** A piece path the server refuses, such as "/piece/d2/0". */
    @Volatile
    private var refusedPiece: String? = null

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val parts = exchange.requestURI.path.trim('/').split('/')
            if (parts[0] == "wrapper" && redirectWrappers) {
                exchange.responseHeaders.add("Location", "/moved/${parts[1]}")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return@createContext
            }
            val entry = hosted[parts[1]]!!
            val body = when (parts[0]) {
                "wrapper" -> {
                    wrapperRequests.add(parts[1])
                    if (rateLimited.getAndUpdate { maxOf(it - 1, 0) } > 0) ByteArray(0) else entry.wrapper
                }
                "moved" -> entry.wrapper
                else -> {
                    pieceRequests.add(exchange.requestURI.path)
                    if (refusePieces || exchange.requestURI.path == refusedPiece) {
                        null
                    } else {
                        val from = parts[2].toInt() * pieceLength
                        entry.payload.copyOfRange(from, minOf(from + pieceLength, entry.payload.size))
                    }
                }
            }
            if (body == null) {
                exchange.sendResponseHeaders(403, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
            exchange.close()
        }
        server.start()
        hosted["d2"] = host("d2", KeyField.BASE_GAME, baseTome, ExpectedPlan(3, 1, 0, "data.mpq", 24, keyMember))
        hosted["lod"] = host("lod", KeyField.EXPANSION, lodTome, ExpectedPlan(1, 1, 1, "char.mpq", 25, lodKeyMember))
    }

    @After
    fun stop() = server.stop(0)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun host(id: String, field: KeyField, tome: ByteArray, expected: ExpectedPlan): Hosted {
        val port = server.address.port
        val base = "http://127.0.0.1:$port/piece/$id"
        val exe = MpqTestSupport.payload(2_222, 5)
        val cab = MpqTestSupport.payload(3_333, 6)
        val mac = MpqTestSupport.payload(1_111, 7)
        val payload = cab + tome + mac + exe
        val files = listOf(
            "DirectX/a.cab" to cab.size,
            Products.TOME to tome.size,
            "Installer.app/mac" to mac.size,
            Products.INSTALLER to exe.size,
        )
        val name = "Synthetic-$id"
        val torrent = torrentBytes(name, base, pieceLength, files, payload)
        val stub = stubBytes(torrent, "c=expires=${System.currentTimeMillis() / 1000 + 86_400}~access=/a/*~md5=0123456789abcdef0123456789abcdef")
        val read = Torrent.fromStub(stub)
        val pin = ProductPin(read.infohashHex, name, pieceLength.toLong(), read.pieceCount, read.total, base)
        val kept = listOf(
            KeptFile("DirectX/a.cab", cab.size.toLong(), sha256(cab)),
            KeptFile(Products.TOME, tome.size.toLong(), sha256(tome)),
            KeptFile(Products.INSTALLER, exe.size.toLong(), sha256(exe)),
        )
        val product = Product(id, field, "http://127.0.0.1:$port/wrapper/$id", pin, kept, expected)
        return Hosted(product, stub, payload)
    }

    private val products get() = listOf(hosted["d2"]!!.product, hosted["lod"]!!.product)

    private val log = Collections.synchronizedList(ArrayList<String>())

    private val progress = object : Progress {
        override fun status(text: String) {
            log.add(text)
        }

        override fun log(text: String) {
            log.add(text)
        }

        override fun bar(done: Long, total: Long) {}
    }

    private fun installer(
        game: File,
        cancel: AtomicBoolean = AtomicBoolean(false),
        keyCheck: (KeyField, String) -> KeyProblem? = { _, _ -> null },
        wrapperLimits: LongRange = 1L..1_000_000L,
        requiredBytes: Long = 1,
        now: () -> Long = System::currentTimeMillis,
        pd2: (File) -> Unit = { File(it, "ProjectDiablo.dll").writeText("pd2") },
    ) = GameInstaller(
        game = game,
        baseKey = baseKey,
        expansionKey = expansionKey,
        cancel = cancel,
        progress = progress,
        products = products,
        keyCheck = keyCheck,
        wrapperSizes = wrapperLimits,
        requiredBytes = requiredBytes,
        now = now,
        wrapperPauseMillis = 1,
        fetchLimits = PieceFetcher.Limits(
            connectMillis = 2_000, readMillis = 2_000, pieceMillis = 5_000, retries = 1, backoffMillis = 1,
            extraRounds = 0, roundPauseMillis = 1, giveUpFailures = 3, expiredRefusals = 3,
        ),
        pd2 = pd2,
    )

    private fun cacheOf(game: File) = File(game, GameInstaller.CACHE_FOLDER)

    @Test
    fun aFullInstallLeavesAPlayableFolder() {
        val game = temp.newFolder("Diablo II")
        var pd2Folder: File? = null
        installer(game, pd2 = { pd2Folder = it }).run()

        assertArrayEquals(lodExe, File(game, "Game.exe").readBytes())
        Mpq.open(File(game, "data.mpq")).use {
            assertArrayEquals(dll, it.member("a.dll"))
            assertArrayEquals(Keystore.wrap(baseKey.toByteArray()), it.member(keyMember))
            assertArrayEquals(Keystore.wrap("Player".toByteArray()), it.member(Keystore.OWNER_MEMBER))
        }
        Mpq.open(File(game, "char.mpq")).use {
            assertArrayEquals(charDll, it.member("c.dll"))
            assertArrayEquals(Keystore.wrap(expansionKey.toByteArray()), it.member(lodKeyMember))
        }
        assertEquals(File(game, "ProjectD2"), pd2Folder)
        assertTrue(pd2Folder!!.isDirectory)
        assertTrue(GameInstaller.hasDownloads(game))
    }

    @Test
    fun theKeysAreNeverWrittenInTheClear() {
        val game = temp.newFolder("Diablo II")
        installer(game).run()
        val keys = listOf(baseKey, expansionKey)
        game.walkTopDown().filter { it.isFile }.forEach { file ->
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            for (key in keys) assertFalse("key in ${file.name}", text.contains(key))
        }
        for (line in log) for (key in keys) assertFalse(line, line.contains(key))
    }

    @Test
    fun theDownloadsCanBeDeletedAfterwards() {
        val game = temp.newFolder("Diablo II")
        installer(game).run()
        GameInstaller.deleteDownloads(game)
        assertFalse(cacheOf(game).exists())
        assertFalse(GameInstaller.hasDownloads(game))
        assertTrue(File(game, "Game.exe").isFile)
    }

    @Test
    fun aBadKeyStopsBeforeAnythingIsWritten() {
        val game = temp.newFolder("Diablo II")
        val failure = assertThrows(InstallFailure::class.java) {
            installer(game, keyCheck = CdKeys::problem).run()
        }
        assertTrue(failure.message, failure.message!!.contains("Diablo II"))
        assertTrue(game.list()!!.isEmpty())
        assertTrue(pieceRequests.isEmpty())
    }

    @Test
    fun aFolderWithOtherFilesIsRefused() {
        val game = temp.newFolder("Diablo II")
        val mine = File(game, "my-save.d2s").apply { writeText("mine") }
        assertThrows(InstallFailure::class.java) { installer(game).run() }
        assertEquals("mine", mine.readText())
        assertTrue(pieceRequests.isEmpty())
    }

    @Test
    fun tooLittleSpaceIsRefusedBeforeDownloading() {
        val game = temp.newFolder("Diablo II")
        assertThrows(InstallFailure::class.java) { installer(game, requiredBytes = Long.MAX_VALUE).run() }
        assertTrue(pieceRequests.isEmpty())
    }

    @Test
    fun aRateLimitedWrapperIsAskedForAgain() {
        rateLimited.set(2)
        val game = temp.newFolder("Diablo II")
        installer(game).run()
        assertTrue(File(game, "Game.exe").isFile)
    }

    @Test
    fun aWrapperThatStaysRateLimitedFailsWithoutDownloading() {
        rateLimited.set(1_000)
        val game = temp.newFolder("Diablo II")
        assertThrows(InstallFailure::class.java) { installer(game).run() }
        assertTrue(pieceRequests.isEmpty())
    }

    @Test
    fun aRedirectedWrapperIsRefused() {
        redirectWrappers = true
        val game = temp.newFolder("Diablo II")
        assertThrows(InstallFailure::class.java) { installer(game).run() }
        assertTrue(pieceRequests.isEmpty())
    }

    @Test
    fun aWrapperOfAnotherSizeIsRefused() {
        val game = temp.newFolder("Diablo II")
        assertThrows(InstallFailure::class.java) { installer(game, wrapperLimits = 1L..10L).run() }
        assertTrue(pieceRequests.isEmpty())
    }

    @Test
    fun aWrapperThatIsNotAProgramIsRefused() {
        val entry = hosted["d2"]!!
        hosted["d2"] = Hosted(entry.product, "XX".toByteArray() + entry.wrapper, entry.payload)
        val game = temp.newFolder("Diablo II")
        assertThrows(InstallFailure::class.java) { installer(game).run() }
        assertTrue(pieceRequests.isEmpty())
    }

    @Test
    fun aTorrentOffItsPinIsRefused() {
        val entry = hosted["d2"]!!
        hosted["d2"] = Hosted(entry.product.copy(pin = entry.product.pin.copy(infohash = "00")), entry.wrapper, entry.payload)
        val game = temp.newFolder("Diablo II")
        assertThrows(InstallFailure::class.java) { installer(game).run() }
        assertTrue(pieceRequests.isEmpty())
    }

    @Test
    fun aCookieAboutToExpireIsRefused() {
        val game = temp.newFolder("Diablo II")
        val later = System.currentTimeMillis() + 86_400_000L
        assertThrows(InstallFailure::class.java) { installer(game, now = { later }).run() }
        assertTrue(pieceRequests.isEmpty())
    }

    @Test
    fun aFinishedFileWithTheWrongHashIsRefusedAndDownloadedAgain() {
        val entry = hosted["d2"]!!
        val pinned = entry.product.kept.map { if (it.path == Products.INSTALLER) it.copy(sha256 = "00") else it }
        hosted["d2"] = Hosted(entry.product.copy(kept = pinned), entry.wrapper, entry.payload)
        val game = temp.newFolder("Diablo II")
        assertThrows(InstallFailure::class.java) { installer(game).run() }
        assertFalse(File(game, "Game.exe").exists())
        hosted["d2"] = entry
        installer(game).run()
        assertTrue(File(game, "Game.exe").isFile)
    }

    @Test
    fun aRefusedDownloadFailsAndARerunFinishes() {
        val game = temp.newFolder("Diablo II")
        refusePieces = true
        assertThrows(InstallFailure::class.java) { installer(game).run() }
        refusePieces = false
        installer(game).run()
        assertTrue(File(game, "Game.exe").isFile)
    }

    @Test
    fun aStopThenARerunFinishes() {
        val game = temp.newFolder("Diablo II")
        val cancel = AtomicBoolean(false)
        val stopping = object : Progress by progress {
            override fun bar(done: Long, total: Long) {
                if (done > 0) cancel.set(true)
            }
        }
        assertThrows(Cancelled::class.java) {
            GameInstaller(
                game = game, baseKey = baseKey, expansionKey = expansionKey, cancel = cancel, progress = stopping,
                products = products, keyCheck = { _, _ -> null }, wrapperSizes = 1L..1_000_000L, requiredBytes = 1,
                pd2 = {},
            ).run()
        }
        assertFalse(File(game, "Game.exe").exists())
        installer(game).run()
        assertArrayEquals(lodExe, File(game, "Game.exe").readBytes())
    }

    @Test
    fun aRerunFetchesOnlyWhatIsMissing() {
        val game = temp.newFolder("Diablo II")
        refusedPiece = "/piece/d2/0"
        assertThrows(InstallFailure::class.java) { installer(game).run() }
        val pieceInTome = "/piece/d2/${3_333 / pieceLength + 1}"
        assertTrue(pieceInTome in pieceRequests)

        refusedPiece = null
        pieceRequests.clear()
        installer(game).run()
        assertTrue("/piece/d2/0" in pieceRequests)
        assertFalse(pieceInTome in pieceRequests)
        assertArrayEquals(lodExe, File(game, "Game.exe").readBytes())
    }

    @Test
    fun aRerunAfterTheBaseGameSkipsWhatIsDone() {
        val game = temp.newFolder("Diablo II")
        var fail = true
        assertThrows(IllegalStateException::class.java) {
            installer(game, pd2 = { if (fail) throw IllegalStateException("no network") }).run()
        }
        fail = false
        wrapperRequests.clear()
        pieceRequests.clear()
        var ran = false
        installer(game, pd2 = { ran = true }).run()
        assertTrue(ran)
        assertTrue(wrapperRequests.isEmpty())
        assertTrue(pieceRequests.isEmpty())
        assertArrayEquals(lodExe, File(game, "Game.exe").readBytes())
    }

    @Test
    fun aFinishedInstallIsNotDoneOver() {
        val game = temp.newFolder("Diablo II")
        installer(game).run()
        GameInstaller.deleteDownloads(game)
        assertThrows(InstallFailure::class.java) { installer(game).run() }
    }
}
