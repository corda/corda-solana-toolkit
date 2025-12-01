package com.r3.corda.lib.solana.bridging.token.test

import com.lmax.solana4j.api.PublicKey

data class AssociatedTokenAccountInfo(
    val mint: PublicKey,
    val tokenAccount: PublicKey,
)
