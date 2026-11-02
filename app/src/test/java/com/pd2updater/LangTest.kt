package com.pd2updater

import com.pd2updater.install.GameInstaller
import com.pd2updater.install.KeyField
import com.pd2updater.install.KeyProblem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LangTest {
    @After
    fun reset() {
        Lang.current = Language.ENGLISH
    }

    @Test
    fun theTextFollowsTheCurrentLanguage() {
        Lang.current = Language.ENGLISH
        assertEquals("Stop", Lang.t("Stop", "Dừng"))
        Lang.current = Language.VIETNAMESE
        assertEquals("Dừng", Lang.t("Stop", "Dừng"))
    }

    @Test
    fun aSavedChoiceWinsOverThePhonesLanguage() {
        assertEquals(Language.ENGLISH, Language.choose("ENGLISH", "vi"))
        assertEquals(Language.VIETNAMESE, Language.choose("VIETNAMESE", "en"))
    }

    @Test
    fun withoutAChoiceTheAppFollowsThePhoneAndElseUsesEnglish() {
        assertEquals(Language.VIETNAMESE, Language.choose(null, "vi"))
        assertEquals(Language.ENGLISH, Language.choose(null, "en"))
        assertEquals(Language.ENGLISH, Language.choose(null, "fr"))
        assertEquals(Language.ENGLISH, Language.choose("KLINGON", "fr"))
    }

    @Test
    fun theOtherLanguageIsTheOneTheButtonOffers() {
        assertEquals(Language.VIETNAMESE, Language.ENGLISH.other)
        assertEquals(Language.ENGLISH, Language.VIETNAMESE.other)
    }

    @Test
    fun aKeyProblemIsToldInTheCurrentLanguage() {
        Lang.current = Language.ENGLISH
        val english = GameInstaller.describe(KeyField.BASE_GAME, KeyProblem.WRONG_LENGTH)
        assertTrue(english, english.contains("26 characters"))
        Lang.current = Language.VIETNAMESE
        val vietnamese = GameInstaller.describe(KeyField.BASE_GAME, KeyProblem.WRONG_LENGTH)
        assertTrue(vietnamese, vietnamese.contains("26 ký tự"))
        assertFalse(vietnamese, vietnamese.contains("characters"))
    }

    /** Every Vietnamese text in the code has its English beside it, inside Lang.t. */
    @Test
    fun noVietnameseIsLeftOutsideATranslation() {
        val sources = java.io.File("src/main/java").walkTopDown().filter { it.extension == "kt" && it.name != "Lang.kt" }.toList()
        assertTrue(sources.isNotEmpty())
        val vietnamese = Regex("[ăâđêôơưĂÂĐÊÔƠƯàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ]", RegexOption.IGNORE_CASE)
        val translated = Regex("\\bt\\(\"")
        val bare = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val text = line.substringBefore("//")
                if (vietnamese.containsMatchIn(text) && !translated.containsMatchIn(text) && !translationArgument(text)) {
                    "${file.name}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertEquals(bare.joinToString("\n"), 0, bare.size)
    }

    /** The second line of a call that is split: `"English", "Tiếng Việt"` or just `"Tiếng Việt",`. */
    private fun translationArgument(line: String) = line.trim().startsWith("\"")
}
