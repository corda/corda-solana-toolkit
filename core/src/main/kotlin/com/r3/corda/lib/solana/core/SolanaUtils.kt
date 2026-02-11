package com.r3.corda.lib.solana.core

import software.sava.core.accounts.Signer

object SolanaUtils {
    @JvmStatic
    fun randomSigner(): Signer = Signer.createFromPrivateKey(Signer.generatePrivateKeyBytes())
}
