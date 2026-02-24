package com.r3.corda.lib.solana.core.rust

import software.sava.core.borsh.Borsh
import java.math.BigInteger
import java.nio.ByteBuffer

class U128(val bigint: BigInteger) : Borsh {
    companion object {
        const val BYTES: Int = 16

        @JvmStatic
        @JvmOverloads
        fun read(data: ByteArray, offset: Int = 0): U128 {
            val bytes = data.copyOfRange(offset, offset + BYTES)
            bytes.reverse()
            return U128(BigInteger(1, bytes))
        }
    }

    init {
        require(bigint.signum() != -1)
        require(bigint.bitLength() <= 128)
    }

    override fun l(): Int = BYTES

    override fun write(data: ByteArray, offset: Int): Int {
        val buffer = ByteBuffer.wrap(data, offset, BYTES)
        val bytes = bigint.toByteArray()
        val size: Int
        val limit: Int
        // toByteArray includes a leading sign byte (which is zero), which for max u128 means an extra byte is produced.
        if (bytes.size > BYTES) {
            size = BYTES
            limit = bytes.size - BYTES
        } else {
            size = bytes.size
            limit = 0
        }
        for (i in bytes.size - 1 downTo limit) {
            buffer.put(bytes[i])
        }
        repeat(BYTES - size) {
            buffer.put(0.toByte())
        }
        return BYTES
    }

    override fun equals(other: Any?): Boolean = other is U128 && other.bigint == bigint

    override fun hashCode(): Int = 31 * bigint.hashCode()

    override fun toString(): String = bigint.toString()
}
