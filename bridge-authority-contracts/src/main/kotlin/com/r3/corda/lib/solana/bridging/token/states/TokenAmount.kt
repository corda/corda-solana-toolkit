package com.r3.corda.lib.solana.bridging.token.states

import net.corda.core.serialization.CordaSerializable
import kotlin.math.pow

@CordaSerializable
data class TokenAmount(val quantity: Long, val fractionDigits: Int) {
    init {
        require(quantity >= 0) { "Quantity must be 0 or positive" }
        require(fractionDigits >= 0) { "Fraction digits must be 0 or positive" }
    }

    fun convertTo(fractionalDigits: Int): TokenAmount {
        val multiplier = getConversionMultiplier(fractionalDigits)
        return if (this.fractionDigits < fractionalDigits) {
            TokenAmount(this.quantity * multiplier, fractionalDigits)
        } else {
            TokenAmount(this.quantity / multiplier, fractionalDigits)
        }
    }

    fun getConversionMultiplier(fractionalDigits: Int): Long {
        if (this.fractionDigits == fractionalDigits) return 1L
        return if (this.fractionDigits > fractionalDigits) {
            (this.fractionDigits - fractionalDigits).let { decimalsDifference ->
                10.0.pow(decimalsDifference).toLong()
            }
        } else {
            (fractionalDigits - this.fractionDigits).let { decimalsDifference ->
                10.0.pow(decimalsDifference).toLong()
            }
        }
    }

    /**
     * Semantic equivalence: checks if two amounts represent the same value
     * even if they have different representations.
     *
     * Example: TokenAmount(100, 0) and TokenAmount(1000, 1) both represent the same value
     * and will return true, even though they have different fields.
     *
     * Use this when you need to compare the actual monetary value.
     * Use equals() when you need to compare the exact representation.
     */
    fun hasSameValueAs(other: TokenAmount): Boolean {
        val maxFractionalDigits = maxOf(this.fractionDigits, other.fractionDigits)
        val thisConverted = this.convertTo(maxFractionalDigits)
        val otherConverted = other.convertTo(maxFractionalDigits)
        return thisConverted.quantity == otherConverted.quantity
    }

    fun truncateQuantityByFactor(factor: Long): Long {
        validateFactor(factor)
        val newValue = quantity / factor
        return newValue
    }

    fun zeroOutFractionDigits(factor: Long): Long {
        validateFactor(factor)
        val newValue = (quantity / factor) * factor
        return newValue
    }

    private fun validateFactor(factor: Long) {
        require(factor > 0) { "factor must be > 0" }
        var f = factor
        while (f > 1 && f % 10L == 0L) {
            f /= 10L
        }
        require(f == 1L) { "factor must be a power of 10. Got: $factor" }
    }

    fun newAmountAndRemainder(newFractionDigits: Int): Pair<TokenAmount, TokenAmount> {
        val conversionMultiplier = getConversionMultiplier(newFractionDigits)
        return if (fractionDigits > newFractionDigits) {
            Pair(
                TokenAmount(truncateQuantityByFactor(conversionMultiplier), newFractionDigits),
                copy(quantity = zeroOutFractionDigits(conversionMultiplier))
            )
        } else {
            Pair(
                TokenAmount(quantity * conversionMultiplier, newFractionDigits),
                this
            )
        }
    }
}
