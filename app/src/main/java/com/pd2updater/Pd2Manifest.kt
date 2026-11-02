package com.pd2updater

/**
 * PD2's client manifest, as the desktop PD2 Launcher reads it (pd2-launcher src/manifest.rs):
 * `{"checksum": ["<32 lowercase hex md5>  <flat file name>", ...]}` at [MANIFEST_URL]; each
 * file is served at `ORIGIN/<percent-encoded name>` and lands in the ProjectD2 folder.
 */
object Pd2Manifest {
    const val ORIGIN = "https://pd2-client-files.projectdiablo2.com"
    const val MANIFEST_URL = "$ORIGIN/metadata.json"
    private const val MAX_ENTRIES = 4096
    private const val MAX_NAME = 255

    /**
     * Files the player owns, and files the official PD2 launcher replaces itself: kept as they
     * are when present, written only when missing (pd2-launcher src/update.rs, src/audit.rs).
     */
    val PROTECTED = setOf(
        "D2.LNG", "BnetLog.txt", "ProjectDiablo.cfg", "ddraw.ini", "default.filter",
        "loot.filter", "UI.ini", "d2gl.yaml", "d2gl.json", "PD2Shared.dll",
        "PD2Launcher.exe", "UpdateUtility.exe", "SteamPD2.exe", "TempLauncher.exe",
        "launcher-update-debug.log", "update.log",
    )

    data class Entry(val name: String, val md5: String) {
        val url: String get() = "$ORIGIN/${encode(name)}"
        val isProtected: Boolean get() = name in PROTECTED
    }

    class InvalidManifest(message: String) : Exception(message)

    fun parseLine(line: String): Entry {
        if (line.length < 35 || line[32] != ' ' || line[33] != ' ') {
            throw InvalidManifest("bad line: $line")
        }
        val md5 = line.substring(0, 32)
        if (!md5.all { it in '0'..'9' || it in 'a'..'f' }) throw InvalidManifest("bad md5: $line")
        val name = line.substring(34)
        if (name.isEmpty() || name.length > MAX_NAME || name == "." || name == ".." ||
            name.any { it == '/' || it == '\\' || it.code !in 0x20..0x7e }
        ) {
            throw InvalidManifest("unsafe file name: $name")
        }
        return Entry(name, md5)
    }

    fun parseLines(lines: List<String>): List<Entry> {
        if (lines.isEmpty() || lines.size > MAX_ENTRIES) throw InvalidManifest("entry count ${lines.size}")
        val seen = HashSet<String>()
        return lines.map { line ->
            parseLine(line).also { entry ->
                if (!seen.add(entry.name.lowercase())) throw InvalidManifest("duplicate: ${entry.name}")
            }
        }
    }

    fun encode(name: String): String = buildString {
        for (byte in name.toByteArray(Charsets.US_ASCII)) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") append(c)
            else append("%%%02X".format(byte.toInt() and 0xff))
        }
    }
}
