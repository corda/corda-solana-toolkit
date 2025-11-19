package com.r3.corda.lib.solana.bridging.token.flows

import com.lmax.solana4j.client.api.Commitment
import net.corda.solana.sdk.internal.Token2022

val tokenProgramId = Token2022.PROGRAM_ID.toPublicKey()
val commitment: Commitment = Commitment.CONFIRMED
