package com.pd2updater.install

import com.pd2updater.Cancelled
import com.pd2updater.t
import com.pd2updater.Progress
import com.pd2updater.Updater
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs Diablo II, Lord of Destruction and Project Diablo 2 into an empty
 * folder: every product's files are downloaded and checked, its install script
 * is run, then ProjectD2 comes from the PD2 manifest. Stopping and running again
 * goes on from what is finished. The keys stay in memory and in the game's own
 * archives, wrapped the way the game expects them.
 */
class GameInstaller(
    private val game: File,
    baseKey: String,
    expansionKey: String,
    private val cancel: AtomicBoolean,
    private val progress: Progress,
    private val products: List<Product> = listOf(Products.BASE_GAME, Products.EXPANSION),
    private val keyCheck: (KeyField, String) -> KeyProblem? = CdKeys::problem,
    private val wrapperSizes: LongRange = 1_000_000L..8_000_000L,
    private val requiredBytes: Long = REQUIRED_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
    private val wrapperPauseMillis: Long = 5_000,
    private val fetchLimits: PieceFetcher.Limits = PieceFetcher.Limits(),
    private val pd2: ((File) -> Unit)? = null,
) {
    private val baseKey = CdKeys.normalize(baseKey)
    private val expansionKey = CdKeys.normalize(expansionKey)
    private val cache = File(game, CACHE_FOLDER)

    fun run() {
        checkKeys()
        checkFolder()
        checkSpace()
        cache.mkdirs()
        if (!cache.isDirectory) throw InstallFailure(t("Could not create folder ${cache.path}", "Không tạo được thư mục ${cache.path}"))
        for (product in products) install(product)
        val projectD2 = File(game, PROJECT_D2)
        projectD2.mkdirs()
        if (!projectD2.isDirectory) throw InstallFailure(t("Could not create folder ${projectD2.path}", "Không tạo được thư mục ${projectD2.path}"))
        progress.status(t("Installing Project Diablo 2…", "Đang cài Project Diablo 2…"))
        (pd2 ?: ::installPd2)(projectD2)
    }

    private fun checkKeys() {
        for (product in products) {
            val problem = keyCheck(product.field, keyFor(product)) ?: continue
            throw InstallFailure(describe(product.field, problem))
        }
    }

    private fun keyFor(product: Product) = if (product.field == KeyField.BASE_GAME) baseKey else expansionKey

    /** Only an empty folder or one this installer began: nothing of the player's is touched. */
    private fun checkFolder() {
        if (game.exists() && !game.isDirectory) throw InstallFailure(t("${game.path} is not a folder.", "${game.path} không phải thư mục."))
        if (cache.isDirectory) return
        if (!game.list().isNullOrEmpty()) {
            throw InstallFailure(t("The folder already has files. Choose an empty folder to install into.", "Thư mục đã có file. Hãy chọn một thư mục trống để cài đặt."))
        }
    }

    /** What is already here counts: it was part of what is needed. */
    private fun checkSpace() {
        game.mkdirs()
        val present = game.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val needed = requiredBytes - present
        if (needed > 0 && game.usableSpace < needed) {
            throw InstallFailure(t("Not enough space: about ${gigabytes(needed)} GB more is needed.", "Không đủ dung lượng: cần thêm khoảng ${gigabytes(needed)} GB trống."))
        }
    }

    private fun install(product: Product) {
        val done = File(cache, "${product.id}.done")
        if (done.isFile) {
            progress.log(t("${title(product)}: already installed.", "${title(product)}: đã cài xong trước đó."))
            return
        }
        val folder = downloaded(product)
        progress.status(t("Installing ${title(product)}…", "Đang cài ${title(product)}…"))
        val tomeFile = File(folder, Products.TOME)
        Mpq.open(tomeFile).use { tome ->
            val script = String(tome.member(Products.SCRIPT_MEMBER), Charsets.UTF_8).removePrefix("﻿")
            val plan = InstallScript.plan(script)
            NativeInstall.checkPlan(plan, product.expected)
            NativeInstall.run(tome, plan, game, keyFor(product), cancel) { copied, total -> progress.bar(copied, total) }
        }
        done.writeText("done")
        progress.log(t("${title(product)}: installed.", "${title(product)}: đã cài xong."))
    }

    /** The product's checked files, downloaded unless a finished copy still matches. */
    private fun downloaded(product: Product): File {
        val finished = File(cache, product.pin.name)
        if (finished.isDirectory) {
            if (product.kept.all { matches(File(finished, it.path), it) }) return finished
            progress.log(t("${title(product)}: downloaded files are damaged, downloading again.", "${title(product)}: file đã tải bị hỏng, tải lại."))
            if (!finished.deleteRecursively()) throw InstallFailure(t("Could not delete ${finished.path}", "Không xóa được ${finished.path}"))
        }
        val partial = File(cache, "${product.pin.name}.partial")
        fetch(product, partial)
        for (file in product.kept) {
            val target = File(partial, file.path)
            if (!matches(target, file)) {
                target.delete()
                throw InstallFailure(t("A downloaded file is wrong: ${file.path}. Tap Install to download it again.", "File tải về không đúng: ${file.path}. Bấm Cài đặt để tải lại file này."))
            }
        }
        if (!partial.renameTo(finished)) throw InstallFailure(t("Could not rename ${partial.path}", "Không đổi tên được ${partial.path}"))
        return finished
    }

    private fun fetch(product: Product, partial: File) {
        val pin = product.pin
        progress.status(t("Getting download information for ${title(product)}…", "Đang lấy thông tin tải ${title(product)}…"))
        val wrapper = wrapper(product)
        val torrent = try {
            Torrent.fromStub(wrapper)
        } catch (error: TorrentException) {
            throw InstallFailure(t("Could not read the download information for ${title(product)}.", "Không đọc được thông tin tải của ${title(product)}."))
        }
        val cookie = AccessCookie.find(wrapper)
            ?: throw InstallFailure(t("The information for ${title(product)} holds no download permission.", "Không có quyền tải trong thông tin của ${title(product)}."))
        if (cookie.expires <= now() / 1000 + COOKIE_MARGIN_SECONDS) {
            throw InstallFailure(t("The download permission for ${title(product)} is about to expire. Try again later.", "Quyền tải của ${title(product)} sắp hết hạn. Hãy thử lại sau."))
        }
        Products.pinMismatch(torrent, pin, product.kept)?.let {
            throw InstallFailure(t("The ${title(product)} installer differs from the verified one ($it), not downloading.", "Bộ cài ${title(product)} khác với bản đã kiểm chứng ($it), không tải."))
        }
        val kept = product.kept.map { it.path }.toSet()
        val files = ArrayList<PayloadFile?>()
        val outcome = try {
            for (file in torrent.files) {
                if (file.path in kept) {
                    val target = File(partial, file.path)
                    files.add(PayloadFile.open(target, file.length, !target.exists()))
                } else {
                    files.add(null)
                }
            }
            progress.status(t("Downloading ${title(product)}…", "Đang tải ${title(product)}…"))
            val outcome = PieceFetcher(fetchLimits, cancel = cancel)
                .fetch(Download(torrent, cookie, pin.base, files)) { done, total -> progress.bar(done, total) }
            if (outcome == FetchOutcome.COMPLETE) files.forEach { it?.sync() }
            outcome
        } finally {
            files.forEach { it?.close() }
        }
        when (outcome) {
            FetchOutcome.COMPLETE -> {}
            FetchOutcome.REFUSED, FetchOutcome.EXPIRED ->
                throw InstallFailure(t("The server refused ${title(product)}. Tap Install to try again; what was downloaded is kept.", "Máy chủ từ chối tải ${title(product)}. Bấm Cài đặt để thử lại, phần đã tải được giữ nguyên."))
            FetchOutcome.INCOMPLETE ->
                throw InstallFailure(t("Unstable network, ${title(product)} is not fully downloaded. Tap Install to continue.", "Mạng không ổn định, ${title(product)} chưa tải hết. Bấm Cài đặt để tải tiếp."))
            FetchOutcome.STORE_FAILED ->
                throw InstallFailure(t("Could not write to storage while downloading ${title(product)}.", "Không ghi được file vào bộ nhớ khi tải ${title(product)}."))
        }
    }

    /** The downloader program, which holds the torrent and the access cookie: never kept or logged. */
    private fun wrapper(product: Product): ByteArray {
        var last: IOException? = null
        repeat(WRAPPER_ATTEMPTS) { attempt ->
            if (cancel.get()) throw Cancelled()
            if (attempt > 0) pause(wrapperPauseMillis * attempt)
            try {
                return readWrapper(product)
            } catch (failure: InstallFailure) {
                throw failure
            } catch (error: IOException) {
                last = error
            }
        }
        throw InstallFailure(t("Could not download the information for ${title(product)}: ${last?.message}", "Không tải được thông tin của ${title(product)}: ${last?.message}"))
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

    private fun readWrapper(product: Product): ByteArray {
        val connection = URL(product.wrapperUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val status = connection.responseCode
            if (status in 300..399) throw InstallFailure(t("The download address of ${title(product)} was redirected, not downloading.", "Địa chỉ tải ${title(product)} đã bị chuyển hướng, không tải."))
            if (status != 200) throw IOException("HTTP $status")
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    if (out.size() > wrapperSizes.last) throw InstallFailure(t("The download information for ${title(product)} is too large.", "Thông tin tải của ${title(product)} quá lớn."))
                }
            }
            val bytes = out.toByteArray()
            // getLegacy's rate limit is an empty or nearly empty 200: ask again after a pause.
            if (bytes.size < minOf(wrapperSizes.first, RATE_LIMITED_WRAPPER_BYTES)) throw IOException(t("the server is rate limiting, try again in a few minutes", "máy chủ đang giới hạn tải, hãy thử lại sau ít phút"))
            if (bytes.size < wrapperSizes.first || bytes.size < 2 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) {
                throw InstallFailure(t("The download information for ${title(product)} has the wrong format.", "Thông tin tải của ${title(product)} không đúng định dạng."))
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun matches(file: File, expected: KeptFile): Boolean {
        if (!file.isFile || file.length() != expected.size) return false
        progress.status(t("Checking ${file.name}…", "Đang kiểm tra ${file.name}…"))
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        var read = 0L
        FileInputStream(file).use { input ->
            while (true) {
                if (cancel.get()) throw Cancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                read += count
                progress.bar(read, expected.size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected.sha256
    }

    private fun installPd2(projectD2: File) {
        val updater = Updater(projectD2, cancel)
        updater.apply(updater.check(progress), progress)
        if (updater.check(progress).toUpdate.isNotEmpty()) {
            throw InstallFailure(t("Some PD2 files still differ. Tap Install to try again.", "Còn file PD2 chưa khớp. Bấm Cài đặt để thử lại."))
        }
        val missing = PD2_LAUNCH_FILES.filterNot { File(projectD2, it).isFile }
        if (missing.isNotEmpty()) {
            throw InstallFailure(
                t("The PD2 manifest lacks ${missing.joinToString()}. Run ProjectD2Installer.exe once in Winlator.", "Manifest PD2 thiếu ${missing.joinToString()}. Hãy chạy ProjectD2Installer.exe một lần trong Winlator."),
            )
        }
    }

    private fun title(product: Product) = fieldTitle(product.field)

    companion object {
        const val CACHE_FOLDER = "_pd2-install"
        private const val PROJECT_D2 = "ProjectD2"
        private const val REQUIRED_BYTES = 6L * 1024 * 1024 * 1024
        private const val COOKIE_MARGIN_SECONDS = 3_600L
        private const val WRAPPER_ATTEMPTS = 4
        private const val RATE_LIMITED_WRAPPER_BYTES = 1024L
        private val PD2_LAUNCH_FILES = listOf("Game.exe", "ProjectDiablo.dll")

        private fun fieldTitle(field: KeyField) =
            if (field == KeyField.BASE_GAME) "Diablo II" else "Lord of Destruction"

        private fun gigabytes(bytes: Long) = (bytes / (1024 * 1024 * 1024)) + 1

        /** Why a key is refused, for the player. */
        fun describe(field: KeyField, problem: KeyProblem): String {
            val why = when (problem) {
                KeyProblem.WRONG_LENGTH -> t("the key must have 26 characters", "key phải có 26 ký tự")
                KeyProblem.OLD_KEY_LENGTH -> t("this is an old 16-character key, this installer needs a 26-character key", "đây là key 16 ký tự kiểu cũ, bộ cài này cần key 26 ký tự")
                KeyProblem.INVALID_CHARACTER -> t("it has an invalid character", "có ký tự không hợp lệ")
                KeyProblem.EXPANSION_KEY_IN_BASE_FIELD -> t("this is a Lord of Destruction key", "đây là key của Lord of Destruction")
                KeyProblem.BASE_KEY_IN_EXPANSION_FIELD -> t("this is a Diablo II key", "đây là key của Diablo II")
                KeyProblem.WRONG_PRODUCT -> t("the key is not for this product", "key không thuộc sản phẩm này")
            }
            return "CD key ${fieldTitle(field)}: $why."
        }

        fun hasDownloads(game: File): Boolean = File(game, CACHE_FOLDER).exists()

        /** Remove the downloaded installers; the installed game stays. */
        fun deleteDownloads(game: File) {
            val cache = File(game, CACHE_FOLDER)
            if (cache.exists() && !cache.deleteRecursively()) throw IOException(t("Could not delete ${cache.path}", "Không xóa được ${cache.path}"))
        }
    }
}
