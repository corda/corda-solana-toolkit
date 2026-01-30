package com.r3.corda.lib.solana.bridging.token.flows

import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.SolanaAccounts
import software.sava.rpc.json.http.request.Commitment

val globalCommitmentLevel: Commitment = Commitment.CONFIRMED
val tokenProgramId: PublicKey = SolanaAccounts.MAIN_NET.token2022Program()
