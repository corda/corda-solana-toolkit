package com.r3.corda.lib.solana.bridging.token.flows

import net.corda.solana.sdk.Token2022
import software.sava.rpc.json.http.request.Commitment

val globalCommitmentLevel = Commitment.CONFIRMED
val tokenProgramId = Token2022.PROGRAM_ID.toPublicKey()
