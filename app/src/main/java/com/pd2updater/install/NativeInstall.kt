package com.pd2updater.install

import com.pd2updater.Cancelled
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The install cannot go on; [message] says why, in the player's language. */
class InstallFailure(message: String) : IOException(message)

/**
 * One product's install script run into the game folder, without Blizzard's
 * installer or Wine. Whatever can fail without a change comes first.
 * Ported from pd2-launcher `blizzard/native.rs`.
 */
object NativeInstall {
    private const val WRITE_BUFFER = 1 shl 20

    fun checkPlan(plan: List<Operation>, expected: ExpectedPlan) {
        fun shape(what: String) = InstallFailure("Kịch bản cài không như dự kiến: $what")
        if (plan.count { it is Operation.Extract } != expected.extracts ||
            plan.count { it is Operation.Add } != expected.adds ||
            plan.count { it is Operation.Delete } != expected.deletes
        ) {
            throw shape("số thao tác")
        }
        var keys = 0
        var owners = 0
        for (operation in plan) {
            when (operation) {
                is Operation.Add -> if (operation.container != expected.container) throw shape("archive khác")
                is Operation.Key -> {
                    if (operation.container != expected.container || operation.into != expected.keyMember ||
                        operation.product != expected.product
                    ) {
                        throw shape("mục CD key")
                    }
                    keys++
                }
                is Operation.Owner -> {
                    if (operation.container != expected.container || operation.into != Keystore.OWNER_MEMBER) {
                        throw shape("mục chủ sở hữu")
                    }
                    owners++
                }
                else -> {}
            }
        }
        if (keys != 1 || owners != 1) throw shape("cần đúng một key và một chủ sở hữu")
    }

    /**
     * Run [plan] into [game] with a normalized, checked [key]: deletes and files
     * in order, then the archive's members, key and owner in one addition.
     */
    fun run(
        tome: Mpq,
        plan: List<Operation>,
        game: File,
        key: String,
        cancel: AtomicBoolean,
        progress: (done: Long, total: Long) -> Unit,
    ) {
        var total = 0L
        for (operation in plan) {
            when (operation) {
                is Operation.Extract -> {
                    val length = tome.size(operation.from)
                    if (operation.size != null && operation.size != length) {
                        throw InstallFailure("File trong bộ cài sai kích thước: ${operation.to}")
                    }
                    total += length
                }
                is Operation.Add -> total += tome.size(operation.from)
                else -> {}
            }
        }
        val (container, members) = archiveMembers(tome, plan, key)
        val madeByPlan = plan.any { it is Operation.Extract && it.to == container }
        if (!madeByPlan) Mpq.open(File(game, container), writable = true).use { it.checkAdd(members) }

        var done = 0L
        for (operation in plan) {
            if (cancel.get()) throw Cancelled()
            when (operation) {
                is Operation.Delete -> removeIfPresent(File(game, operation.name))
                is Operation.Extract -> {
                    val target = File(game, operation.to)
                    removeIfPresent(target)
                    val base = done
                    FileOutputStream(target).use { file ->
                        val out = BufferedOutputStream(file, WRITE_BUFFER)
                        val length = tome.copyMember(operation.from, out) { copied ->
                            if (cancel.get()) throw Cancelled()
                            progress(base + copied, total)
                        }
                        out.flush()
                        file.fd.sync()
                        done += length
                    }
                }
                else -> {}
            }
        }
        if (cancel.get()) throw Cancelled()
        Mpq.open(File(game, container), writable = true).use { it.add(members) }
        progress(total, total)
    }

    /** The archive the plan adds to, and its members: the Tome's, the wrapped key and owner. */
    private fun archiveMembers(tome: Mpq, plan: List<Operation>, key: String): Pair<String, List<NewMember>> {
        val members = ArrayList<NewMember>()
        var container: String? = null
        for (operation in plan) {
            when (operation) {
                is Operation.Add -> {
                    val data = tome.member(operation.from)
                    if (operation.size != null && operation.size != data.size.toLong()) {
                        throw InstallFailure("Mục trong bộ cài sai kích thước: ${operation.to}")
                    }
                    container = operation.container
                    members.add(NewMember(operation.to, data))
                }
                is Operation.Key -> {
                    container = operation.container
                    members.add(NewMember(operation.into, Keystore.wrap(key.toByteArray(Charsets.US_ASCII))))
                }
                is Operation.Owner -> {
                    container = operation.container
                    members.add(NewMember(operation.into, Keystore.wrap(Keystore.OWNER.toByteArray(Charsets.US_ASCII))))
                }
                else -> {}
            }
        }
        return (container ?: throw InstallFailure("Kịch bản cài không có archive")) to members
    }

    private fun removeIfPresent(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Không xóa được ${file.name}")
    }
}
