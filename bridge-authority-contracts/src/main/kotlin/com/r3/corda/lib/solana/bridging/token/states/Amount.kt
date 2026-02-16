package com.r3.corda.lib.solana.bridging.token.states

import net.corda.core.serialization.CordaSerializable
import kotlin.math.pow

@CordaSerializable
data class Amount(val quantity: Long, val fractionalDigits: Int) {

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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Amount) return false

        // Convert both amounts to the same fractional digits for comparison
        val maxFractionalDigits = maxOf(this.fractionalDigits, other.fractionalDigits)
        val thisConverted = this.convertTo(maxFractionalDigits)
        val otherConverted = other.convertTo(maxFractionalDigits)

        return thisConverted.quantity == otherConverted.quantity
    }

    override fun hashCode(): Int {
        // Normalize to a common representation for consistent hashing
        val normalized = this.convertTo(0)
        return normalized.quantity.hashCode()
    }
}
