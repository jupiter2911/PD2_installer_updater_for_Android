package com.pd2updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FilterCatalogTest {
    @Test
    fun parsesAuthors() {
        val json = """[
            {"name":"Wolfie's PD2 Loot Filters","url":"https://api.github.com/repos/WolfieeifloW/pd2filter/contents","author":"Wolfie"},
            {"name":"bad","url":"http://insecure.example/x","author":"x"}
        ]"""
        val authors = FilterCatalog.parseAuthors(json)
        assertEquals(1, authors.size)
        assertEquals("Wolfie", authors[0].author)
    }

    private val listing = """[
        {"name":"README.md","download_url":"https://raw.githubusercontent.com/a/b/main/README.md","sha":"1","type":"file"},
        {"name":"Strict.filter","download_url":"https://raw.githubusercontent.com/a/b/main/Strict.filter","sha":"aaa","type":"file"},
        {"name":"Loose.filter","download_url":"https://raw.githubusercontent.com/a/b/main/Loose.filter","sha":"bbb","type":"file"},
        {"name":"filter_definitions.json","download_url":"https://raw.githubusercontent.com/a/b/main/filter_definitions.json","sha":"c","type":"file"},
        {"name":"docs","download_url":null,"sha":"d","type":"dir"}
    ]"""

    @Test
    fun filtersWithoutDefinitions() {
        val filters = FilterCatalog.buildFilters(listing, null)
        assertEquals(listOf("Strict.filter", "Loose.filter"), filters.map { it.name })
        assertEquals("Strict.filter", filters[0].displayName)
    }

    @Test
    fun filtersWithDefinitions() {
        assertEquals("https://raw.githubusercontent.com/a/b/main/filter_definitions.json", FilterCatalog.definitionsUrl(listing))
        val definitions = """{"filter_info":{
            "strict":{"display_name":"Strict (endgame)","description":"Hides most items","file_name":"Strict.filter"},
            "gone":{"display_name":"Gone","file_name":"Missing.filter"}
        }}"""
        val filters = FilterCatalog.buildFilters(listing, definitions)
        assertEquals(1, filters.size)
        assertEquals("Strict (endgame)", filters[0].displayName)
        assertEquals("Hides most items", filters[0].description)
        assertEquals("strict", filters[0].filterId)
    }

    @Test
    fun keepsBlobUrlForExactDownload() {
        val listing = """[{"name":"A.filter","download_url":"https://raw.githubusercontent.com/a/b/main/A.filter","sha":"aaa",
            "git_url":"https://api.github.com/repos/a/b/git/blobs/aaa","type":"file"}]"""
        assertEquals("https://api.github.com/repos/a/b/git/blobs/aaa", FilterCatalog.buildFilters(listing, null)[0].gitUrl)
    }

    @Test
    fun decodesBlobWithNewlines() {
        // GitHub wraps the base64 at 60 characters; "hello world\n" is aGVsbG8gd29ybGQK.
        val bytes = FilterCatalog.decodeBlob("""{"sha":"3b18e5","encoding":"base64","content":"aGVsbG8g\nd29ybGQK\n"}""")
        assertEquals("hello world\n", String(bytes))
        assertEquals("3b18e512dba79e4c8300dd08aeb37f8e728b8dad", FilterCatalog.gitBlobSha(bytes))
    }

    @Test
    fun badDefinitionsFallBackToFiles() {
        assertEquals(2, FilterCatalog.buildFilters(listing, "not json").size)
    }

    @Test
    fun gitBlobShaMatchesGit() {
        // `git hash-object` of an empty file and of "hello world\n".
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", FilterCatalog.gitBlobSha(ByteArray(0)))
        assertEquals("3b18e512dba79e4c8300dd08aeb37f8e728b8dad", FilterCatalog.gitBlobSha("hello world\n".toByteArray()))
    }

    @Test
    fun localFilterAppliesAndIsCurrent() {
        val dir = Files.createTempDirectory("pd2").toFile()
        val local = File(dir, "filters/local").apply { mkdirs() }
        File(local, "Mine.filter").writeText("ItemDisplay[]: %NAME%")
        val filters = FilterCatalog.localFilters(dir)
        assertEquals(1, filters.size)

        val service = FilterService(dir)
        File(dir, "loot.filter").writeText("old")
        assertTrue(!service.isCurrent(filters[0]))
        service.apply(FilterCatalog.localAuthor, filters[0])
        assertEquals("ItemDisplay[]: %NAME%", File(dir, "loot.filter").readText())
        assertTrue(service.isCurrent(filters[0]))
        dir.deleteRecursively()
    }

    @Test
    fun noLocalFolderNoFilters() {
        val dir = Files.createTempDirectory("pd2").toFile()
        assertTrue(FilterCatalog.localFilters(dir).isEmpty())
        assertNull(FilterCatalog.definitionsUrl("[]"))
        dir.deleteRecursively()
    }
}
