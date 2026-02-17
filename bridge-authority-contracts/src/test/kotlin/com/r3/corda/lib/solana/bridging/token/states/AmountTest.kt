package com.r3.corda.lib.solana.bridging.token.states

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmountTest {
    // Tests for strict object comparison
    @Test
    fun `equals returns true for same amount with same fractional digits`() {
        val amount1 = Amount(100, 2)
        val amount2 = Amount(100, 2)

        assertEquals(amount1, amount2)
    }

    @Test
    fun `equals returns false for different amounts with same fractional digits`() {
        val amount1 = Amount(100, 2)
        val amount2 = Amount(200, 2)

        assertNotEquals(amount1, amount2)
    }

    @Test
    fun `equals returns false for same quantity but different fractional digits`() {
        val amount1 = Amount(100, 0)
        val amount2 = Amount(100, 1)

        assertNotEquals(amount1, amount2)
    }

    @Test
    fun `equals returns true for same reference`() {
        val amount = Amount(100, 2)

        assertEquals(amount, amount)
    }

    @Test
    fun `equals returns false for null`() {
        val amount = Amount(100, 2)

        assertNotEquals(amount, null)
    }

    @Test
    fun `equals returns false for different type`() {
        val amount = Amount(100, 2)

        assertNotEquals(amount, "not an amount")
    }

    @Test
    fun `hashCode is consistent for identical amounts`() {
        val amount1 = Amount(100, 2)
        val amount2 = Amount(100, 2)

        assertEquals(amount1.hashCode(), amount2.hashCode())
    }

    @Test
    fun `hashCode differs for different quantities`() {
        val amount1 = Amount(100, 2)
        val amount2 = Amount(200, 2)

        assertNotEquals(amount1.hashCode(), amount2.hashCode())
    }

    @Test
    fun `hashCode differs for different fractional digits`() {
        val amount1 = Amount(100, 0)
        val amount2 = Amount(100, 1)

        assertNotEquals(amount1.hashCode(), amount2.hashCode())
    }

    // Tests for semantic value comparison

    @Test
    fun `hasSameValueAs returns true for amounts with same value but different fractional digits`() {
        // 100 with 0 fractional digits = 1000 with 1 fractional digit
        val amount1 = Amount(100, 0)
        val amount2 = Amount(1000, 1)

        assertTrue(amount1.hasSameValueAs(amount2))
        assertTrue(amount2.hasSameValueAs(amount1)) // test symmetry
    }

    @Test
    fun `hasSameValueAs returns true for amounts with same value and multiple fractional digit differences`() {
        // 100 with 0 fractional digits = 10000 with 2 fractional digits
        val amount1 = Amount(100, 0)
        val amount2 = Amount(10000, 2)

        assertTrue(amount1.hasSameValueAs(amount2))
    }

    @Test
    fun `hasSameValueAs returns true for same amount with same fractional digits`() {
        val amount1 = Amount(100, 2)
        val amount2 = Amount(100, 2)

        assertTrue(amount1.hasSameValueAs(amount2))
    }

    @Test
    fun `hasSameValueAs returns false for different amounts with same fractional digits`() {
        val amount1 = Amount(100, 2)
        val amount2 = Amount(200, 2)

        assertFalse(amount1.hasSameValueAs(amount2))
    }

    @Test
    fun `hasSameValueAs returns false for different amounts with different fractional digits`() {
        // 100 with 0 fractional digits != 1001 with 1 fractional digit
        val amount1 = Amount(100, 0)
        val amount2 = Amount(1001, 1)

        assertFalse(amount1.hasSameValueAs(amount2))
    }

    @Test
    fun `hasSameValueAs returns true for complex conversion scenarios`() {
        // 5 with 0 fractional digits = 50 with 1 fractional digit = 500 with 2 fractional digits
        val amount1 = Amount(5, 0)
        val amount2 = Amount(50, 1)
        val amount3 = Amount(500, 2)

        assertTrue(amount1.hasSameValueAs(amount2))
        assertTrue(amount2.hasSameValueAs(amount3))
        assertTrue(amount1.hasSameValueAs(amount3)) // test transitivity
    }

    @Test
    fun `hasSameValueAs works correctly with zero amounts`() {
        val amount1 = Amount(0, 0)
        val amount2 = Amount(0, 1)
        val amount3 = Amount(0, 5)

        assertTrue(amount1.hasSameValueAs(amount2))
        assertTrue(amount2.hasSameValueAs(amount3))
        assertTrue(amount1.hasSameValueAs(amount3))
    }

    @Test
    fun `hasSameValueAs works with large quantities and different fractional digits`() {
        // 1000000 with 0 fractional digits = 10000000 with 1 fractional digit
        val amount1 = Amount(1000000, 0)
        val amount2 = Amount(10000000, 1)

        assertTrue(amount1.hasSameValueAs(amount2))
    }
}
