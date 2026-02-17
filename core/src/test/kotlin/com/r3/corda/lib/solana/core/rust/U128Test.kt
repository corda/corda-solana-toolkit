package com.r3.corda.lib.solana.core.rust

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.math.BigInteger

class U128Test {
    @Test
    fun `U128 rejects BigIntegers larger than 128 bits`() {
        U128(BigInteger.TWO.pow(128) - BigInteger.ONE)
        val bigint = BigInteger.TWO.pow(128)
        assertThatIllegalArgumentException().isThrownBy { U128(bigint) }
    }

    @Test
    fun `U128 rejects negative numbers`() {
        U128(BigInteger.ZERO)
        val minusOne = (-1).toBigInteger()
        assertThatIllegalArgumentException().isThrownBy { U128(minusOne) }
        U128(BigInteger.ONE)
    }

    @Test
    fun `U128 write`() {
        assertThat(writeU128(BigInteger.ZERO)).isEqualTo(ByteArray(16))
        assertThat(writeU128(BigInteger.ONE))
            .isEqualTo(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertThat(writeU128(0x12345678.toBigInteger()))
            .isEqualTo(byteArrayOf(0x78, 0x56, 0x34, 0x12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertThat(writeU128(BigInteger.TWO.pow(128) - BigInteger.ONE)).isEqualTo(ByteArray(16) { 0xff.toByte() })
    }

    @Test
    fun `U128 read`() {
        assertThat(readU128(ByteArray(16))).isEqualTo(BigInteger.ZERO)
        assertThat(readU128(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
            .isEqualTo(BigInteger.ONE)
        assertThat(readU128(byteArrayOf(0x78, 0x56, 0x34, 0x12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
            .isEqualTo(0x12345678.toBigInteger())
        assertThat(readU128(ByteArray(16) { 0xff.toByte() }))
            .isEqualTo(BigInteger.TWO.pow(128) - BigInteger.ONE)
    }

    private fun writeU128(bigint: BigInteger): ByteArray = U128(bigint).write()

    private fun readU128(bytes: ByteArray): BigInteger = U128.read(bytes).bigint
}
