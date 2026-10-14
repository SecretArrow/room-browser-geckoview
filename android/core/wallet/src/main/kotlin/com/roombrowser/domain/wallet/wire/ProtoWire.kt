package com.roombrowser.domain.wallet.wire

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire-format writer/reader (proto3), sufficient for:
 *  - Cosmos: SignDoc hashing, TxRaw building, Any packing, TxBody parsing
 *  - TRON: raw_data hashing when raw_data_hex is missing (hand-serialized
 *    common contracts) and raw tx parsing for the confirmation screen
 *
 * This is NOT a general protobuf runtime; it only implements the wire
 * primitives (varint, length-delimited, 32/64-bit) with bounds checking.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    private fun varint(value: Long) {
        var v = value
        while (true) {
            val byte = (v and 0x7f).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(byte)
                return
            }
            out.write(byte or 0x80)
        }
    }

    private fun tag(fieldNumber: Int, wireType: Int) {
        varint(((fieldNumber shl 3) or wireType).toLong())
    }

    fun writeVarint(fieldNumber: Int, value: Long) = apply {
        tag(fieldNumber, 0)
        varint(value)
    }

    fun writeBytes(fieldNumber: Int, value: ByteArray) = apply {
        tag(fieldNumber, 2)
        varint(value.size.toLong())
        out.write(value)
    }

    fun writeString(fieldNumber: Int, value: String) = writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))

    fun writeBool(fieldNumber: Int, value: Boolean) = writeVarint(fieldNumber, if (value) 1 else 0)

    companion object {
        /** google.protobuf.Any: type_url = 1, value = 2. */
        fun any(typeUrl: String, value: ByteArray): ByteArray =
            ProtoWriter().writeString(1, typeUrl).writeBytes(2, value).bytes()
    }
}

/**
 * Generic protobuf wire reader. Yields (fieldNumber, value) pairs in stream
 * order; nested messages can be re-parsed recursively. Unknown fields are
 * skipped per protobuf rules.
 */
class ProtoReader(private val data: ByteArray, private var offset: Int = 0) {

    data class Field(val number: Int, val wireType: Int, val value: FieldValue) {
        val varint: Long get() = (value as FieldValue.Varint).v
        val bytes: ByteArray get() = (value as FieldValue.Bytes).b
        fun asReader() = ProtoReader(bytes)
    }

    sealed interface FieldValue {
        data class Varint(val v: Long) : FieldValue
        data class Bytes(val b: ByteArray) : FieldValue
        data class Fixed32(val v: Int) : FieldValue
        data class Fixed64(val v: Long) : FieldValue
    }

    fun atEnd(): Boolean = offset >= data.size

    fun next(): Field? {
        if (atEnd()) return null
        val key = readVarint()
        val fieldNumber = (key ushr 3).toInt()
        if (fieldNumber <= 0) throw IllegalArgumentException("Invalid protobuf field number")
        return when (val wireType = (key and 0x7).toInt()) {
            0 -> Field(fieldNumber, 0, FieldValue.Varint(readVarint()))
            1 -> Field(fieldNumber, 1, FieldValue.Fixed64(readFixed64()))
            2 -> {
                val len = readVarint().toInt()
                if (len < 0 || offset + len > data.size) throw IllegalArgumentException("protobuf bytes out of bounds")
                val b = data.copyOfRange(offset, offset + len)
                offset += len
                Field(fieldNumber, 2, FieldValue.Bytes(b))
            }
            5 -> Field(fieldNumber, 5, FieldValue.Fixed32(readFixed32()))
            else -> throw IllegalArgumentException("Unsupported wire type $wireType")
        }
    }

    /** Reads all remaining fields; throws on malformed input. */
    fun all(): List<Field> {
        val fields = mutableListOf<Field>()
        while (true) {
            val f = next() ?: break
            fields += f
        }
        return fields
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            check(offset < data.size) { "protobuf varint underflow" }
            val byte = data[offset++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            check(shift < 70) { "protobuf varint too long" }
        }
    }

    private fun readFixed32(): Int = checkAndAdvance(4) { o ->
        (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or
            ((data[o + 2].toInt() and 0xff) shl 16) or ((data[o + 3].toInt() and 0xff) shl 24)
    }

    private fun readFixed64(): Long = checkAndAdvance(8) { o ->
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (data[o + i].toLong() and 0xff)
        v
    }

    private inline fun <T> checkAndAdvance(n: Int, read: (Int) -> T): T {
        check(offset + n <= data.size) { "protobuf fixed read out of bounds" }
        val v = read(offset)
        offset += n
        return v
    }
}
