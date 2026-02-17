package com.r3.corda.lib.solana.core.internal

import software.sava.core.encoding.ByteUtil

object BorshUtils {
    fun toBytes(value: Short): ByteArray {
        val bytes = ByteArray(2)
        ByteUtil.putInt16LE(bytes, 0, value)
        return bytes
    }

    fun toBytes(value: Int): ByteArray {
        val bytes = ByteArray(4)
        ByteUtil.putInt32LE(bytes, 0, value)
        return bytes
    }

    fun toBytes(value: Long): ByteArray {
        val bytes = ByteArray(8)
        ByteUtil.putInt64LE(bytes, 0, value)
        return bytes
    }

    fun toBytes(value: Float): ByteArray {
        val bytes = ByteArray(4)
        ByteUtil.putFloat32LE(bytes, 0, value)
        return bytes
    }

    fun toBytes(value: Double): ByteArray {
        val bytes = ByteArray(8)
        ByteUtil.putFloat64LE(bytes, 0, value)
        return bytes
    }

    inline fun <reified E> readVector(
        readElement: (Int) -> E,
        elementSize: (E) -> Int,
        data: ByteArray,
        offset: Int,
    ): List<E> {
        val count = ByteUtil.getInt32LE(data, offset)
        var i = offset + 4
        val vector = Array(count) {
            val element = readElement(i)
            i += elementSize(element)
            element
        }
        return vector.asList()
    }

    fun <E> writeVector(vector: List<E>, writeElement: (E, Int) -> Int, data: ByteArray, offset: Int): Int {
        ByteUtil.putInt32LE(data, offset, vector.size)
        var i = offset + 4
        for (element in vector) {
            i += writeElement(element, i)
        }
        return i - offset
    }
}
