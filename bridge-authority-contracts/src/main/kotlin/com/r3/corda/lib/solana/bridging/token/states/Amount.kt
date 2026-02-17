package com.r3.corda.lib.solana.bridging.token.states

import net.corda.core.serialization.CordaSerializable
import kotlin.math.pow

@CordaSerializable
data class Amount(val quantity: Long, val fractionalDigits: Int) {
    init {
        require(quantity >= 0) { "Quantity must be 0 or positive" }
        require(fractionalDigits >= 0) { "Fractional digits must be 0 or positive" }
    }

    fun convertTo(fractionalDigits: Int): Amount {
        val multiplier = getConversionMultiplier(fractionalDigits)
        return if (this.fractionalDigits < fractionalDigits) {
            Amount(this.quantity * multiplier, fractionalDigits)
        } else {
            Amount(this.quantity / multiplier, fractionalDigits)
        }
    }

    fun getConversionMultiplier(fractionalDigits: Int): Long {
        if (this.fractionalDigits == fractionalDigits) return 1L
        return if (this.fractionalDigits > fractionalDigits) {
            (this.fractionalDigits - fractionalDigits).let { decimalsDifference ->
                10.0.pow(decimalsDifference).toLong()
            }
        } else {
            (fractionalDigits - this.fractionalDigits).let { decimalsDifference ->
                10.0.pow(decimalsDifference).toLong()
            }
        }
    }

    /**
     * Semantic equivalence: checks if two amounts represent the same value
     * even if they have different representations.
     *
     * Example: Amount(100, 0) and Amount(1000, 1) both represent the same value
     * and will return true, even though they have different fields.
     *
     * Use this when you need to compare the actual monetary value.
     * Use equals() when you need to compare the exact representation.
     */
    fun hasSameValueAs(other: Amount): Boolean {
        val maxFractionalDigits = maxOf(this.fractionalDigits, other.fractionalDigits)
        val thisConverted = this.convertTo(maxFractionalDigits)
        val otherConverted = other.convertTo(maxFractionalDigits)
        return thisConverted.quantity == otherConverted.quantity
    }
}
