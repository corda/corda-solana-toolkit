package com.r3.corda.lib.solana.core.cordautils

import com.r3.corda.lib.solana.core.cordautils.internal.TokenCommands
import net.corda.core.solana.Pubkey
import net.corda.core.solana.SolanaInstruction

/**
 * Collection of utilities for the [SPL Token](https://www.solana-program.com/docs/token) program.
 */
object SplToken {
    @JvmField
    val PROGRAM_ID: Pubkey = Pubkey.fromBase58("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")

    @JvmStatic
    fun mintTo(mint: Pubkey, destination: Pubkey, authority: Pubkey, amount: Long): SolanaInstruction {
        return TokenCommands.mintTo(PROGRAM_ID, mint, destination, authority, amount)
    }

    @JvmStatic
    fun burn(mint: Pubkey, source: Pubkey, owner: Pubkey, amount: Long): SolanaInstruction {
        return TokenCommands.burn(PROGRAM_ID, mint, source, owner, amount)
    }

    @JvmStatic
    fun transferChecked(
        source: Pubkey,
        mint: Pubkey,
        destination: Pubkey,
        owner: Pubkey,
        amount: Long,
        decimals: Byte,
    ): SolanaInstruction {
        return TokenCommands.transferChecked(PROGRAM_ID, source, mint, destination, owner, amount, decimals)
    }
}
