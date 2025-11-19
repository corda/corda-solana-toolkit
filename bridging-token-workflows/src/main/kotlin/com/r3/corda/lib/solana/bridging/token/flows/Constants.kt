package com.r3.corda.lib.solana.bridging.token.flows

import net.corda.solana.sdk.internal.Token2022

val globalCommitmentLevelLmax = com.lmax.solana4j.client.api.Commitment.CONFIRMED
val globalCommitmentLevelSava = software.sava.rpc.json.http.request.Commitment.CONFIRMED
val tokenProgramId = Token2022.PROGRAM_ID.toPublicKey()
