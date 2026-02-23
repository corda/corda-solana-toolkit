package com.r3.corda.lib.solana.bridging.token.states

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenAmountTest {
    // Single tests for strict object comparison to show differences with semantic quals
    @Test
    fun `equals returns false for same quantity but different fractional digits`() {
        val amount1 = TokenAmount(100, 0)
        val amount2 = TokenAmount(1000, 1)

        assertNotEquals(amount1, amount2)
    }

    // Tests for semantic value comparison
    @Test
    fun `isNumericallyEqual returns true for amounts with same value but different fractional digits`() {
        // 100 with 0 fractional digits = 1000 with 1 fractional digit
        val amount1 = TokenAmount(100, 0)
        val amount2 = TokenAmount(1000, 1)

        assertTrue(amount1.isNumericallyEqual(amount2))
        assertTrue(amount2.isNumericallyEqual(amount1)) // test symmetry
    }

    @Test
    fun `isNumericallyEqual returns true for amounts with same value and multiple fractional digit differences`() {
        // 100 with 0 fractional digits = 10000 with 2 fractional digits
        val amount1 = TokenAmount(100, 0)
        val amount2 = TokenAmount(10000, 2)

        assertTrue(amount1.isNumericallyEqual(amount2))
    }

    @Test
    fun `isNumericallyEqual returns true for same amount with same fractional digits`() {
        val amount1 = TokenAmount(100, 2)
        val amount2 = TokenAmount(100, 2)

        assertTrue(amount1.isNumericallyEqual(amount2))
    }

    @Test
    fun `isNumericallyEqual returns false for different amounts with same fractional digits`() {
        val amount1 = TokenAmount(100, 2)
        val amount2 = TokenAmount(200, 2)

        assertFalse(amount1.isNumericallyEqual(amount2))
    }

    @Test
    fun `isNumericallyEqual returns false for different amounts with different fractional digits`() {
        // 100 with 0 fractional digits != 1001 with 1 fractional digit
        val amount1 = TokenAmount(100, 0)
        val amount2 = TokenAmount(1001, 1)

        assertFalse(amount1.isNumericallyEqual(amount2))
    }

    @Test
    fun `isNumericallyEqual returns true for complex conversion scenarios`() {
        // 5 with 0 fractional digits = 50 with 1 fractional digit = 500 with 2 fractional digits
        val amount1 = TokenAmount(5, 0)
        val amount2 = TokenAmount(50, 1)
        val amount3 = TokenAmount(500, 2)

        assertTrue(amount1.isNumericallyEqual(amount2))
        assertTrue(amount2.isNumericallyEqual(amount3))
        assertTrue(amount1.isNumericallyEqual(amount3)) // test transitivity
    }

    @Test
    fun `isNumericallyEqual works correctly with zero amounts`() {
        val amount1 = TokenAmount(0, 0)
        val amount2 = TokenAmount(0, 1)
        val amount3 = TokenAmount(0, 5)

        assertTrue(amount1.isNumericallyEqual(amount2))
        assertTrue(amount2.isNumericallyEqual(amount3))
        assertTrue(amount1.isNumericallyEqual(amount3))
    }

    @Test
    fun `isNumericallyEqual./ works with large quantities and different fractional digits`() {
        // 1000000 with 0 fractional digits = 10000000 with 1 fractional digit
        val amount1 = TokenAmount(1000000, 0)
        val amount2 = TokenAmount(10000000, 1)

        assertTrue(amount1.isNumericallyEqual(amount2))
    }

    // Tests for convertToAndKeepOriginal method - converting to lower resolution
    @Test
    fun `convertToAndKeepOriginal with lower resolution truncates and keeps original with zeroed fraction`() {
        // Solana: 0.11 (quantity=11, fractionDigits=2)
        // Convert to Corda with 1 fractional digit
        // Expected: 0.1 (quantity=1, fractionDigits=1) + original at 0.10 (quantity=10, fractionDigits=2)
        // The second value keeps the original resolution with fraction digits zeroed out
        val solanaAmount = TokenAmount(quantity = 11, fractionDigits = 2)
        val (newAmount, originalResolution) = solanaAmount.convertToAndKeepOriginal(newFractionDigits = 1)

        // New amount should have 1 fractional digit with quantity 1 (representing 0.1)
        assertEquals(1, newAmount.fractionDigits)
        assertEquals(1L, newAmount.quantity)

        // Original resolution should be in original resolution (2 fractional digits)
        // with quantity 10 (representing 0.10)
        assertEquals(2, originalResolution.fractionDigits)
        assertEquals(10L, originalResolution.quantity)
    }

    @Test
    fun `convertToAndKeepOriginal with lower resolution simple case`() {
        // Amount: 123 with 2 fractional digits (1.23)
        // Convert to 1 fractional digit
        // Expected: 12 with 1 fractional digit (1.2) + original 120 with 2 fractional digits (1.20)
        val amount = TokenAmount(quantity = 123, fractionDigits = 2)
        val (newAmount, original) = amount.convertToAndKeepOriginal(newFractionDigits = 1)

        assertEquals(1, newAmount.fractionDigits)
        assertEquals(12L, newAmount.quantity)

        assertEquals(2, original.fractionDigits)
        assertEquals(120L, original.quantity)
    }

    @Test
    fun `convertToAndKeepOriginal with lower resolution exact division`() {
        // Amount: 120 with 2 fractional digits (1.20)
        // Convert to 1 fractional digit
        // Expected: 12 with 1 fractional digit (1.2) + original 120 with 2 fractional digits (1.20)
        val amount = TokenAmount(quantity = 120, fractionDigits = 2)
        val (newAmount, original) = amount.convertToAndKeepOriginal(newFractionDigits = 1)

        assertEquals(1, newAmount.fractionDigits)
        assertEquals(12L, newAmount.quantity)

        assertEquals(2, original.fractionDigits)
        assertEquals(120L, original.quantity)
    }

    @Test
    fun `convertToAndKeepOriginal with lower resolution many fractional digits`() {
        // Amount: 123456 with 5 fractional digits (1.23456)
        // Convert to 2 fractional digits
        // Expected: 123 with 2 fractional digits (1.23) + original 123000 with 5 fractional digits (1.23000)
        val amount = TokenAmount(quantity = 123456, fractionDigits = 5)
        val (newAmount, original) = amount.convertToAndKeepOriginal(newFractionDigits = 2)

        assertEquals(2, newAmount.fractionDigits)
        assertEquals(123L, newAmount.quantity)

        assertEquals(5, original.fractionDigits)
        assertEquals(123000L, original.quantity)
    }

    // Tests for convertToAndKeepOriginal method - converting to higher resolution
    @Test
    fun `convertToAndKeepOriginal with higher resolution scales quantity and produces zero remainder`() {
        // Solana: 0.11 (quantity=11, fractionDigits=2)
        // Convert to Corda with 3 fractional digits
        // Expected: 0.110 (quantity=110, fractionDigits=3) + original 0.11 (quantity=11, fractionDigits=2)
        val solanaAmount = TokenAmount(quantity = 11, fractionDigits = 2)
        val (newAmount, original) = solanaAmount.convertToAndKeepOriginal(newFractionDigits = 3)

        // New amount should have 3 fractional digits with quantity 110 (representing 0.110)
        assertEquals(3, newAmount.fractionDigits)
        assertEquals(110L, newAmount.quantity)

        // Original should be the original amount unchanged (in original resolution)
        assertEquals(2, original.fractionDigits)
        assertEquals(11L, original.quantity)
    }

    @Test
    fun `convertToAndKeepOriginal with higher resolution simple case`() {
        // Amount: 12 with 1 fractional digit (1.2)
        // Convert to 3 fractional digits
        // Expected: 1200 with 3 fractional digits (1.200) + original 12 with 1 fractional digit (1.2)
        val amount = TokenAmount(quantity = 12, fractionDigits = 1)
        val (newAmount, original) = amount.convertToAndKeepOriginal(newFractionDigits = 3)

        assertEquals(3, newAmount.fractionDigits)
        assertEquals(1200L, newAmount.quantity)

        assertEquals(1, original.fractionDigits)
        assertEquals(12L, original.quantity)
    }

    @Test
    fun `convertToAndKeepOriginal with higher resolution many steps`() {
        // Amount: 5 with 0 fractional digits (5)
        // Convert to 3 fractional digits
        // Expected: 5000 with 3 fractional digits (5.000) + original 5 with 0 fractional digits (5)
        val amount = TokenAmount(quantity = 5, fractionDigits = 0)
        val (newAmount, original) = amount.convertToAndKeepOriginal(newFractionDigits = 3)

        assertEquals(3, newAmount.fractionDigits)
        assertEquals(5000L, newAmount.quantity)

        assertEquals(0, original.fractionDigits)
        assertEquals(5L, original.quantity)
    }

    // Tests for newAmountAndRemainder with same resolution
    @Test
    fun `newAmountAndRemainder with same resolution scales quantity and produces original as remainder`() {
        // Amount: 100 with 2 fractional digits
        // Convert to 2 fractional digits (same)
        // Expected: 100 with 2 fractional digits + remainder 100 with 2 fractional digits
        val amount = TokenAmount(quantity = 100, fractionDigits = 2)
        val (newAmount, remainder) = amount.convertToAndKeepOriginal(newFractionDigits = 2)

        assertEquals(2, newAmount.fractionDigits)
        assertEquals(100L, newAmount.quantity)

        assertEquals(2, remainder.fractionDigits)
        assertEquals(100L, remainder.quantity)
    }

    @Test
    fun `newAmountAndRemainder roundtrip test - lower then higher resolution`() {
        // Start with: 11 with 2 fractional digits (0.11)
        // Convert to 1 fractional digit -> (1 with 1 fractional digit + 10 with 2 fractional digits)
        val originalAmount = TokenAmount(quantity = 11, fractionDigits = 2)
        val (mainAmount, original) = originalAmount.convertToAndKeepOriginal(newFractionDigits = 1)

        // The main amount converted back + original should equal the original
        // Main: 1 with 1 digit = 10 with 2 digits
        // Original: 10 with 2 digits
        // Sum: 20 with 2 digits = 0.20 (original was 0.11, so some precision was lost in truncation)
        val mainConverted = mainAmount.convertTo(2)
        val totalQuantity = mainConverted.quantity + original.quantity
        // The original represents the rounded-down kept amount, not the lost amount
        // So total should be = (original / multiplier) * multiplier, which is the rounded-down original
        assertEquals(20L, totalQuantity)
    }

    @Test
    fun `newAmountAndRemainder with zero amount`() {
        // Amount: 0 with 2 fractional digits
        // Convert to 1 fractional digit
        // Expected: 0 with 1 fractional digit + original 0 with 2 fractional digits
        val amount = TokenAmount(quantity = 0, fractionDigits = 2)
        val (newAmount, original) = amount.convertToAndKeepOriginal(newFractionDigits = 1)

        assertEquals(1, newAmount.fractionDigits)
        assertEquals(0L, newAmount.quantity)

        assertEquals(2, original.fractionDigits)
        assertEquals(0L, original.quantity)
    }

    @Test
    fun `newAmountAndRemainder with large quantities`() {
        // Amount: 999999 with 3 fractional digits (999.999)
        // Convert to 2 fractional digits
        // Expected: 99999 with 2 fractional digits (999.99) + original 999990 with 3 fractional digits (999.990)
        val amount = TokenAmount(quantity = 999999, fractionDigits = 3)
        val (newAmount, original) = amount.convertToAndKeepOriginal(newFractionDigits = 2)

        assertEquals(2, newAmount.fractionDigits)
        assertEquals(99999L, newAmount.quantity)

        assertEquals(3, original.fractionDigits)
        assertEquals(999990L, original.quantity)
    }
}
