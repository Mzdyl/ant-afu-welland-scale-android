package io.github.afuwellandscale.util

fun ByteArray.hex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

fun Int.bit(index: Int): Boolean = (this and (1 shl index)) != 0

fun setBit(value: Int, index: Int): Int = value or (1 shl index)

fun u16(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

fun u32(data: ByteArray, offset: Int): Long =
    ((data[offset].toLong() and 0xFF) shl 24) or
        ((data[offset + 1].toLong() and 0xFF) shl 16) or
        ((data[offset + 2].toLong() and 0xFF) shl 8) or
        (data[offset + 3].toLong() and 0xFF)

fun Long.toBytes4(): ByteArray = byteArrayOf(
    ((this ushr 24) and 0xFF).toByte(),
    ((this ushr 16) and 0xFF).toByte(),
    ((this ushr 8) and 0xFF).toByte(),
    (this and 0xFF).toByte(),
)

fun Int.toBytes2(): ByteArray = byteArrayOf(
    ((this ushr 8) and 0xFF).toByte(),
    (this and 0xFF).toByte(),
)
