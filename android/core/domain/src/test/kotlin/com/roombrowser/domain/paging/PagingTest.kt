package com.roombrowser.domain.paging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PagingTest {

    @Test
    fun a_list_that_fits_is_one_page() {
        assertThat(Paging.pageCount(0)).isEqualTo(0)
        assertThat(Paging.pageCount(1)).isEqualTo(1)
        assertThat(Paging.pageCount(Paging.PAGE_SIZE)).isEqualTo(1)
        assertThat(Paging.pageCount(Paging.PAGE_SIZE + 1)).isEqualTo(2)
        assertThat(Paging.pageCount(Paging.PAGE_SIZE * 24)).isEqualTo(24)
        // The remainder must be a page of its own, not dropped.
        assertThat(Paging.pageCount(Paging.PAGE_SIZE * 24 + 1)).isEqualTo(25)
    }

    @Test
    fun the_slice_is_the_window_and_the_last_page_is_short() {
        val items = (1..25).map { "m$it" }
        assertThat(Paging.slice(items, 0)).hasSize(Paging.PAGE_SIZE)
        assertThat(Paging.slice(items, 0).first()).isEqualTo("m1")
        assertThat(Paging.slice(items, 0).last()).isEqualTo("m10")
        assertThat(Paging.slice(items, 2).first()).isEqualTo("m21")
        assertThat(Paging.slice(items, 2)).hasSize(5)
    }

    @Test
    fun an_empty_list_has_no_page() {
        assertThat(Paging.slice(emptyList<String>(), 3)).isEmpty()
        assertThat(Paging.clampPage(3, 0)).isEqualTo(0)
        assertThat(Paging.windowLabel(0, 0)).isEqualTo("0 of 0")
    }

    @Test
    fun a_shrinking_list_pulls_the_page_back_instead_of_going_blank() {
        val items = (1..25).map { "m$it" }
        // Page 2 is valid for 25 items; a search that narrows it to 3 must
        // show those 3 rather than an empty page 3.
        assertThat(Paging.clampPage(2, 25)).isEqualTo(2)
        assertThat(Paging.clampPage(2, 3)).isEqualTo(0)
        assertThat(Paging.slice(items.take(3), 2)).containsExactly("m1", "m2", "m3")
        assertThat(Paging.windowLabel(9, 3)).isEqualTo("1-3 of 3")
    }

    @Test
    fun a_negative_page_never_reaches_the_list() {
        assertThat(Paging.clampPage(-4, 25)).isEqualTo(0)
        assertThat(Paging.slice((1..25).map { "m$it" }, -1).first()).isEqualTo("m1")
        assertThat(Paging.windowLabel(-1, 25)).isEqualTo("1-10 of 25")
    }

    @Test
    fun the_window_label_states_the_real_bounds() {
        assertThat(Paging.windowLabel(0, 240)).isEqualTo("1-10 of 240")
        assertThat(Paging.windowLabel(1, 240)).isEqualTo("11-20 of 240")
        assertThat(Paging.windowLabel(23, 240)).isEqualTo("231-240 of 240")
        assertThat(Paging.windowLabel(0, 1)).isEqualTo("1-1 of 1")
        assertThat(Paging.windowLabel(0, 240 + 1)).isEqualTo("1-10 of 241")
    }

    @Test
    fun the_page_size_is_the_one_the_pickers_advertise() {
        assertThat(Paging.PAGE_SIZE).isEqualTo(10)
    }
}
