package com.pd2updater

/**
 * The file path of a folder the system picker returned, from its tree document id: `primary:Download/Diablo II`
 * is `/storage/emulated/0/Download/Diablo II`, `1234-ABCD:Games` is `/storage/1234-ABCD/Games`
 * (an SD card). The app reads the folder by path, with All files access.
 */
object FolderPath {
    fun fromTreeDocumentId(id: String): String? {
        val colon = id.indexOf(':')
        if (colon <= 0) return null
        val volume = id.substring(0, colon)
        val relative = id.substring(colon + 1).trim('/')
        val root = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
        return if (relative.isEmpty()) root else "$root/$relative"
    }
}
