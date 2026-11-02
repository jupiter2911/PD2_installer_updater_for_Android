package com.pd2updater

/** The languages of the app; [label] is shown in the language itself. */
enum class Language(val label: String, private val tag: String) {
    ENGLISH("English", "en"),
    VIETNAMESE("Tiếng Việt", "vi"),
    ;

    /** With two languages, what the switch button offers. */
    val other: Language get() = if (this == ENGLISH) VIETNAMESE else ENGLISH

    companion object {
        /** The saved choice, else the phone's language when the app has it, else English. */
        fun choose(saved: String?, phoneLanguage: String): Language =
            entries.firstOrNull { it.name == saved }
                ?: entries.firstOrNull { it.tag == phoneLanguage }
                ?: ENGLISH
    }
}

object Lang {
    @Volatile
    var current: Language = Language.ENGLISH

    fun t(english: String, vietnamese: String): String =
        if (current == Language.VIETNAMESE) vietnamese else english
}

/** A text in the current language, with both versions beside the code that uses it. */
fun t(english: String, vietnamese: String): String = Lang.t(english, vietnamese)
