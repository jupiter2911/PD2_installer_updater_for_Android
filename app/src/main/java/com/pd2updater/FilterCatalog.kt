package com.pd2updater

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Loot filters as the official PD2 launcher picks them (Project-Diablo-2/PD2Launcher,
 * PD2Shared/Helpers/FilterHelpers.cs): a list of authors from [AUTHORS_URL]; each author's
 * `url` is a GitHub contents listing whose `.filter` files are the author's filters, named by
 * `filter_definitions.json` when the folder has one. Applying a filter downloads it into
 * `ProjectD2/filters/online/` and copies it over `ProjectD2/loot.filter`; a filter of
 * `filters/local/` is copied as it is.
 */
object FilterCatalog {
    const val AUTHORS_URL = "https://raw.githubusercontent.com/Project-Diablo-2/LootFilters/main/filters.json"
    const val LOCAL_AUTHOR = "Local Filter"
    const val LOOT_FILTER = "loot.filter"

    data class Author(val name: String, val url: String, val author: String) {
        val isLocal: Boolean get() = url == "local"
    }

    data class Filter(
        val name: String,
        val downloadUrl: String,
        val sha: String,
        val displayName: String,
        val description: String,
        val filterId: String,
        /** GitHub's blob API for exactly [sha]'s contents; empty for a local filter. */
        val gitUrl: String = "",
    )

    val localAuthor = Author(LOCAL_AUTHOR, "local", LOCAL_AUTHOR)

    fun parseAuthors(json: String): List<Author> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (!url.startsWith("https://")) return@mapNotNull null
            Author(o.optString("name", url), url, o.optString("author", ""))
        }
    }

    /** The author's filters from a GitHub contents listing and, when given, its definitions file. */
    fun buildFilters(listing: String, definitions: String?): List<Filter> {
        val files = JSONArray(listing)
        val filters = (0 until files.length()).mapNotNull { i ->
            val o = files.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            val url = o.optString("download_url")
            if (!name.endsWith(".filter", ignoreCase = true) || !url.startsWith("https://")) return@mapNotNull null
            if (name.contains('/') || name.contains('\\')) return@mapNotNull null
            val gitUrl = o.optString("git_url").takeIf { it.startsWith("https://api.github.com/") }.orEmpty()
            Filter(name, url, o.optString("sha"), name, "", "", gitUrl)
        }
        val info = definitions?.let { runCatching { JSONObject(it).optJSONObject("filter_info") }.getOrNull() }
            ?: return filters
        val mapped = info.keys().asSequence().mapNotNull { id ->
            val entry = info.optJSONObject(id) ?: return@mapNotNull null
            val fileName = entry.optString("file_name")
            val file = filters.firstOrNull { it.name.equals(fileName, ignoreCase = true) } ?: return@mapNotNull null
            file.copy(
                displayName = entry.optString("display_name").ifBlank { file.name },
                description = entry.optString("description"),
                filterId = id,
            )
        }.toList()
        return mapped.ifEmpty { filters }
    }

    /** The name of the definitions file in a listing, as the launcher looks for it. */
    fun definitionsUrl(listing: String): String? {
        val files = JSONArray(listing)
        for (i in 0 until files.length()) {
            val o = files.optJSONObject(i) ?: continue
            if (o.optString("name").equals("filter_definitions.json", ignoreCase = true)) {
                return o.optString("download_url").takeIf { it.startsWith("https://") }
            }
        }
        return null
    }

    /** The contents of a GitHub blob API answer: `{"content": "<base64 with newlines>", "encoding": "base64"}`. */
    fun decodeBlob(json: String): ByteArray {
        val o = JSONObject(json)
        if (o.optString("encoding") != "base64") throw IOException("GitHub blob không phải base64")
        return java.util.Base64.getMimeDecoder().decode(o.getString("content"))
    }

    /** Git's blob SHA-1 of [bytes]: the `sha` GitHub lists for a file with these contents. */
    fun gitBlobSha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun localFilters(projectD2: File): List<Filter> =
        File(projectD2, "filters/local").listFiles { f -> f.isFile && f.name.endsWith(".filter", true) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { Filter(it.name, "", gitBlobSha(it.readBytes()), it.name, "Filter của bạn trong filters/local", "") }
            ?: emptyList()
}

