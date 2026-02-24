package com.r3.corda.lib.solana.core.cordautils.internal

import net.corda.core.solana.AccountMeta
import net.corda.core.solana.Pubkey
import net.corda.core.solana.SolanaInstruction
import net.corda.core.utilities.OpaqueBytes
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object TokenCommands {
    fun mintTo(
        programId: Pubkey,
        mint: Pubkey,
        destination: Pubkey,
        authority: Pubkey,
        amount: Long,
    ): SolanaInstruction {
        val data = ByteBuffer.allocate(9)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(7)
            .putLong(amount)
            .array()
        return SolanaInstruction(
            programId,
            listOf(
                AccountMeta(mint, isSigner = false, isWritable = true),
                AccountMeta(destination, isSigner = false, isWritable = true),
                AccountMeta(authority, isSigner = true, isWritable = false),
            ),
            OpaqueBytes(data)
        )
    }

    fun burn(programId: Pubkey, mint: Pubkey, source: Pubkey, owner: Pubkey, amount: Long): SolanaInstruction {
        val data = ByteBuffer.allocate(9)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(8)
            .putLong(amount)
            .array()
        return SolanaInstruction(
            programId = programId,
            listOf(
                AccountMeta(source, isSigner = false, isWritable = true),
                AccountMeta(mint, isSigner = false, isWritable = true),
                AccountMeta(owner, isSigner = true, isWritable = false)
            ),
            OpaqueBytes(data)
        )
    }

    fun transferChecked(
        programId: Pubkey,
        source: Pubkey,
        mint: Pubkey,
        destination: Pubkey,
        owner: Pubkey,
        amount: Long,
        decimals: Byte,
    ): SolanaInstruction {
        val data = ByteBuffer.allocate(10)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(12)
            .putLong(amount)
            .put(decimals)
            .array()
        return SolanaInstruction(
            programId = programId,
            listOf(
                AccountMeta(source, isSigner = false, isWritable = true),
                AccountMeta(mint, isSigner = false, isWritable = false),
                AccountMeta(destination, isSigner = false, isWritable = true),
                AccountMeta(owner, isSigner = true, isWritable = false)
            ),
            OpaqueBytes(data)
        )
    }
}
