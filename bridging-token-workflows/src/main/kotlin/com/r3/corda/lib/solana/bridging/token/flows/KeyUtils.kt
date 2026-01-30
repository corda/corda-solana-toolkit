package com.r3.corda.lib.solana.bridging.token.flows

import net.corda.solana.sdk.instruction.Pubkey
import software.sava.core.accounts.PublicKey

fun Pubkey.toPublicKey(): PublicKey = PublicKey.createPubKey(bytes)

fun PublicKey.toPubkey(): Pubkey = Pubkey(copyByteArray())
