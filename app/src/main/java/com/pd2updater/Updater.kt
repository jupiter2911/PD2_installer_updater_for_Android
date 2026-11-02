package com.pd2updater

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class Cancelled : Exception("Đã dừng")

/** What one file of the manifest needs. */
enum class FileState { MATCH, KEPT, UPDATE }

data class Plan(
    val manifestRaw: String,
    val entries: List<Pd2Manifest.Entry>,
    val states: Map<String, FileState>,
) {
    val toUpdate: List<Pd2Manifest.Entry> get() = entries.filter { states[it.name] == FileState.UPDATE }
    val kept: Int get() = states.values.count { it == FileState.KEPT }
}

interface Progress {
    fun status(text: String)
    fun log(text: String)
    fun bar(done: Long, total: Long)
}

/**
 * Decides each file's state from the folder's own files. A protected file named in
 * [alwaysUpdate] (for example default.filter) is updated like any other file.
 */
fun planFor(
    entries: List<Pd2Manifest.Entry>,
    folder: File,
    md5Of: (File) -> String,
    raw: String = "",
    alwaysUpdate: Set<String> = emptySet(),
): Plan {
    val states = entries.associate { entry ->
        val file = File(folder, entry.name)
        entry.name to when {
            entry.isProtected && entry.name !in alwaysUpdate && file.exists() -> FileState.KEPT
            file.isFile && md5Of(file) == entry.md5 -> FileState.MATCH
            else -> FileState.UPDATE
        }
    }
    return Plan(raw, entries, states)
}

/**
 * The update workflow of the desktop PD2 Launcher, reduced to its steps: get the manifest, MD5
 * every file of the ProjectD2 folder, download what is missing or different into a staging
 * folder, check each download's MD5, then move them all into place. Player files stay.
 */
class Updater(private val projectD2: File, private val cancel: AtomicBoolean) {
    private val staging = File(projectD2, ".pd2-updater-staging")
    val folder: File get() = projectD2

    fun check(progress: Progress, alwaysUpdate: Set<String> = emptySet()): Plan {
        progress.status("Đang tải manifest…")
        val raw = retry(progress) { httpText(Pd2Manifest.MANIFEST_URL) }
        val entries = Pd2Manifest.parseLines(checksumLines(raw))
        progress.log("Manifest: ${entries.size} file")
        var i = 0L
        val plan = planFor(entries, projectD2, { file ->
            checkCancel()
            progress.status("Đang kiểm tra ${file.name}")
            progress.bar(++i, entries.size.toLong())
            md5(file)
        }, raw, alwaysUpdate)
        progress.bar(entries.size.toLong(), entries.size.toLong())
        return plan
    }

    fun apply(plan: Plan, progress: Progress) {
        val todo = plan.toUpdate
        if (todo.isEmpty()) return
        staging.mkdirs()
        if (!staging.isDirectory) throw IOException("Không tạo được thư mục ${staging.path}")
        todo.forEachIndexed { index, entry ->
            checkCancel()
            val part = File(staging, entry.name)
            if (part.isFile && md5(part) == entry.md5) {
                progress.log("Đã có sẵn trong staging: ${entry.name}")
            } else {
                retry(progress) {
                    progress.status("Tải ${index + 1}/${todo.size}: ${entry.name}")
                    download(entry.url, part, progress)
                    val got = md5(part)
                    if (got != entry.md5) {
                        part.delete()
                        throw IOException("Sai MD5 ${entry.name}: $got ≠ ${entry.md5}")
                    }
                }
                progress.log("Đã tải: ${entry.name}")
            }
        }
        checkCancel()
        progress.status("Đang chép file vào ProjectD2…")
        for (entry in todo) {
            Files.move(
                File(staging, entry.name).toPath(),
                File(projectD2, entry.name).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        staging.delete()
        File(projectD2, ".pd2-updater-manifest.json").writeText(plan.manifestRaw)
    }

    private fun checkCancel() {
        if (cancel.get()) throw Cancelled()
    }

    private fun <T> retry(progress: Progress, block: () -> T): T {
        var last: Exception? = null
        for (attempt in 1..4) {
            checkCancel()
            try {
                return block()
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                last = e
                progress.log("Lỗi (lần $attempt): ${e.message}")
                if (attempt < 4) Thread.sleep(1000L * attempt * attempt)
            }
        }
        throw last!!
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "pd2-updater-android/1.0")
        val code = connection.responseCode
        if (code != 200) {
            connection.disconnect()
            throw IOException("HTTP $code: $url")
        }
        return connection
    }

    private fun httpText(url: String): String {
        val connection = open(url)
        try {
            val length = connection.contentLengthLong
            if (length > 1024 * 1024) throw IOException("Manifest quá lớn")
            val bytes = connection.inputStream.use { it.readBytes() }
            if (length >= 0 && bytes.size.toLong() != length) throw IOException("Manifest bị cắt")
            return String(bytes, Charsets.UTF_8).removePrefix("﻿")
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File, progress: Progress) {
        val connection = open(url)
        try {
            val total = connection.contentLengthLong
            var done = 0L
            target.outputStream().use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        checkCancel()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        progress.bar(done, total)
                    }
                }
            }
            if (total >= 0 && done != total) throw IOException("Tải thiếu: $done/$total byte")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun checksumLines(raw: String): List<String> {
            val json = org.json.JSONObject(raw)
            if (json.length() != 1) throw Pd2Manifest.InvalidManifest("unexpected keys")
            val array = json.getJSONArray("checksum")
            return (0 until array.length()).map { array.getString(it) }
        }

        fun md5(file: File): String = file.inputStream().use { md5(it) }

        fun md5(input: InputStream): String {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** The ProjectD2 folder for what the player typed: the folder itself, or its ProjectD2. */
        fun resolveProjectD2(path: String): File? {
            val folder = File(path.trim())
            val sub = folder.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("ProjectD2", true) }
            if (sub != null) return sub
            val looksLikeProject = folder.listFiles()?.any {
                it.name.equals("ProjectDiablo.dll", true) || it.name.equals("PD2Launcher.exe", true)
            } == true
            return if (looksLikeProject || folder.name.equals("ProjectD2", true) && folder.isDirectory) folder else null
        }
    }
}
