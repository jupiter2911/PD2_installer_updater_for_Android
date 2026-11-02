package com.pd2updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Pd2ManifestTest {
    private val md5 = "0123456789abcdef0123456789abcdef"

    @Test
    fun parsesLine() {
        val entry = Pd2Manifest.parseLine("$md5  ProjectDiablo.dll")
        assertEquals("ProjectDiablo.dll", entry.name)
        assertEquals(md5, entry.md5)
        assertEquals("https://pd2-client-files.projectdiablo2.com/ProjectDiablo.dll", entry.url)
    }

    @Test
    fun encodesSpaces() {
        assertEquals("Diablo%20II.exe", Pd2Manifest.encode("Diablo II.exe"))
    }

    @Test(expected = Pd2Manifest.InvalidManifest::class)
    fun rejectsUppercaseMd5() {
        Pd2Manifest.parseLine("0123456789ABCDEF0123456789abcdef  a.dll")
    }

    @Test(expected = Pd2Manifest.InvalidManifest::class)
    fun rejectsSingleSpace() {
        Pd2Manifest.parseLine("$md5 a.dll")
    }

    @Test(expected = Pd2Manifest.InvalidManifest::class)
    fun rejectsSubfolder() {
        Pd2Manifest.parseLine("$md5  ../evil.dll")
    }

    @Test(expected = Pd2Manifest.InvalidManifest::class)
    fun rejectsCaseDuplicates() {
        Pd2Manifest.parseLines(listOf("$md5  a.dll", "$md5  A.DLL"))
    }

    @Test
    fun plansMatchKeptAndUpdate() {
        val dir = Files.createTempDirectory("pd2").toFile()
        val same = File(dir, "same.dll").apply { writeText("x") }
        File(dir, "loot.filter").writeText("mine")
        File(dir, "old.dll").writeText("old")
        val sameMd5 = Updater.md5(same)
        val entries = Pd2Manifest.parseLines(
            listOf("$sameMd5  same.dll", "$md5  loot.filter", "$md5  old.dll", "$md5  new.dll", "$md5  UI.ini"),
        )
        val plan = planFor(entries, dir, Updater::md5)
        assertEquals(FileState.MATCH, plan.states["same.dll"])
        assertEquals(FileState.KEPT, plan.states["loot.filter"])
        assertEquals(FileState.UPDATE, plan.states["old.dll"])
        assertEquals(FileState.UPDATE, plan.states["new.dll"])
        // A protected file that is missing is written.
        assertEquals(FileState.UPDATE, plan.states["UI.ini"])
        dir.deleteRecursively()
    }

    @Test
    fun resolvesProjectD2() {
        val dir = Files.createTempDirectory("d2").toFile()
        assertNull(Updater.resolveProjectD2(dir.path))
        val project = File(dir, "ProjectD2").apply { mkdirs() }
        assertEquals(project, Updater.resolveProjectD2(dir.path))
        assertNotNull(Updater.resolveProjectD2(project.path))
        dir.deleteRecursively()
    }
}
