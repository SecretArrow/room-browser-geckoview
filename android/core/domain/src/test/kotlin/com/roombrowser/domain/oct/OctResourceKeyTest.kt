package com.roombrowser.domain.oct

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The resource key, against vectors produced by running the reference's own framing in
 * Node. A test that recomputed the expected value with this implementation would prove
 * only that it agrees with itself.
 */
class OctResourceKeyTest {

    private val circle = "oct" + "D".repeat(44)

    @Test
    fun `the key of a path is the reference's`() {
        assertThat(OctResourceKey.of(circle, "/index.html"))
            .isEqualTo("d373335ac825c804cba329b3818d884f926be1cfe744d7b89fe4f5448d51b179")
        assertThat(OctResourceKey.of(circle, "/"))
            .isEqualTo("e6e9d7b22686df0bb2582fac16c3ec04d4b8ec03f3184236258c84bca2b52218")
        assertThat(OctResourceKey.of(circle, "/a/b"))
            .isEqualTo("b426b6cc468ba2acd363388a74abe980c45def1ff2db2a118e5c91e05ce4c60d")
    }

    @Test
    fun `the length prefix is what keeps two pairs apart`() {
        // Both frame to the same concatenation, circle + "ab/c", and only one of them is
        // the key of ("ab", "/c") — without the length prefix they would collide.
        val twoParts = OctSealed.hex(OctResourceKey.derive("octra:circle_resource_key:v1", circle, "ab", "/c"))
        val other = OctSealed.hex(OctResourceKey.derive("octra:circle_resource_key:v1", circle, "a", "b/c"))
        assertThat(twoParts).isNotEqualTo(other)
        assertThat(twoParts).isNotEqualTo(OctResourceKey.of(circle, "ab/c"))
    }

    @Test
    fun `a different tag is a different key`() {
        val slot = OctSealed.hex(OctResourceKey.derive("octra:circle_resource_key:slot:v1", circle, "/index.html"))
        assertThat(slot).isNotEqualTo(OctResourceKey.of(circle, "/index.html"))
    }
}
