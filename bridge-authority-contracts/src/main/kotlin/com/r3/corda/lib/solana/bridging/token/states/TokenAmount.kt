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

    /**
     * Converts this amount to a new fractional digit resolution while preserving the original resolution
     * with a truncated value.
     *
     * This function is useful when bridging between different token systems with different precision levels.
     * For example, when converting from Solana (8 fractional digits) to Corda (1 fractional digit), you get:
     * - The converted amount in the target resolution (with lower precision if reducing digits)
     * - The original amount in the original resolution, but with the quantity truncated/zeroed to match
     *   what was actually converted (no extra precision loss)
     *
     * "keepOriginal" means keeping the original resolution (fractional digits) with a truncated quantity,
     * not keeping the exact original quantity value. This ensures that:
     * - First value: amount in new resolution
     * - Second value: amount in original resolution representing the same VALUE as the first (just in original units)
     *
     * Examples:
     * - Input: TokenAmount(11, 2) [0.11] → convert to 1 fractional digit
     *   Returns: Pair(TokenAmount(1, 1) [0.1], TokenAmount(10, 2) [0.10])
     *   The second value is in original resolution (2 digits) with truncated quantity (10 instead of 11)
     *
     * - Input: TokenAmount(11, 2) [0.11] → convert to 3 fractional digits
     *   Returns: Pair(TokenAmount(110, 3) [0.110], TokenAmount(11, 2) [0.11])
     *   When resolution increases, the original quantity is kept as-is (no truncation needed)
     *
     * @param newFractionDigits The target number of fractional digits
     * @return A Pair containing:
     *         - First: The amount converted to the new fractional digit resolution
     *         - Second: The original amount in its original resolution with truncated quantity
     *                  (representing the same value as the converted amount, just expressed in original units)
     */
    fun convertToAndKeepOriginal(newFractionDigits: Int): Pair<TokenAmount, TokenAmount> {
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