/** Network and file work for [FilterCatalog]. */
class FilterService(private val projectD2: File) {
    fun authors(): List<FilterCatalog.Author> = FilterCatalog.parseAuthors(get(FilterCatalog.AUTHORS_URL))

    fun filters(author: FilterCatalog.Author): List<FilterCatalog.Filter> {
        if (author.isLocal) return FilterCatalog.localFilters(projectD2)
        val listing = get(author.url)
        val definitions = FilterCatalog.definitionsUrl(listing)?.let { runCatching { get(it) }.getOrNull() }
        return FilterCatalog.buildFilters(listing, definitions)
    }

    /** The author's current copy of the chosen filter: by its id, else by its file name. */
    fun resolve(author: FilterCatalog.Author, name: String, filterId: String): FilterCatalog.Filter? {
        val all = filters(author)
        return all.firstOrNull { filterId.isNotEmpty() && it.filterId.equals(filterId, true) }
            ?: all.firstOrNull { it.name.equals(name, true) }
    }

    /** Whether loot.filter already is [filter]'s current contents. */
    fun isCurrent(filter: FilterCatalog.Filter): Boolean {
        val loot = File(projectD2, FilterCatalog.LOOT_FILTER)
        if (!loot.isFile || filter.sha.isEmpty()) return false
        return FilterCatalog.gitBlobSha(loot.readBytes()) == filter.sha
    }

    /** Puts [filter] in place as loot.filter. */
    fun apply(author: FilterCatalog.Author, filter: FilterCatalog.Filter) {
        val loot = File(projectD2, FilterCatalog.LOOT_FILTER)
        val source = if (author.isLocal) {
            File(projectD2, "filters/local/${filter.name}")
        } else {
            val online = File(projectD2, "filters/online").apply { mkdirs() }
            val bytes = download(filter)
            File(online, filter.name).apply { writeBytes(bytes) }
        }
        if (!source.isFile) throw IOException("Không thấy ${source.path}")
        val temp = File(projectD2, "${FilterCatalog.LOOT_FILTER}.pd2-updater")
        source.copyTo(temp, overwrite = true)
        if (loot.exists() && !loot.delete()) throw IOException("Không thay được loot.filter")
        if (!temp.renameTo(loot)) throw IOException("Không thay được loot.filter")
    }

    /**
     * The filter's bytes for exactly its listed SHA. raw.githubusercontent.com sits behind a CDN
     * that can serve the previous commit's copy for a few minutes after an author pushes, while
     * the API listing already names the new SHA; then the blob API gives that SHA's contents.
     */
    private fun download(filter: FilterCatalog.Filter): ByteArray {
        val raw = getBytes(filter.downloadUrl)
        if (filter.sha.isEmpty() || FilterCatalog.gitBlobSha(raw) == filter.sha) return raw
        if (filter.gitUrl.isEmpty()) throw IOException("Filter tải về không khớp SHA của GitHub (không có git_url)")
        val blob = FilterCatalog.decodeBlob(get(filter.gitUrl))
        val got = FilterCatalog.gitBlobSha(blob)
        if (got != filter.sha) throw IOException("Filter không khớp SHA của GitHub: ${got.take(10)} ≠ ${filter.sha.take(10)}")
        return blob
    }

    private fun get(url: String): String = String(getBytes(url), Charsets.UTF_8).removePrefix("﻿")

    private fun getBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "pd2-updater-android/1.2")
        if (url.startsWith("https://api.github.com/")) {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = connection.responseCode
            if (code == 403 || code == 429) throw IOException("GitHub tạm giới hạn lượt truy cập, thử lại sau ít phút")
            if (code != 200) throw IOException("HTTP $code: $url")
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > 16 * 1024 * 1024) throw IOException("File quá lớn: $url")
            return bytes
        } finally {
            connection.disconnect()
        }
    }
}
