package com.roombrowser.domain

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.localai.Gguf
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JVM tests for the GGUF header parser. Fixtures are crafted byte-by-byte
 * (LITTLE-ENDIAN, matching the wire format) so every test pins the exact
 * layout the vendored gguf.cpp reads — not an incidental artifact of some
 * other GGUF writer.
 */
class GgufTest {

    // ------------------------------------------------------------- fixture kit

    /** Little-endian GGUF byte builder (values go through [ByteBuffer]). */
    private class Builder {
        private val out = java.io.ByteArrayOutputStream()
        private val scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        fun raw(bytes: ByteArray): Builder = apply { out.write(bytes) }

        fun u32(v: Long): Builder = apply {
            scratch.clear(); scratch.putInt(v.toInt())
            out.write(scratch.array(), 0, 4)
        }

        fun u64(v: Long): Builder = apply {
            scratch.clear(); scratch.putLong(v)
            out.write(scratch.array(), 0, 8)
        }

        fun str(s: String): Builder = apply {
            val bytes = s.toByteArray(Charsets.UTF_8)
            u64(bytes.size.toLong())
            out.write(bytes)
        }

        fun kvString(key: String, value: String): Builder = apply {
            str(key); u32(8L); str(value)
        }

        fun kvU32(key: String, value: Long): Builder = apply {
            str(key); u32(4L); u32(value)
        }

        fun kvArrayU32(key: String, values: LongArray): Builder = apply {
            str(key); u32(9L); u32(4L); u64(values.size.toLong())
            values.forEach { u32(it) }
        }

