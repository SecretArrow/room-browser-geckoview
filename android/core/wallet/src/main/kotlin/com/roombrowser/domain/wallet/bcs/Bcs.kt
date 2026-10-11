package com.roombrowser.domain.wallet.bcs

import java.io.ByteArrayOutputStream

/**
 * BCS (Binary Canonical Serialization) writer/reader — the serialization used
 * by Aptos and Sui. Implements exactly the primitives their transaction and
 * message formats need: ULEB128 lengths, fixed-width integers (LE), bool,
 * bytes, UTF-8 strings, and enums.
 */
class BcsWriter {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    fun write(byte: Byte) = apply { out.write(byte.toInt() and 0xff) }

    fun writeBytes(data: ByteArray) = apply {
        writeUleb128(data.size.toLong())
        out.write(data)
    }

    fun writeRaw(data: ByteArray) = apply { out.write(data) }

    fun writeU8(value: Int) = apply { out.write(value and 0xff) }

    fun writeBool(value: Boolean) = apply { out.write(if (value) 1 else 0) }

    fun writeUleb128(value: Long) = apply {
        var v = value
        while (true) {
            val byte = (v and 0x7f).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(byte)
                break
            }
            out.write(byte or 0x80)
        }
    }

    fun writeU64(value: Long) = apply {
        for (shift in 0 until 64 step 8) out.write((value shr shift).toInt() and 0xff)
    }

    fun writeU128(value: String) = apply {
        writeU128Bytes(parseU128(value))
    }

    fun writeU128Bytes(bytes: ByteArray) = apply {
        // bytes: 16 bytes little-endian
        require(bytes.size == 16) { "u128 must be 16 bytes" }
        out.write(bytes)
    }

    fun writeString(text: String) = apply {
        val data = text.toByteArray(Charsets.UTF_8)
        writeUleb128(data.size.toLong())
        out.write(data)
    }

    companion object {
        /** Parses a decimal u128 into 16 little-endian bytes. */
        fun parseU128(decimal: String): ByteArray {
            require(decimal.all { it.isDigit() } && decimal.isNotEmpty()) { "Invalid u128 decimal '$decimal'" }
            val bytes = ByteArray(16)
            var value = decimal
            // Repeated long division by 256, least-significant byte first.
            val result = ArrayList<Byte>(16)
            var v = java.math.BigInteger(decimal)
            val base = java.math.BigInteger.valueOf(256)
            while (v.signum() > 0) {
                val rem = v.mod(base).toInt()
                result.add(rem.toByte())
                v = v.divide(base)
            }
            require(result.size <= 16) { "u128 overflow" }
            for (i in result.indices) bytes[i] = result[i]
            return bytes
        }
    }
}

/**
 * Minimal BCS reader for parsing dApp-provided payloads (Sui txBytes etc.).
 * Everything is bounds-checked; a malformed payload throws
 * [IllegalArgumentException] rather than reading out of bounds.
 */
class BcsReader(private val data: ByteArray, private var offset: Int = 0) {

    fun remaining(): Int = data.size - offset

    fun readByte(): Byte {
        check(offset < data.size) { "BCS underflow" }
        return data[offset++]
    }

    fun readUleb128(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = readByte().toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            check(shift < 64) { "ULEB128 too long" }
        }
    }

    fun readBytes(): ByteArray {
        val len = readUleb128().toInt()
        check(len >= 0 && offset + len <= data.size) { "BCS bytes out of bounds" }
        val out = data.copyOfRange(offset, offset + len)
        offset += len
        return out
    }

    fun readFixed(n: Int): ByteArray {
        check(offset + n <= data.size) { "BCS fixed read out of bounds" }
        val out = data.copyOfRange(offset, offset + n)
        offset += n
        return out
    }

    fun readU64(): Long {
        val b = readFixed(8)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xff)
        return v
    }

    fun readU8(): Int = readByte().toInt() and 0xff

    fun readBool(): Boolean = readU8() != 0

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    fun readAddress(): ByteArray = readFixed(32)

    fun skip(n: Int) {
        check(offset + n <= data.size) { "BCS skip out of bounds" }
        offset += n
    }
}
