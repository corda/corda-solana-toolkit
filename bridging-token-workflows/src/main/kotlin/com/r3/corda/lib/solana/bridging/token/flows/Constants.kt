package com.r3.corda.lib.solana.bridging.token.flows

import net.corda.solana.notary.common.rpc.DefaultRpcParams
import net.corda.solana.sdk.internal.Token2022

val tokenProgramId = Token2022.PROGRAM_ID.toPublicKey()
val rpcParams = DefaultRpcParams()
