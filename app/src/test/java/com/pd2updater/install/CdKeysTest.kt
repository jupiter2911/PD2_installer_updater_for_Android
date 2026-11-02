package com.pd2updater.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CdKeysTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun decode(key: String) = CdKeys.decode(CdKeys.digits(key))

    @Test
    fun decodesTheKnownVectors() {
        val twos = decode("2".repeat(26))
        assertEquals(3293, twos.product)
        assertEquals(0x00c58620, twos.public)
        assertEquals("969c435fa6c175b8adfb", hex(twos.value))

        val zeds = decode("Z".repeat(26))
        assertEquals(21039, zeds.product)
        assertEquals(0x01f76232, zeds.public)
        assertEquals("c3634a7f034504c14d40", hex(zeds.value))

        val mixed = decode("246789BCDEFGHJKMNPRTVWXYZ2")
        assertEquals(5775, mixed.product)
        assertEquals(0x001146db, mixed.public)
        assertEquals("5ab9c310857095acf08d", hex(mixed.value))
    }

    @Test
    fun normalizeRemovesDashesAndSpacesAndUppercases() {
        assertEquals("ABC2", CdKeys.normalize("a-b c-2"))
    }

    @Test
    fun formProblemsAreNamed() {
        assertEquals(KeyProblem.WRONG_LENGTH, CdKeys.problem(KeyField.BASE_GAME, "222"))
        assertEquals(KeyProblem.OLD_KEY_LENGTH, CdKeys.problem(KeyField.BASE_GAME, "2222-2222-2222-2222"))
        for (bad in "AIO015S#") {
            assertEquals(
                "character $bad",
                KeyProblem.INVALID_CHARACTER,
                CdKeys.problem(KeyField.BASE_GAME, "2".repeat(25) + bad),
            )
        }
    }

    @Test
    fun aDecodedKeyOfAnotherProductIsRefused() {
        assertEquals(KeyProblem.WRONG_PRODUCT, CdKeys.problem(KeyField.BASE_GAME, "2".repeat(26)))
    }

    @Test
    fun theProductTableMatchesEachField() {
        assertNull(CdKeys.productProblem(KeyField.BASE_GAME, 24))
        assertNull(CdKeys.productProblem(KeyField.EXPANSION, 25))
        assertEquals(KeyProblem.EXPANSION_KEY_IN_BASE_FIELD, CdKeys.productProblem(KeyField.BASE_GAME, 25))
        assertEquals(KeyProblem.BASE_KEY_IN_EXPANSION_FIELD, CdKeys.productProblem(KeyField.EXPANSION, 24))
        assertEquals(KeyProblem.WRONG_PRODUCT, CdKeys.productProblem(KeyField.BASE_GAME, 7))
    }
}