        fun kvArrayString(key: String, values: List<String>): Builder = apply {
            str(key); u32(9L); u32(8L); u64(values.size.toLong())
            values.forEach { str(it) }
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun magic(): ByteArray = "GGUF".toByteArray(Charsets.US_ASCII)

    /** The 5-KV minimal v3 header from the task brief. */
    private fun minimalV3(): ByteArray = Builder()
        .raw(magic())
        .u32(3) // version
        .u64(0) // tensor_count
        .u64(5) // kv_count
        .kvString("general.architecture", "llama")
        .kvString("general.name", "test-model")
        .kvU32("general.file_type", 15) // Q4_K_M
        .kvU32("llama.context_length", 2048)
        .kvString("general.size_label", "1.5B")
        .bytes()

    // ------------------------------------------------------------------ tests

    @Test
    fun `parse minimal v3 header`() {
        val fixture = minimalV3()
        // Sentinel tail proves the parser consumed EXACTLY the header: the
        // caller may keep streaming tensor data afterwards.
        val tail = "TENSOR-DATA".toByteArray()
        val stream = ByteArrayInputStream(fixture.plus(tail))

        val meta = Gguf.parse(stream)

        assertThat(meta).isNotNull()
        assertThat(meta!!.version).isEqualTo(3)
        assertThat(meta.name).isEqualTo("test-model")
        assertThat(meta.architecture).isEqualTo("llama")
        assertThat(meta.quantization).isEqualTo("Q4_K_M")
        assertThat(meta.contextLength).isEqualTo(2048L)
        assertThat(meta.tensorCount).isEqualTo(0L)
        assertThat(meta.parameterCountApprox).isEqualTo(1_500_000_000L)
        assertThat(stream.available()).isEqualTo(tail.size)
    }

    @Test
    fun `rejects legacy v1 header`() {
        // The vendored gguf.cpp REFUSES v1 ("GGUFv1 is no longer supported") —
        // and so does the embedded engine. The parser mirrors that rejection:
        // a v1 file must not be presented as a loadable model. (The legacy v1
        // alignment rules the task brief speculated about were removed from
        // gguf.cpp before it was vendored here; the C++ is the ground truth.)
        val v1 = Builder()
            .raw(magic())
            .u32(1)
            .u64(0)
            .u64(0)
            .bytes()
        assertThat(Gguf.parse(ByteArrayInputStream(v1))).isNull()
    }

    @Test
    fun `rejects bad magic and suspicious versions`() {
        val notGguf = Builder().raw("JUNK".toByteArray()).u32(3).u64(0).u64(0).bytes()
        assertThat(Gguf.parse(ByteArrayInputStream(notGguf))).isNull()

        val version4 = Builder().raw(magic()).u32(4).u64(0).u64(0).bytes()
        assertThat(Gguf.parse(ByteArrayInputStream(version4))).isNull()

        val version0 = Builder().raw(magic()).u32(0).u64(0).u64(0).bytes()
        assertThat(Gguf.parse(ByteArrayInputStream(version0))).isNull()

        // A big-endian-written version 3 reads as 0x03000000 little-endian;
        // gguf.cpp detects exactly this via the "low half is zero" check.
        val byteSwapped = Builder().raw(magic()).u32(0x03000000L).u64(0).u64(0).bytes()
        assertThat(Gguf.parse(ByteArrayInputStream(byteSwapped))).isNull()

        assertThat(Gguf.parse(ByteArrayInputStream(ByteArray(0)))).isNull()
    }

    @Test
    fun `parse array-typed kvs and keep reading following kvs`() {
        val fixture = Builder()
            .raw(magic())
            .u32(3)
            .u64(48)
            .u64(4)
            .kvString("general.architecture", "llama")
            .kvArrayString("tokenizer.ggml.tokens", listOf("<s>", "▁the"))
            .kvArrayU32("llama.attention.head_count", longArrayOf(8, 16))
            .kvString("general.name", "array-model")
            .bytes()

        val meta = Gguf.parse(ByteArrayInputStream(fixture))

        assertThat(meta).isNotNull()
        assertThat(meta!!.name).isEqualTo("array-model")
        assertThat(meta.architecture).isEqualTo("llama")
        assertThat(meta.tensorCount).isEqualTo(48L)
        // No file_type / context_length KVs present → null, not garbage.
        assertThat(meta.quantization).isNull()
        assertThat(meta.contextLength).isNull()
        assertThat(meta.parameterCountApprox).isNull()
    }

    @Test
    fun `truncated header returns null`() {
        val fixture = minimalV3()
        assertThat(Gguf.parse(ByteArrayInputStream(fixture.copyOf(7)))).isNull()
        assertThat(Gguf.parse(ByteArrayInputStream(fixture.copyOf(fixture.size / 2)))).isNull()
        assertThat(Gguf.parse(ByteArrayInputStream(fixture.copyOf(fixture.size - 1)))).isNull()
    }

    @Test
    fun `absurd string length returns null instead of allocating`() {
        val hostile = Builder()
            .raw(magic())
            .u32(3)
            .u64(0)
            .u64(1)
            .str("general.name")
            .u32(8)
            .u64(128L * 1024 * 1024) // 128 MiB declared, cap is 64 MiB
            .bytes()
        assertThat(Gguf.parse(ByteArrayInputStream(hostile))).isNull()
    }

    @Test
    fun `unknown file type renders FT n`() {
        val fixture = Builder()
            .raw(magic())
            .u32(2) // v2 is accepted too (only v1 and >3 are refused)
            .u64(0)
            .u64(1)
            .kvU32("general.file_type", 99)
            .bytes()
        val meta = Gguf.parse(ByteArrayInputStream(fixture))
        assertThat(meta).isNotNull()
        assertThat(meta!!.quantization).isEqualTo("FT 99")
    }

    @Test
    fun `size label heuristics`() {
        assertThat(Gguf.parseSizeLabel("1.5B")).isEqualTo(1_500_000_000L)
        assertThat(Gguf.parseSizeLabel("350M")).isEqualTo(350_000_000L)
        assertThat(Gguf.parseSizeLabel("2.06B")).isEqualTo(2_060_000_000L)
        assertThat(Gguf.parseSizeLabel("1,5B")).isEqualTo(1_500_000_000L)
        assertThat(Gguf.parseSizeLabel("7B")).isEqualTo(7_000_000_000L)
        assertThat(Gguf.parseSizeLabel("Q4_0")).isNull()
        assertThat(Gguf.parseSizeLabel("")).isNull()
    }
}
