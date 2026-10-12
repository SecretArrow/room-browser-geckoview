package com.roombrowser.domain.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadSubfolderTest {

    private val emptyish = listOf("", "   ", ".", "..")

    private val table = listOf(
        "Holiday",
        "Room Browser",
        "../../etc",
        "a/b",
        "a\\b",
        "/abs",
        ".hidden",
        "x".repeat(200)
    ) + emptyish

    @Test
    fun `a plain name passes through unchanged`() {
        assertThat(sanitiseDownloadSubfolder("Holiday")).isEqualTo("Holiday")
        assertThat(sanitiseDownloadSubfolder("Room Browser")).isEqualTo("Room Browser")
    }

    @Test
    fun `a parent reference does not survive`() {
        val result = sanitiseDownloadSubfolder("../../etc")
        assertThat(result).doesNotContain("/")
        assertThat(result).doesNotContain("..")
    }

    @Test
    fun `a separator becomes a space rather than an escape`() {
        assertThat(sanitiseDownloadSubfolder("a/b")).isEqualTo("a b")
        assertThat(sanitiseDownloadSubfolder("a\\b")).isEqualTo("a b")
    }

    @Test
    fun `a leading separator is dropped`() {
        val result = sanitiseDownloadSubfolder("/abs")
        assertThat(result).isEqualTo("abs")
        assertThat(result).doesNotContain("/")
    }

    @Test
    fun `a leading dot is stripped so the folder is not hidden`() {
        assertThat(sanitiseDownloadSubfolder(".hidden")).isEqualTo("hidden")
    }

    @Test
    fun `every empty-ish input falls back to the default`() {
        for (raw in emptyish) {
            assertThat(sanitiseDownloadSubfolder(raw)).isEqualTo(DEFAULT_DOWNLOAD_SUBFOLDER)
        }
    }

    @Test
    fun `a very long name is bounded`() {
        val result = sanitiseDownloadSubfolder("x".repeat(200))
        // The cap is private; the bound, not the constant, is the contract.
        assertThat(result.length).isAtMost(64)
        assertThat(result.length).isLessThan(200)
    }

    @Test
    fun `no result ever carries a separator or a parent reference`() {
        for (raw in table) {
            val result = sanitiseDownloadSubfolder(raw)
            assertThat(result).doesNotContain("/")
            assertThat(result).doesNotContain("\\")
            assertThat(result).doesNotContain("..")
        }
    }
}
