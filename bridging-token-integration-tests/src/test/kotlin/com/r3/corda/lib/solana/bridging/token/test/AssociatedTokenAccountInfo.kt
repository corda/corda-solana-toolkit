package com.r3.corda.lib.solana.bridging.token.test

import software.sava.core.accounts.PublicKey

data class AssociatedTokenAccountInfo(
    val mint: PublicKey,
    val tokenAccount: PublicKey,
)
