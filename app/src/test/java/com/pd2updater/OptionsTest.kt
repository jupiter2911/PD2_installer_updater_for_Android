package com.pd2updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OptionsTest {
    private val md5 = "0123456789abcdef0123456789abcdef"

    @Test
    fun defaultFilterKeptUnlessAsked() {
        val dir = Files.createTempDirectory("pd2").toFile()
        File(dir, "default.filter").writeText("old")
        File(dir, "loot.filter").writeText("mine")
        val entries = Pd2Manifest.parseLines(listOf("$md5  default.filter", "$md5  loot.filter"))

        val normal = planFor(entries, dir, Updater::md5)
        assertEquals(FileState.KEPT, normal.states["default.filter"])

        val forced = planFor(entries, dir, Updater::md5, alwaysUpdate = setOf("default.filter"))
        assertEquals(FileState.UPDATE, forced.states["default.filter"])
        assertEquals(FileState.KEPT, forced.states["loot.filter"])
        dir.deleteRecursively()
    }

    @Test
    fun glideScript() {
        val script = GameLauncher.script(Renderer.GLIDE)
        assertTrue(script.contains("start \"\" \"Game.exe\" -3dfx\r\n"))
        assertFalse(script.contains("WINEDLLOVERRIDES"))
    }

    @Test
    fun ddrawScriptUsesPd2Ddraw() {
        val script = GameLauncher.script(Renderer.DDRAW)
        assertTrue(script.contains("set WINEDLLOVERRIDES=ddraw=n,b\r\n"))
        assertTrue(script.contains("\"Game.exe\" -ddraw"))
    }

    @Test
    fun rendererDefaultsToGlide() {
        assertEquals(Renderer.GLIDE, Renderer.of(null))
        assertEquals(Renderer.DDRAW, Renderer.of("DDRAW"))
    }

    @Test
    fun treeIdToPath() {
        assertEquals("/storage/emulated/0/Download/Diablo II", FolderPath.fromTreeDocumentId("primary:Download/Diablo II"))
        assertEquals("/storage/1234-ABCD/Games/D2", FolderPath.fromTreeDocumentId("1234-ABCD:Games/D2"))
        assertEquals("/storage/emulated/0", FolderPath.fromTreeDocumentId("primary:"))
        assertNull(FolderPath.fromTreeDocumentId("nonsense"))
    }
}
