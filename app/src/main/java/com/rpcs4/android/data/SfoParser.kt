package com.rpcs4.android.data

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin parser for PS4 param.sfo (System File Object).
 *
 * Format reference (matches upstream core/Loaders/SFO/SFOLoader.cpp):
 *
 *   Header (0x14 bytes):
 *     u32 magic "\0PSF" (0x00505346)
 *     u32 version
 *     u32 keyTableStart
 *     u32 dataTableStart
 *     u32 entryCount
 *
 *   Index table, 16 bytes per entry:
 *     u16 keyOffset
 *     u16 dataFmt   // 0x0400 = UTF-8 special mode, 0x0402 = int32, 0x0404 = UTF-8
 *     u32 dataLen
 *     u32 dataMaxLen
 *     u32 dataOffset
 */
object SfoParser {

    private const val MAGIC = 0x00505346

    /** Keys we care about when building library cards. */
    const val KEY_TITLE = "TITLE"
    const val KEY_TITLE_ID = "TITLE_ID"
    const val KEY_VERSION = "VERSION"
    const val KEY_APP_VER = "APP_VER"

    fun parse(input: InputStream): Map<String, String> {
        val bytes = input.use { it.readBytes() }
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): Map<String, String> {
        if (bytes.size < 0x14) throw IllegalArgumentException("param.sfo too small (${bytes.size} bytes)")

        val le: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = le.int
        require(magic == MAGIC) { "Not a param.sfo buffer (magic=0x%08x)".format(magic) }
        le.int // version
        val keyTableStart = le.int
        val dataTableStart = le.int
        val entryCount = le.int

        val out = LinkedHashMap<String, String>()
        repeat(entryCount) {
            val keyOffset = le.short.toInt() and 0xFFFF
            val dataFmt = le.short.toInt() and 0xFFFF
            val dataLen = le.int
            val dataMaxLen = le.int // unused
            val dataOffset = le.int

            val keyStart = keyTableStart + keyOffset
            var keyEnd = keyStart
            while (keyEnd < bytes.size && bytes[keyEnd] != 0.toByte()) keyEnd++
            if (keyEnd >= bytes.size) return@repeat
            val key = String(bytes, keyStart, keyEnd - keyStart, Charsets.UTF_8)

            val valueStart = dataTableStart + dataOffset
            val value = when (dataFmt) {
                0x0402 -> if (valueStart + 4 <= bytes.size) {
                    val intBuf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    intBuf.position(valueStart)
                    intBuf.int.toString()
                } else ""

                else -> {
                    val end = minOf(valueStart + maxOf(dataLen, 1), bytes.size)
                    var stop = valueStart
                    while (stop < end && bytes[stop] != 0.toByte()) stop++
                    if (stop > valueStart) String(bytes, valueStart, stop - valueStart, Charsets.UTF_8) else ""
                }
            }

            out[key] = value
        }
        return out
    }
}
