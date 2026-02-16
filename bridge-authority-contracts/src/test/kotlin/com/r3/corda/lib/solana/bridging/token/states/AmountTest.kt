package com.r3.corda.lib.solana.bridging.token.states

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AmountTest {

    @Test
    fun `equals returns true for amounts with same value but different fractional digits`() {
        // 100 with 0 fractional digits = 1000 with 1 fractional digit
        val amount1 = Amount(100, 0)
        val amount2 = Amount(1000, 1)

        assertEquals(amount1, amount2)
        assertEquals(amount2, amount1) // test symmetry
    }

    @Test
    fun `equals returns true for amounts with same value and multiple fractional digit differences`() {
        // 100 with 0 fractional digits = 10000 with 2 fractional digits
        val amount1 = Amount(100, 0)
        val amount2 = Amount(10000, 2)

        assertEquals(amount1, amount2)
    }

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
    fun `equals returns false for different amounts with different fractional digits`() {
        // 100 with 0 fractional digits != 1001 with 1 fractional digit
        val amount1 = Amount(100, 0)
        val amount2 = Amount(1001, 1)

        assertNotEquals(amount1, amount2)
    }

    @Test
    fun `equals returns true for complex conversion scenarios`() {
        // 5 with 0 fractional digits = 50 with 1 fractional digit = 500 with 2 fractional digits
        val amount1 = Amount(5, 0)
        val amount2 = Amount(50, 1)
        val amount3 = Amount(500, 2)

        assertEquals(amount1, amount2)
        assertEquals(amount2, amount3)
        assertEquals(amount1, amount3) // test transitivity
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
    fun `hashCode is consistent with equals for same value different fractional digits`() {
        // Amounts that are equal should have same hashCode
        val amount1 = Amount(100, 0)
        val amount2 = Amount(1000, 1)

        assertEquals(amount1, amount2)
        assertEquals(amount1.hashCode(), amount2.hashCode())
    }

    @Test
    fun `hashCode is consistent for multiple equal amounts`() {
        val amount1 = Amount(5, 0)
        val amount2 = Amount(50, 1)
        val amount3 = Amount(500, 2)

        assertEquals(amount1.hashCode(), amount2.hashCode())
        assertEquals(amount2.hashCode(), amount3.hashCode())
        assertEquals(amount1.hashCode(), amount3.hashCode())
    }

    @Test
    fun `hashCode is same for same amount`() {
        val amount = Amount(100, 2)

        assertEquals(amount.hashCode(), amount.hashCode())
    }

    @Test
    fun `equals works correctly with zero amounts`() {
        val amount1 = Amount(0, 0)
        val amount2 = Amount(0, 1)
        val amount3 = Amount(0, 5)

        assertEquals(amount1, amount2)
        assertEquals(amount2, amount3)
        assertEquals(amount1, amount3)
    }

    @Test
    fun `equals works with large quantities and different fractional digits`() {
        // 1000000 with 0 fractional digits = 10000000 with 1 fractional digit
        val amount1 = Amount(1000000, 0)
        val amount2 = Amount(10000000, 1)

        assertEquals(amount1, amount2)
        assertEquals(amount1.hashCode(), amount2.hashCode())
    }
}

