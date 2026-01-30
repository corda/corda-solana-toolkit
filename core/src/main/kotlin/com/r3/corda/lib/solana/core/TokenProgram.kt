package com.r3.corda.lib.solana.core

import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.SolanaAccounts

/**
 * Enum for the two Solana token programs.
 */
enum class TokenProgram(val programId: PublicKey) {
    TOKEN(SolanaAccounts.MAIN_NET.tokenProgram()),
    TOKEN_2022(SolanaAccounts.MAIN_NET.token2022Program()),
    ;

    companion object {
        /**
         * Returns the matching [TokenProgram] for the given program ID, or throws [IllegalArgumentException].
         */
        @JvmStatic
        fun valueOf(programId: PublicKey?): TokenProgram {
            return when (programId) {
                SolanaAccounts.MAIN_NET.tokenProgram() -> TOKEN
                SolanaAccounts.MAIN_NET.token2022Program() -> TOKEN_2022
                else -> throw IllegalArgumentException("$programId")
            }
        }
    }
}
