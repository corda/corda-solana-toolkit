package com.r3.corda.lib.solana.bridging.token.flows

import com.lmax.solana4j.Solana
import com.lmax.solana4j.api.PublicKey
import net.corda.solana.sdk.instruction.Pubkey

fun Pubkey.toPublicKey(): PublicKey = Solana.account(bytes)

fun PublicKey.toPubkey(): Pubkey = Pubkey(bytes())
