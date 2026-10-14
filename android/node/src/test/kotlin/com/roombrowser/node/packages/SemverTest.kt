package com.roombrowser.node.packages

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SemverTest {

    @Test
    fun a_version_parses_with_or_without_a_leading_v_or_equals() {
        assertThat(Semver.parse("v1.2.3")).isEqualTo(Semver.Version(1, 2, 3))
        assertThat(Semver.parse("=1.2.3")).isEqualTo(Semver.Version(1, 2, 3))
        assertThat(Semver.parse("1.2.3")).isEqualTo(Semver.Version(1, 2, 3))
    }

    @Test
    fun build_metadata_is_ignored_and_a_prerelease_is_kept() {
        assertThat(Semver.parse("1.2.3+build.5")).isEqualTo(Semver.Version(1, 2, 3))
        assertThat(Semver.parse("1.2.3-beta.1+xyz"))
            .isEqualTo(Semver.Version(1, 2, 3, listOf("beta", "1")))
        assertThat(Semver.parse("1.2.3-beta.1")!!.isPrerelease).isTrue()
        assertThat(Semver.parse("1.2.3")!!.isPrerelease).isFalse()
    }

    @Test
    fun a_version_that_makes_no_sense_is_refused() {
        assertThat(Semver.parse("nonsense")).isNull()
        assertThat(Semver.parse("1.2.x")).isNull()
        assertThat(Semver.parse("01.2.3")).isNull()
        assertThat(Semver.parse("")).isNull()
    }

    @Test
    fun versions_compare_by_core_and_then_by_prerelease() {
        assertThat(Semver.parse("1.2.3")!! < Semver.parse("1.2.4")!!).isTrue()
        assertThat(Semver.parse("1.10.0")!! > Semver.parse("1.9.9")!!).isTrue()
        assertThat(Semver.parse("1.0.0")!! > Semver.parse("1.0.0-rc.1")!!).isTrue()
        assertThat(Semver.parse("1.0.0-alpha")!! < Semver.parse("1.0.0-alpha.1")!!).isTrue()
        assertThat(Semver.parse("1.0.0-alpha.2")!! < Semver.parse("1.0.0-alpha.10")!!).isTrue()
        assertThat(Semver.parse("1.0.0-1")!! < Semver.parse("1.0.0-alpha")!!).isTrue()
    }

    @Test
    fun an_empty_or_wildcard_spec_allows_every_release() {
        for (spec in listOf("", "*", "x", "latest")) {
            val range = rangeOf(spec)
            assertThat(range.allows(Semver.Version(0, 0, 0))).isTrue()
            assertThat(range.allows(Semver.Version(9, 9, 9))).isTrue()
        }
    }

    @Test
    fun a_wildcard_does_not_reach_a_prerelease() {
        assertThat(rangeOf("*").allows(Semver.Version(1, 0, 0, listOf("rc", "1")))).isFalse()
    }

    @Test
    fun an_exact_and_a_partial_version_both_select_their_own_range() {
        val exact = rangeOf("1.2.3")
        assertThat(exact.allows(Semver.Version(1, 2, 3))).isTrue()
        assertThat(exact.allows(Semver.Version(1, 2, 4))).isFalse()

        val minor = rangeOf("1.2")
        assertThat(minor.allows(Semver.Version(1, 2, 0))).isTrue()
        assertThat(minor.allows(Semver.Version(1, 2, 9))).isTrue()
        assertThat(minor.allows(Semver.Version(1, 3, 0))).isFalse()

        val major = rangeOf("1")
        assertThat(major.allows(Semver.Version(1, 9, 9))).isTrue()
        assertThat(major.allows(Semver.Version(2, 0, 0))).isFalse()

        val wildcard = rangeOf("1.x")
        assertThat(wildcard.allows(Semver.Version(1, 4, 0))).isTrue()
        assertThat(wildcard.allows(Semver.Version(2, 0, 0))).isFalse()
    }

    @Test
    fun caret_stops_before_the_next_major() {
        val range = rangeOf("^1.2.3")
        assertThat(range.allows(Semver.Version(1, 2, 3))).isTrue()
        assertThat(range.allows(Semver.Version(1, 9, 0))).isTrue()
        assertThat(range.allows(Semver.Version(2, 0, 0))).isFalse()
    }

    @Test
    fun caret_on_a_zero_major_stops_at_the_next_minor() {
        val range = rangeOf("^0.2.3")
        assertThat(range.allows(Semver.Version(0, 2, 3))).isTrue()
        assertThat(range.allows(Semver.Version(0, 2, 9))).isTrue()
        assertThat(range.allows(Semver.Version(0, 3, 0))).isFalse()
    }

    @Test
    fun caret_on_a_zero_minor_stops_at_the_next_patch() {
        val range = rangeOf("^0.0.3")
        assertThat(range.allows(Semver.Version(0, 0, 3))).isTrue()
        assertThat(range.allows(Semver.Version(0, 0, 4))).isFalse()
    }

    @Test
    fun caret_on_a_partial_zero_version_stops_at_the_next_minor() {
        val range = rangeOf("^0.0")
        assertThat(range.allows(Semver.Version(0, 0, 9))).isTrue()
        assertThat(range.allows(Semver.Version(0, 1, 0))).isFalse()
    }

    @Test
    fun tilde_stays_inside_the_minor_it_names() {
        val range = rangeOf("~1.2.3")
        assertThat(range.allows(Semver.Version(1, 2, 9))).isTrue()
        assertThat(range.allows(Semver.Version(1, 3, 0))).isFalse()

        val majorOnly = rangeOf("~1")
        assertThat(majorOnly.allows(Semver.Version(1, 9, 0))).isTrue()
        assertThat(majorOnly.allows(Semver.Version(2, 0, 0))).isFalse()
    }

    @Test
    fun a_partial_comparator_is_widened_the_way_npm_widens_it() {
        val atLeast = rangeOf(">=1.2")
        assertThat(atLeast.allows(Semver.Version(1, 2, 0))).isTrue()
        assertThat(atLeast.allows(Semver.Version(1, 1, 9))).isFalse()

        val above = rangeOf(">1.2")
        assertThat(above.allows(Semver.Version(1, 2, 9))).isFalse()
        assertThat(above.allows(Semver.Version(1, 3, 0))).isTrue()

        val below = rangeOf("<1.2")
        assertThat(below.allows(Semver.Version(1, 1, 9))).isTrue()
        assertThat(below.allows(Semver.Version(1, 2, 0))).isFalse()

        val atMost = rangeOf("<=1.2")
        assertThat(atMost.allows(Semver.Version(1, 2, 9))).isTrue()
        assertThat(atMost.allows(Semver.Version(1, 3, 0))).isFalse()
    }

    @Test
    fun a_space_separated_pair_is_a_conjunction() {
        val range = rangeOf(">=1.0.0 <2.0.0")
        assertThat(range.allows(Semver.Version(1, 5, 0))).isTrue()
        assertThat(range.allows(Semver.Version(0, 9, 9))).isFalse()
        assertThat(range.allows(Semver.Version(2, 0, 0))).isFalse()
    }

    @Test
    fun a_hyphen_range_includes_both_ends() {
        val range = rangeOf("1.2.3 - 2.3.4")
        assertThat(range.allows(Semver.Version(1, 2, 3))).isTrue()
        assertThat(range.allows(Semver.Version(2, 3, 4))).isTrue()
        assertThat(range.allows(Semver.Version(2, 3, 5))).isFalse()
        assertThat(range.allows(Semver.Version(1, 2, 2))).isFalse()
    }

    @Test
    fun a_hyphen_range_with_a_partial_upper_end_stops_before_it() {
        val range = rangeOf("1.2.3 - 2.3")
        assertThat(range.allows(Semver.Version(2, 3, 9))).isTrue()
        assertThat(range.allows(Semver.Version(2, 4, 0))).isFalse()
    }

    @Test
    fun alternatives_are_a_disjunction() {
        val range = rangeOf("1.0.0 || 2.0.0")
        assertThat(range.allows(Semver.Version(1, 0, 0))).isTrue()
        assertThat(range.allows(Semver.Version(2, 0, 0))).isTrue()
        assertThat(range.allows(Semver.Version(3, 0, 0))).isFalse()
    }

    @Test
    fun a_prerelease_is_allowed_only_where_the_range_names_one() {
        val stable = rangeOf(">=1.0.0")
        assertThat(stable.allows(Semver.Version(1, 0, 0, listOf("beta")))).isFalse()

        val named = rangeOf(">=1.0.0-beta")
        assertThat(named.allows(Semver.Version(1, 0, 0, listOf("beta", "2")))).isTrue()
        assertThat(named.allows(Semver.Version(1, 1, 0, listOf("beta")))).isFalse()
    }

    @Test
    fun a_range_that_makes_no_sense_is_refused() {
        assertThat(Semver.parseRange("nonsense")).isNull()
        assertThat(Semver.parseRange(">=")).isNull()
        assertThat(Semver.parseRange("1.2.3.4")).isNull()
        assertThat(Semver.parseRange(">=1.0.0 nonsense")).isNull()
    }

    @Test
    fun the_highest_match_is_chosen_and_given_back_as_published() {
        val published = listOf("1.0.0", "1.2.0", "1.2.3", "2.0.0-rc.1", "2.0.0")
        assertThat(Semver.highest(published, "^1.2.0")).isEqualTo("1.2.3")
        assertThat(Semver.highest(published, "1.2")).isEqualTo("1.2.3")
        assertThat(Semver.highest(published, "*")).isEqualTo("2.0.0")
        assertThat(Semver.highest(published, ">=2.0.0-rc.1 <2.0.0")).isEqualTo("2.0.0-rc.1")
        assertThat(Semver.highest(published, "^3.0.0")).isNull()
        assertThat(Semver.highest(published, "nonsense")).isNull()
    }

    @Test
    fun the_highest_allowed_version_is_answered_for_a_parsed_range() {
        val range = rangeOf("~1.2.0")
        val versions = listOf(
            Semver.Version(1, 2, 0),
            Semver.Version(1, 2, 5),
            Semver.Version(1, 3, 0)
        )
        assertThat(Semver.maxSatisfying(versions, range)).isEqualTo(Semver.Version(1, 2, 5))
        assertThat(Semver.maxSatisfying(emptyList(), range)).isNull()
    }
}

private fun rangeOf(spec: String): Semver.Range =
    Semver.parseRange(spec) ?: throw AssertionError("\"$spec\" was refused")
