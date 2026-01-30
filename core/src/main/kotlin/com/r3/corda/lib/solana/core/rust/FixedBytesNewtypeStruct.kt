package com.r3.corda.lib.solana.core.rust

import software.sava.core.borsh.Borsh

/**
 * Extended by classes which represent Rust
 * ["newtype" structs](https://rust-unofficial.github.io/patterns/patterns/behavioural/newtype.html) over a fixed size
 * byte array.
 */
abstract class FixedBytesNewtypeStruct(private val bytesInternal: ByteArray) : Borsh {
    val bytes: ByteArray
        get() = bytesInternal.clone()

    init {
        require(bytesInternal.size == l()) { "${javaClass.name} must be ${l()} bytes" }
    }

    final override fun write(data: ByteArray, offset: Int): Int = Borsh.writeArray(bytesInternal, data, offset)

    final override fun write(): ByteArray = bytes

    override fun toString(): String = "${javaClass.simpleName}(${encodeToHex(bytesInternal)})"

    private companion object {
        private val hexCode = "0123456789ABCDEF".toCharArray()

        private fun encodeToHex(data: ByteArray): String {
            val r = StringBuilder(data.size * 2)
            for (b in data) {
                r.append(hexCode[(b.toInt() shr 4) and 0xF])
                r.append(hexCode[b.toInt() and 0xF])
            }
            return r.toString()
        }
    }
}
