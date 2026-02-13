package com.r3.corda.lib.solana.bridging.token.flows

import net.corda.core.solana.Pubkey
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.SolanaAccounts
import software.sava.rpc.json.http.request.Commitment

val globalCommitmentLevel = Commitment.CONFIRMED
val tokenProgramId: PublicKey = SolanaAccounts.MAIN_NET.token2022Program()

fun PublicKey.toPubkey(): Pubkey = Pubkey(copyByteArray())

fun Pubkey.toPublicKey(): PublicKey = PublicKey.createPubKey(bytes)

private fun validateFactor(factor: Long) {
    require(factor > 0) { "factor must be > 0" }
    var f = factor
    while (f > 1 && f % 10L == 0L) {
        f /= 10L
    }
    require(f == 1L) { "factor must be a power of 10. Got: $factor" }
}

fun truncateByFactor(value: Long, factor: Long): Long {
    validateFactor(factor)
    val newValue = value / factor
    return newValue
}

fun zeroOutFractionDigits(value: Long, factor: Long): Long {
    validateFactor(factor)
    val newValue = (value / factor) * factor
    return newValue
}
