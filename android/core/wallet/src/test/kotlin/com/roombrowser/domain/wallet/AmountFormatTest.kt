package com.roombrowser.domain.wallet

import com.google.common.truth.Truth.assertThat
import com.roombrowser.domain.wallet.model.AmountFormat
import java.math.BigInteger
import org.junit.Test

/**
 * The wallet's one amount formatter: at most seven digits after the decimal
 * point, truncated and never rounded, then trailing zeros dropped. The six
 * owner examples are the contract; the rest pin the edges truncation can get
 * wrong (a carry at the seventh digit, a value that truncates to zero, a
 * large integer part).
 */
class AmountFormatTest {

    @Test
    fun `owner examples`() {
        assertThat(AmountFormat.display("0.0000999987656675675")).isEqualTo("0.0000999")
        assertThat(AmountFormat.display("0.123456789")).isEqualTo("0.1234567")
        assertThat(AmountFormat.display("1.23456789")).isEqualTo("1.2345678")
        assertThat(AmountFormat.display("10.50000000")).isEqualTo("10.5")
        assertThat(AmountFormat.display("0.001000000")).isEqualTo("0.001")
        assertThat(AmountFormat.display("0")).isEqualTo("0")
    }

    @Test
    fun `truncates and never rounds at the seventh digit`() {
        // The eighth digit is 9: rounding would carry it into the seventh.
        assertThat(AmountFormat.display("0.12345679")).isEqualTo("0.1234567")
        assertThat(AmountFormat.display("1.99999999")).isEqualTo("1.9999999")
        assertThat(AmountFormat.display("0.000000199999")).isEqualTo("0.0000001")
    }

    @Test
    fun `drops decimals left unneeded after truncation`() {
        assertThat(AmountFormat.display("5.000000000")).isEqualTo("5")
        assertThat(AmountFormat.display("0.12000000")).isEqualTo("0.12")
        assertThat(AmountFormat.display("0.00000010")).isEqualTo("0.0000001")
    }

    @Test
    fun `keeps a value with exactly seven retainable decimals`() {
        assertThat(AmountFormat.display("0.1234567")).isEqualTo("0.1234567")
        assertThat(AmountFormat.display("123.0000001")).isEqualTo("123.0000001")
    }

    @Test
    fun `an all zero fraction collapses to the integer`() {
        assertThat(AmountFormat.display("42.000000000000000000")).isEqualTo("42")
        assertThat(AmountFormat.display("0.000000000000000000")).isEqualTo("0")
    }

    @Test
    fun `zero and negative-looking values never render negative zero`() {
        assertThat(AmountFormat.display("0")).isEqualTo("0")
        assertThat(AmountFormat.display("0.000000")).isEqualTo("0")
        assertThat(AmountFormat.display("-0.000000004")).isEqualTo("0")
        assertThat(AmountFormat.display("-1.23456789")).isEqualTo("-1.2345678")
    }

    @Test
    fun `a large integer part stays plain and whole`() {
        assertThat(AmountFormat.display("123456789012345678901234567890.999999999"))
            .isEqualTo("123456789012345678901234567890.9999999")
        assertThat(AmountFormat.display("1000000000000000000000"))
            .isEqualTo("1000000000000000000000")
    }

    @Test
    fun `more than eighteen decimals are truncated`() {
        assertThat(AmountFormat.display("0.123456789012345678901234567890"))
            .isEqualTo("0.1234567")
    }

    @Test
    fun `text that is not a number is returned unchanged`() {
        assertThat(AmountFormat.display("message")).isEqualTo("message")
        assertThat(AmountFormat.display("  typed data  ")).isEqualTo("typed data")
    }

    @Test
    fun `base units are scaled then truncated`() {
        // 10^18 wei is one ETH.
        assertThat(AmountFormat.fromBaseUnits(BigInteger.TEN.pow(18), 18)).isEqualTo("1")
        // 0.000099998765667567 ETH, in wei (18 decimals).
        assertThat(AmountFormat.fromBaseUnits(BigInteger("99998765667567"), 18))
            .isEqualTo("0.0000999")
        assertThat(AmountFormat.fromBaseUnits(BigInteger.ZERO, 18)).isEqualTo("0")
    }
}
