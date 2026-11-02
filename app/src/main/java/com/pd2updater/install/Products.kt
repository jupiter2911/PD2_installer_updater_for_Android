package com.pd2updater.install

/** What the download accepts for one product. */
data class ProductPin(
    val infohash: String,
    val name: String,
    val pieceLength: Long,
    val pieces: Int,
    val total: Long,
    /** The only piece server: `<base>/<n>` is piece n. */
    val base: String,
)

/** A file of the payload that is written, with its pinned size and SHA-256. */
data class KeptFile(val path: String, val size: Long, val sha256: String)

/** The shape of one product's install plan. */
data class ExpectedPlan(
    val extracts: Int,
    val adds: Int,
    val deletes: Int,
    val container: String,
    val product: Int,
    val keyMember: String,
)

/** One installer: where it comes from, what it must be, what it must do. */
data class Product(
    val id: String,
    val field: KeyField,
    val wrapperUrl: String,
    val pin: ProductPin,
    val kept: List<KeptFile>,
    val expected: ExpectedPlan,
) {
    val tomePath: String get() = Products.TOME
}

/** The pins of pd2-launcher `blizzard.rs` and `install/adapters.rs`. */
object Products {
    const val PIECE_HOST = "rogue.blizzard.com.edgesuite.net"
    const val INSTALLER = "Installer.exe"
    const val TOME = "Installer Tome.mpq"
    const val SCRIPT_MEMBER = "InstallCD\\InstallerFileList\\InstallerFileList.xml"
    private const val SKIPPED_PREFIX = "Installer.app/"

    private val DIRECTX = listOf(
        KeptFile("DirectX/BDA.cab", 703_080, "0f3e9859711233a80f4065ee14402d09b893e730c1a4834460af975e7b97e77b"),
        KeptFile("DirectX/BDANT.cab", 1_156_363, "7262a0b2963f1240b17d8c7644c09e473f9706022ff3cd2cdab156f29f15b5a4"),
        KeptFile("DirectX/BDAXP.cab", 976_020, "fd472360f4d706a838dd934337473ef6aa2f3dd6aaa669456b3aef6d91756a74"),
        KeptFile("DirectX/DSETUP.dll", 62_976, "ad672597c675a160dbc9b5321c4b1720f05e11af22fb8723ab93d0510bab4e01"),
        KeptFile("DirectX/DirectX.cab", 15_493_481, "0e8c0ea0a9bc50012fd9286d8941736f8a99d43af10413f9062db7773199efc2"),
        KeptFile("DirectX/ManagedDX.CAB", 3_432_656, "41174c8582039a6c9484634292df28c55ce48c784bb3d9cef16d65ec81fc8a1e"),
        KeptFile("DirectX/dsetup32.dll", 2_242_560, "79ba6d7ef7f8ffeb7cfec21b14003a121314b831e3522c24bb0a581406cc6c9a"),
        KeptFile("DirectX/dxnt.cab", 13_265_040, "1cd52d218757a6108108ed7daa1f96aff4ebcc738888979f74126b983834d579"),
        KeptFile("DirectX/dxsetup.exe", 472_576, "37a000d745c65286f029acdc126d355fe8368dee22a38d05be9ece0ffe02f286"),
    )

    val BASE_GAME = Product(
        id = "d2",
        field = KeyField.BASE_GAME,
        wrapperUrl = "https://downloader.battle.net/download/getLegacy?os=win&locale=enUS&product=D2DV",
        pin = ProductPin(
            "84ce44e25d34d9a292d7aad5d31e54e866c10c9c",
            "D2-1.14b-Installer-enUS",
            262_144,
            6_088,
            1_595_721_859,
            "http://$PIECE_HOST/applications/Diablo2/1.14B/D2/enUS",
        ),
        kept = DIRECTX + listOf(
            KeptFile(INSTALLER, 2_664_936, "f845e8bc0ab622989fd25a81b24161ec7abbd1a6cc07e6d771df3962859f5f68"),
            KeptFile(TOME, 1_551_865_181, "1a20e8e53a0c6b458c6ad0be37e873f127ed819c006505c75d181e9f0a9a1378"),
        ),
        expected = ExpectedPlan(13, 25, 0, "d2data.mpq", 24, Keystore.BASE_KEY_MEMBER),
    )

    val EXPANSION = Product(
        id = "lod",
        field = KeyField.EXPANSION,
        wrapperUrl = "https://downloader.battle.net/download/getLegacy?os=win&locale=enUS&product=D2XP",
        pin = ProductPin(
            "82c051339f174d5cf9c383bbf752b9b6b1db2b54",
            "D2LOD-1.14b-Installer-enUS",
            262_144,
            2_039,
            534_264_111,
            "http://$PIECE_HOST/applications/Diablo2/1.14B/LOD/enUS",
        ),
        kept = DIRECTX + listOf(
            KeptFile(INSTALLER, 2_671_080, "a825d97018f2638d1ef067f3fc199cd3d613fe31748ab5494c61fe3250e7bbe5"),
            KeptFile(TOME, 490_399_607, "c286c74eba99c7905d61178d62527977df710a64b49bf557ea02c2fef90b3071"),
        ),
        expected = ExpectedPlan(11, 21, 46, "d2char.mpq", 25, Keystore.EXPANSION_KEY_MEMBER),
    )

    /** What differs from the pin, or null: every file not kept must be the Mac installer. */
    fun pinMismatch(torrent: Torrent, pin: ProductPin, kept: List<KeptFile>): String? {
        if (torrent.infohashHex != pin.infohash) return "infohash"
        if (torrent.name != pin.name) return "name"
        if (torrent.pieceLength != pin.pieceLength || torrent.pieceCount != pin.pieces || torrent.total != pin.total) {
            return "pieces"
        }
        if (torrent.servers != listOf(pin.base)) return "server"
        for (file in kept) {
            val found = torrent.files.filter { it.path == file.path }
            if (found.size != 1 || found[0].length != file.size) return "files"
        }
        val keptPaths = kept.map { it.path }.toSet()
        if (!torrent.files.all { it.path in keptPaths || it.path.startsWith(SKIPPED_PREFIX) }) return "files"
        return null
    }
}
