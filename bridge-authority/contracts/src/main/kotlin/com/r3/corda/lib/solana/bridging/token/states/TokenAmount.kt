package com.r3.corda.lib.solana.bridging.token.states

import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import net.corda.core.contracts.Amount
import net.corda.core.serialization.CordaSerializable
import java.math.BigDecimal
import kotlin.math.absoluteValue

@CordaSerializable
data class TokenAmount(val quantity: Long, val fractionDigits: Int) {
    init {
        require(quantity >= 0) { "Quantity must be 0 or positive" }
        require(fractionDigits >= 0) { "Fraction digits must be 0 or positive" }
    }

    companion object {
        fun fromAmount(amount: Amount<IssuedTokenType>) = TokenAmount(amount.quantity, amount.token.fractionDigits)
    }

    /**
     * Checks if two amounts represent the same value even if they have different representations.
     *
     * Example: TokenAmount(100, 0) and TokenAmount(1000, 1) both represent the same value
     * and will return true, even though they have different fields.
     */
    fun isNumericallyEqual(other: TokenAmount): Boolean {
        val maxFractionalDigits = maxOf(this.fractionDigits, other.fractionDigits)
        val thisConverted = this.rescale(maxFractionalDigits)
        val otherConverted = other.rescale(maxFractionalDigits)
        return thisConverted.quantity == otherConverted.quantity
    }

    /**
     * Rescales this token amount to a different fractional digit resolution.
     * This function converts the amount to a new precision level by adjusting both the quantity
     * and the fractional digits to maintain the same numerical value.
     *
     * Example: TokenAmount(100, 0) rescaled to 1 fractional digit gives TokenAmount(1000, 1).
     *
     * @param newFractionDigits The target number of fractional digits
     * @return A new TokenAmount with the specified fractional digits and adjusted quantity
     * @throws IllegalArgumentException if downscaling would result in precision loss (non-zero remainder)
     */
    fun rescale(newFractionDigits: Int): TokenAmount {
        val multiplier = getAbsoluteMultiplier(newFractionDigits)
        val value = if (this.fractionDigits <= newFractionDigits) {
            this.quantity * multiplier
        } else {
            val remainder = this.quantity % multiplier
            require(remainder == 0L) {
                "Cannot rescale from ${this.fractionDigits} to $newFractionDigits fractional digits, " +
                    "precision loss detected (quantity=$quantity would have remainder=$remainder). "
            }
            this.quantity / multiplier
        }
        return TokenAmount(value, newFractionDigits)
    }

    /**
     * Truncates the quantity to match a lower fractional digit resolution while keeping the original resolution.
     * When the target resolution has fewer fractional digits (lower precision), truncates the quantity.
     * When the target resolution has more fractional digits (higher precision), returns the amount unchanged.
     *
     * Example: TokenAmount(11, 2) [0.11] truncated to 1 fractional digit gives TokenAmount(10, 2) [0.10],
     * quantity 11 is truncated to 10 to match 1 fractional digit precision, but stays in original 2-digit resolution.
     *
     * @param newFractionDigits The target number of fractional digits to truncate to
     * @return A new TokenAmount with truncated quantity in the original fractional digit resolution
     */
    fun truncate(newFractionDigits: Int): TokenAmount {
        val conversionMultiplier = getAbsoluteMultiplier(newFractionDigits)
        return if (this.fractionDigits > newFractionDigits) {
            copy(quantity = (quantity / conversionMultiplier) * conversionMultiplier)
        } else {
            this
        }
    }

    private fun getAbsoluteMultiplier(fractionDigits: Int): Long {
        if (this.fractionDigits == fractionDigits) return 1L
        val absoluteDifference = (this.fractionDigits - fractionDigits).absoluteValue
        return BigDecimal.TEN.pow(absoluteDifference).longValueExact()
    }
}
