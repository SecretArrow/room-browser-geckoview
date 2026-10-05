package com.roombrowser.domain.wallet.model

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * The one display formatter for token amounts anywhere in the wallet UI.
 *
 * Rule, derived from the owner's examples: keep at most [MAX_DECIMALS] digits
 * after the decimal point, TRUNCATE anything past that (never round — a shown
 * balance must never grow on screen), then drop the trailing zeros the
 * truncation left unneeded. The budget is counted from the decimal point, so a
 * value below 1 spends it on its leading zeros and shows only the leading
 * significant digits:
 *
 *   "0.0000999987656675675" -> "0.0000999"
 *   "0.123456789"           -> "0.1234567"
 *   "1.23456789"            -> "1.2345678"
 *   "10.50000000"           -> "10.5"
 *   "0.001000000"           -> "0.001"
 *   "0"                     -> "0"
 *
 * DISPLAY ONLY. It takes an already-scaled human-units decimal and returns a
 * string; it must never feed a stored value, a fee estimate, a validation, a
 * signature input or an RPC call. Plain JVM and locale-independent (no
 * `String.format`, no default locale), so both editions share it verbatim.
 */
object AmountFormat {
    /** Digits kept after the decimal point before trailing zeros are dropped. */
    const val MAX_DECIMALS: Int = 7

    private const val MAX_BASE_UNIT_DECIMALS = 36

    /**
     * A human-units decimal string -> display string. Anything that is not a
     * plain number is returned trimmed and unchanged, so callers can pass a
     * value that is already a label without having to pre-check it.
     */
    fun display(amount: String): String {
        val trimmed = amount.trim()
        val parsed = trimmed.toBigDecimalOrNull() ?: return trimmed
        return display(parsed)
    }

    fun display(amount: BigDecimal): String {
        val truncated = amount.setScale(MAX_DECIMALS, RoundingMode.DOWN)
        // Zero is re-checked after truncation: a tiny non-zero input can round
        // down to zero here, and stripTrailingZeros would leave "0.0000000".
        if (truncated.signum() == 0) return "0"
        return truncated.stripTrailingZeros().toPlainString()
    }

    /** Base units (wei, sats, lamports, uatom, ...) -> display string. */
    fun fromBaseUnits(baseUnits: BigInteger, decimals: Int): String {
        val safeDecimals = decimals.coerceIn(0, MAX_BASE_UNIT_DECIMALS)
        return display(BigDecimal(baseUnits).movePointLeft(safeDecimals))
    }
}
