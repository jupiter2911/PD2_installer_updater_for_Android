package com.pd2updater.install

import com.pd2updater.Cancelled
import com.pd2updater.install.MpqTestSupport.Member
import com.pd2updater.install.MpqTestSupport.build
import com.pd2updater.install.MpqTestSupport.payload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class NativeInstallTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val keyMember = "data\\global\\key.wav"
    private val expected = ExpectedPlan(2, 1, 1, "data.mpq", 24, keyMember)
    private val key = "ABCDEFGHJKMNPRTVWXYZ234567"
    private val archiveData = payload(2_000, 1)
    private val exeData = payload(500, 2)
    private val memberData = payload(300, 3)

    private fun samplePlan(): List<Operation> = listOf(
        Operation.Delete("old.dll"),
        Operation.Extract("Common\\data.mpq", "data.mpq", null),
        Operation.Extract("PC\\Game.exe", "Game.exe", exeData.size.toLong()),
        Operation.Add("data.mpq", "PC\\a.dll", "a.dll", memberData.size.toLong()),
        Operation.Key("data.mpq", keyMember, 24),
        Operation.Owner("data.mpq", Keystore.OWNER_MEMBER),
    )

    private fun tome(): File {
        val archive = build(listOf(Member("x.txt", "kept".toByteArray())), 16)
        val file = temp.newFile("tome.mpq")
        file.writeBytes(
            build(
                listOf(
                    Member("Common\\data.mpq", archive),
                    Member("PC\\Game.exe", exeData, true),
                    Member("PC\\a.dll", memberData),
                ),
                16,
            ),
        )
        return file
    }

    private fun refusal(plan: List<Operation>) =
        assertThrows(InstallFailure::class.java) { NativeInstall.checkPlan(plan, expected) }

    @Test
    fun aPlanMustHaveItsProductsShape() {
        NativeInstall.checkPlan(samplePlan(), expected)
        refusal(samplePlan().filterNot { it is Operation.Delete })
        refusal(samplePlan().filterNot { it is Operation.Key })
        refusal(samplePlan().filterNot { it is Operation.Owner })
        refusal(samplePlan().map { if (it is Operation.Add) it.copy(container = "other.mpq") else it })
        refusal(samplePlan().map { if (it is Operation.Key) it.copy(product = 25) else it })
        refusal(samplePlan().map { if (it is Operation.Key) it.copy(into = "elsewhere") else it })
    }

    @Test
    fun aPlanRunsIntoTheGameFolder() {
        val game = temp.newFolder("game")
        File(game, "old.dll").writeText("old")
        val seen = ArrayList<Pair<Long, Long>>()
        Mpq.open(tome()).use { tome ->
            NativeInstall.run(tome, samplePlan(), game, key, AtomicBoolean(false)) { done, total -> seen.add(done to total) }
        }
        assertFalse(File(game, "old.dll").exists())
        assertArrayEquals(exeData, File(game, "Game.exe").readBytes())
        Mpq.open(File(game, "data.mpq")).use { archive ->
            assertArrayEquals("kept".toByteArray(), archive.member("x.txt"))
            assertArrayEquals(memberData, archive.member("a.dll"))
            assertArrayEquals(Keystore.wrap(key.toByteArray()), archive.member(keyMember))
            assertArrayEquals(Keystore.wrap("Player".toByteArray()), archive.member(Keystore.OWNER_MEMBER))
        }
        assertEquals(seen.last().second, seen.last().first)
    }

    @Test
    fun anExpansionAddsToAnArchiveAlreadyInTheFolder() {
        val game = temp.newFolder("game")
        File(game, "data.mpq").writeBytes(build(listOf(Member("x.txt", "kept".toByteArray())), 16))
        val plan = samplePlan().filterNot { it is Operation.Extract && it.to == "data.mpq" }
        Mpq.open(tome()).use { tome -> NativeInstall.run(tome, plan, game, key, AtomicBoolean(false)) { _, _ -> } }
        Mpq.open(File(game, "data.mpq")).use {
            assertArrayEquals(memberData, it.member("a.dll"))
            assertTrue(it.contains(keyMember))
        }
    }

    @Test
    fun aFullArchiveFailsBeforeAnyChange() {
        val game = temp.newFolder("game")
        File(game, "old.dll").writeText("old")
        val full = build(listOf(Member("p", ByteArray(1)), Member("q", ByteArray(1))), 2)
        File(game, "data.mpq").writeBytes(full)
        val plan = samplePlan().filterNot { it is Operation.Extract && it.to == "data.mpq" }
        Mpq.open(tome()).use { tome ->
            assertThrows(MpqException::class.java) {
                NativeInstall.run(tome, plan, game, key, AtomicBoolean(false)) { _, _ -> }
            }
        }
        assertTrue(File(game, "old.dll").exists())
        assertFalse(File(game, "Game.exe").exists())
    }

    @Test
    fun aMissingMemberOrAWrongSizeFailsBeforeAnyChange() {
        val game = temp.newFolder("game")
        File(game, "old.dll").writeText("old")
        val sized = samplePlan().map { if (it is Operation.Extract && it.to == "Game.exe") it.copy(size = 1) else it }
        val missing = samplePlan().map { if (it is Operation.Add) it.copy(from = "PC\\none.dll") else it }
        Mpq.open(tome()).use { tome ->
            assertThrows(InstallFailure::class.java) {
                NativeInstall.run(tome, sized, game, key, AtomicBoolean(false)) { _, _ -> }
            }
            assertThrows(MpqException::class.java) {
                NativeInstall.run(tome, missing, game, key, AtomicBoolean(false)) { _, _ -> }
            }
        }
        assertTrue(File(game, "old.dll").exists())
    }

    @Test
    fun aStopEndsTheInstallAndDoesNotAddToTheArchive() {
        val game = temp.newFolder("game")
        val cancel = AtomicBoolean(false)
        Mpq.open(tome()).use { tome ->
            assertThrows(Cancelled::class.java) {
                NativeInstall.run(tome, samplePlan(), game, key, cancel) { done, _ ->
                    if (done > 0) cancel.set(true)
                }
            }
        }
        Mpq.open(File(game, "data.mpq")).use { assertFalse(it.contains(keyMember)) }
    }

    @Test
    fun theRealProductShapesAreTheOnesOfTheScripts() {
        assertEquals(ExpectedPlan(13, 25, 0, "d2data.mpq", 24, Keystore.BASE_KEY_MEMBER), Products.BASE_GAME.expected)
        assertEquals(ExpectedPlan(11, 21, 46, "d2char.mpq", 25, Keystore.EXPANSION_KEY_MEMBER), Products.EXPANSION.expected)
    }
}
