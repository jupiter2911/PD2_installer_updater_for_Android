package com.pd2updater.install

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/** Builds synthetic format-0 MPQ archives for tests. */
object MpqTestSupport {
    class Member(val name: String, val data: ByteArray, val zlib: Boolean = false)

    private const val EXISTS = 0x80000000.toInt()
    private const val COMPRESSED = 0x200
    private const val EMPTY = -1

    private fun words(values: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(6)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    fun build(members: List<Member>, slots: Int, sectorShift: Int = 3): ByteArray {
        val sector = 512 shl sectorShift
        val out = ByteArrayOutputStream()
        out.write(ByteArray(32))
        val blocks = ArrayList<IntArray>()
        for (member in members) {
            val offset = out.size()
            val data = member.data
            if (member.zlib) {
                val sectors = (data.size + sector - 1) / sector
                val table = ArrayList<Int>()
                val body = ByteArrayOutputStream()
                var at = (sectors + 1) * 4
                var from = 0
                while (from < data.size) {
                    val chunk = data.copyOfRange(from, minOf(from + sector, data.size))
                    table.add(at)
                    val packed = byteArrayOf(0x02) + deflate(chunk)
                    val unit = if (packed.size < chunk.size) packed else chunk
                    at += unit.size
                    body.write(unit)
                    from += sector
                }
                table.add(at)
                out.write(words(table.toIntArray()))
                out.write(body.toByteArray())
                blocks.add(intArrayOf(offset, at, data.size, EXISTS or COMPRESSED))
            } else {
                out.write(data)
                blocks.add(intArrayOf(offset, data.size, data.size, EXISTS))
            }
        }
        val hashes = Array(slots) { intArrayOf(EMPTY, EMPTY, -1, EMPTY) }
        members.forEachIndexed { block, member ->
            var index = ((Mpq.hash(member.name, 0).toLong() and 0xffffffffL) % slots).toInt()
            while (hashes[index][3] != EMPTY) index = (index + 1) % slots
            hashes[index] = intArrayOf(Mpq.hash(member.name, 1), Mpq.hash(member.name, 2), 0, block)
        }
        val hashAt = out.size()
        val hashWords = hashes.flatMap { it.toList() }.toIntArray()
        Mpq.cipher(hashWords, Mpq.hash("(hash table)", 3), false)
        out.write(words(hashWords))
        val blockAt = out.size()
        val blockWords = blocks.flatMap { it.toList() }.toIntArray()
        Mpq.cipher(blockWords, Mpq.hash("(block table)", 3), false)
        out.write(words(blockWords))
        val bytes = out.toByteArray()
        val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'Q'.code.toByte(), 0x1a))
        header.putInt(32).putInt(bytes.size).putShort(0).putShort(sectorShift.toShort())
        header.putInt(hashAt).putInt(blockAt).putInt(slots).putInt(blocks.size)
        header.array().copyInto(bytes)
        return bytes
    }

    fun payload(length: Int, seed: Int): ByteArray =
        ByteArray(length) { (((it / 7) and 0xff) * 13 + seed).toByte() }
}
