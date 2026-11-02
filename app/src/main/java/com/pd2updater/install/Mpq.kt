package com.pd2updater.install

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

enum class MpqProblem { FORMAT, MISSING, UNSUPPORTED, FULL }

class MpqException(val problem: MpqProblem) : IOException("MPQ: $problem")

/** One member to add: its name and its whole data, stored as it is. */
class NewMember(val name: String, val data: ByteArray)

/**
 * The MPQ archives of Diablo II: read members of the Installer Tome (stored or
 * zlib) and add members to a game archive in place. An addition never breaks
 * the archive: data, then new tables after the end, then the header last.
 * Ported from pd2-launcher `blizzard/mpq.rs`.
 */
class Mpq private constructor(
    private val file: RandomAccessFile,
    private var header: ByteArray,
    private val sector: Long,
    private var hashes: Array<HashEntry>,
    private var blocks: List<BlockEntry>,
) : Closeable {

    internal class HashEntry(val name1: Int, val name2: Int, val localePlatform: Int, val block: Int)

    internal class BlockEntry(val offset: Long, val stored: Long, val size: Long, val flags: Int)

    private class AddPlan(
        val hashes: Array<HashEntry>,
        val blocks: List<BlockEntry>,
        val offsets: List<Long>,
        val hashAt: Long,
    )

    override fun close() = file.close()

    private fun lookup(name: String): Int? {
        val count = hashes.size
        val start = unsigned(hash(name, 0)) % count
        val name1 = hash(name, 1)
        val name2 = hash(name, 2)
        var found: Int? = null
        for (step in 0 until count) {
            val index = ((start + step) % count).toInt()
            val entry = hashes[index]
            if (entry.block == EMPTY) break
            if (entry.block != DELETED && entry.name1 == name1 && entry.name2 == name2) {
                if (entry.localePlatform == 0) return index
                if (found == null) found = index
            }
        }
        return found
    }

    private fun block(name: String): BlockEntry {
        val entry = lookup(name) ?: throw MpqException(MpqProblem.MISSING)
        val block = blocks.getOrNull(hashes[entry].block) ?: throw MpqException(MpqProblem.FORMAT)
        if (block.flags and EXISTS == 0) throw MpqException(MpqProblem.MISSING)
        if (block.flags and (ENCRYPTED or IMPLODED) != 0) throw MpqException(MpqProblem.UNSUPPORTED)
        return block
    }

    fun size(name: String): Long = block(name).size

    fun contains(name: String): Boolean = try {
        block(name)
        true
    } catch (_: MpqException) {
        false
    }

    internal fun usedHashEntries(): Int = hashes.count { it.block != EMPTY }

    /** Copy a member a sector at a time; [progress] gets the bytes copied so far. */
    fun copyMember(name: String, out: OutputStream, progress: (Long) -> Unit = {}): Long {
        val block = block(name)
        val offset = block.offset
        val stored = block.stored
        val size = block.size
        if (block.flags and COMPRESSED == 0) {
            if (stored < size) throw MpqException(MpqProblem.FORMAT)
            val buffer = ByteArray(1 shl 20)
            var done = 0L
            while (done < size) {
                val length = minOf(size - done, buffer.size.toLong()).toInt()
                file.seek(offset + done)
                file.readFully(buffer, 0, length)
                out.write(buffer, 0, length)
                done += length
                progress(done)
            }
            return size
        }
        if (block.flags and SINGLE_UNIT != 0) {
            val raw = read(offset, stored)
            out.write(if (stored == size) raw else inflate(raw, size))
            progress(size)
            return size
        }
        val sectors = (size + sector - 1) / sector
        val entries = sectors + 1 + (if (block.flags and SECTOR_CRC != 0) 1 else 0)
        val tableBytes = ByteBuffer.wrap(read(offset, entries * 4)).order(ByteOrder.LITTLE_ENDIAN)
        val table = LongArray(entries.toInt()) { unsigned(tableBytes.getInt()) }
        var done = 0L
        for (index in 0 until sectors.toInt()) {
            val start = table[index]
            val end = table[index + 1]
            val want = minOf(size - done, sector)
            if (end < start || end > stored) throw MpqException(MpqProblem.FORMAT)
            val raw = read(offset + start, end - start)
            out.write(if (raw.size.toLong() == want) raw else inflate(raw, want))
            done += want
            progress(done)
        }
        return size
    }

    /** A member in memory (at most 64 MiB). */
    fun member(name: String): ByteArray {
        val size = size(name)
        if (size > MEMORY_LIMIT) throw MpqException(MpqProblem.UNSUPPORTED)
        val out = java.io.ByteArrayOutputStream(size.toInt())
        copyMember(name, out)
        return out.toByteArray()
    }

    private fun read(at: Long, length: Long): ByteArray {
        if (length > MEMORY_LIMIT) throw MpqException(MpqProblem.FORMAT)
        val buffer = ByteArray(length.toInt())
        file.seek(at)
        file.readFully(buffer)
        return buffer
    }

    /** Whether members can be added: every slot and offset found, nothing written. */
    fun checkAdd(members: List<NewMember>) {
        planAdd(members)
    }

    private fun planAdd(members: List<NewMember>): AddPlan {
        var end = file.length()
        val newHashes = hashes.copyOf()
        val newBlocks = ArrayList(blocks)
        val offsets = ArrayList<Long>(members.size)
        for (member in members) {
            if (end > MAX_U32) throw MpqException(MpqProblem.FORMAT)
            val size = member.data.size.toLong()
            offsets.add(end)
            val block = newBlocks.size
            newBlocks.add(BlockEntry(end, size, size, EXISTS))
            end += size
            val slot = insertionSlot(newHashes, member.name) ?: throw MpqException(MpqProblem.FULL)
            newHashes[slot] = HashEntry(hash(member.name, 1), hash(member.name, 2), 0, block)
        }
        val blockAt = end + newHashes.size * 16L
        val archiveEnd = blockAt + newBlocks.size * 16L
        if (archiveEnd > MAX_U32) throw MpqException(MpqProblem.FORMAT)
        return AddPlan(newHashes, newBlocks, offsets, end)
    }

    /** Add members, stored, replacing same-named ones: data, tables, then header. */
    fun add(members: List<NewMember>) {
        val plan = planAdd(members)
        members.forEachIndexed { index, member ->
            file.seek(plan.offsets[index])
            file.write(member.data)
        }
        val hashWords = IntArray(plan.hashes.size * 4)
        plan.hashes.forEachIndexed { i, entry ->
            hashWords[i * 4] = entry.name1
            hashWords[i * 4 + 1] = entry.name2
            hashWords[i * 4 + 2] = entry.localePlatform
            hashWords[i * 4 + 3] = entry.block
        }
        cipher(hashWords, hash("(hash table)", 3), false)
        file.seek(plan.hashAt)
        file.write(wordsToBytes(hashWords))
        val blockAt = plan.hashAt + hashWords.size * 4L
        val blockWords = IntArray(plan.blocks.size * 4)
        plan.blocks.forEachIndexed { i, entry ->
            blockWords[i * 4] = entry.offset.toInt()
            blockWords[i * 4 + 1] = entry.stored.toInt()
            blockWords[i * 4 + 2] = entry.size.toInt()
            blockWords[i * 4 + 3] = entry.flags
        }
        cipher(blockWords, hash("(block table)", 3), false)
        file.seek(blockAt)
        file.write(wordsToBytes(blockWords))
        val archiveEnd = blockAt + blockWords.size * 4L
        file.fd.sync()
        val newHeader = ByteBuffer.wrap(header.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        newHeader.putInt(8, archiveEnd.toInt())
        newHeader.putInt(16, plan.hashAt.toInt())
        newHeader.putInt(20, blockAt.toInt())
        newHeader.putInt(28, plan.blocks.size)
        file.seek(0)
        file.write(newHeader.array())
        file.fd.sync()
        header = newHeader.array()
        hashes = plan.hashes
        blocks = plan.blocks
    }

    companion object {
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'Q'.code.toByte(), 0x1a)
        private const val EXISTS = 0x80000000.toInt()
        private const val COMPRESSED = 0x200
        private const val IMPLODED = 0x100
        private const val ENCRYPTED = 0x10000
        private const val SINGLE_UNIT = 0x01000000
        private const val SECTOR_CRC = 0x04000000
        private const val EMPTY = -1
        private const val DELETED = -2
        private const val MEMORY_LIMIT = 64L * 1024 * 1024
        private const val MAX_U32 = 0xffffffffL

        private val CRYPT: IntArray by lazy {
            val table = IntArray(0x500)
            var seed = 0x00100001
            for (first in 0 until 0x100) {
                for (round in 0 until 5) {
                    seed = (seed * 125 + 3) % 0x2aaaab
                    val high = (seed and 0xffff) shl 16
                    seed = (seed * 125 + 3) % 0x2aaaab
                    table[first + round * 0x100] = high or (seed and 0xffff)
                }
            }
            table
        }

        private fun unsigned(value: Int): Long = value.toLong() and 0xffffffffL

        /** The format's hash of a name (upper case, / as \) for a kind. */
        internal fun hash(name: String, kind: Int): Int {
            var seed1 = 0x7fed7fed
            var seed2 = 0xeeeeeeee.toInt()
            for (char in name.toByteArray(Charsets.UTF_8)) {
                var byte = char.toInt() and 0xff
                if (byte in 'a'.code..'z'.code) byte -= 32
                if (byte == '/'.code) byte = '\\'.code
                seed1 = CRYPT[kind * 0x100 + byte] xor (seed1 + seed2)
                seed2 = byte + seed1 + seed2 + (seed2 shl 5) + 3
            }
            return seed1
        }

        internal fun cipher(words: IntArray, startKey: Int, decrypt: Boolean) {
            var key = startKey
            var seed = 0xeeeeeeee.toInt()
            for (i in words.indices) {
                seed += CRYPT[0x400 + (key and 0xff)]
                val mixed = words[i] xor (key + seed)
                val plain = if (decrypt) mixed else words[i]
                words[i] = mixed
                key = ((key.inv() shl 21) + 0x11111111) or (key ushr 11)
                seed = plain + seed + (seed shl 5) + 3
            }
        }

        private fun wordsToBytes(words: IntArray): ByteArray {
            val buffer = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            words.forEach { buffer.putInt(it) }
            return buffer.array()
        }

        private fun readTable(file: RandomAccessFile, at: Long, entries: Int, key: Int): IntArray {
            val bytes = ByteArray(entries * 16)
            file.seek(at)
            file.readFully(bytes)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val words = IntArray(entries * 4) { buffer.getInt() }
            cipher(words, key, true)
            return words
        }

        private fun insertionSlot(hashes: Array<HashEntry>, name: String): Int? {
            val count = hashes.size
            val start = unsigned(hash(name, 0)) % count
            val name1 = hash(name, 1)
            val name2 = hash(name, 2)
            var free: Int? = null
            for (step in 0 until count) {
                val index = ((start + step) % count).toInt()
                val entry = hashes[index]
                if (entry.block == EMPTY) return free ?: index
                if (entry.block == DELETED) {
                    if (free == null) free = index
                } else if (entry.name1 == name1 && entry.name2 == name2 && entry.localePlatform == 0) {
                    return index
                }
            }
            return free
        }

        /** One compressed unit: a mask byte, then zlib data (the only one used). */
        internal fun inflate(raw: ByteArray, size: Long): ByteArray {
            if (raw.isEmpty() || raw[0].toInt() != 0x02) throw MpqException(MpqProblem.UNSUPPORTED)
            val inflater = Inflater()
            try {
                inflater.setInput(raw, 1, raw.size - 1)
                val out = ByteArray(size.toInt())
                var done = 0
                while (done < out.size) {
                    val count = inflater.inflate(out, done, out.size - done)
                    if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                    done += count
                }
                if (done != out.size) throw MpqException(MpqProblem.UNSUPPORTED)
                // Nothing may follow the exact size.
                if (!inflater.finished() && inflater.inflate(ByteArray(1)) != 0) {
                    throw MpqException(MpqProblem.UNSUPPORTED)
                }
                return out
            } catch (_: DataFormatException) {
                throw MpqException(MpqProblem.UNSUPPORTED)
            } finally {
                inflater.end()
            }
        }

        /** Read the header and tables of the archive at the start of a file. */
        fun open(path: File, writable: Boolean = false): Mpq {
            val file = RandomAccessFile(path, if (writable) "rw" else "r")
            try {
                return open(file)
            } catch (error: Throwable) {
                file.close()
                throw error
            }
        }

        private fun open(file: RandomAccessFile): Mpq {
            val length = file.length()
            val raw = ByteArray(44)
            file.seek(0)
            var read = 0
            while (read < raw.size) {
                val count = file.read(raw, read, raw.size - read)
                if (count < 0) break
                read += count
            }
            if (read < 32 || !raw.copyOfRange(0, 4).contentEquals(MAGIC)) throw MpqException(MpqProblem.FORMAT)
            val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val headerSize = header.getInt(4)
            val version = header.getShort(12).toInt() and 0xffff
            val shift = header.getShort(14).toInt() and 0xffff
            val known = (version == 0 && headerSize == 32) ||
                (version == 1 && headerSize == 44 && read == 44 && (32 until 44).all { raw[it].toInt() == 0 })
            if (!known || shift > 16) throw MpqException(MpqProblem.FORMAT)
            val hashAt = unsigned(header.getInt(16))
            val blockAt = unsigned(header.getInt(20))
            val hashCount = unsigned(header.getInt(24))
            val blockCount = unsigned(header.getInt(28))
            if (hashCount == 0L || hashCount and (hashCount - 1) != 0L ||
                hashAt + hashCount * 16 > length || blockAt + blockCount * 16 > length
            ) {
                throw MpqException(MpqProblem.FORMAT)
            }
            val hashWords = readTable(file, hashAt, hashCount.toInt(), hash("(hash table)", 3))
            val hashes = Array(hashCount.toInt()) {
                HashEntry(hashWords[it * 4], hashWords[it * 4 + 1], hashWords[it * 4 + 2], hashWords[it * 4 + 3])
            }
            val blockWords = readTable(file, blockAt, blockCount.toInt(), hash("(block table)", 3))
            val blocks = List(blockCount.toInt()) {
                BlockEntry(
                    unsigned(blockWords[it * 4]),
                    unsigned(blockWords[it * 4 + 1]),
                    unsigned(blockWords[it * 4 + 2]),
                    blockWords[it * 4 + 3],
                )
            }
            return Mpq(file, raw.copyOfRange(0, 32), 512L shl shift, hashes, blocks)
        }
    }
}
