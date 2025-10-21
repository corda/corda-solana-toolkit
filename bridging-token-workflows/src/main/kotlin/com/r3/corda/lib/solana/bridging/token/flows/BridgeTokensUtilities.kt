package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.AbstractParty
import net.corda.core.node.ServiceHub

//TODO this potentially could go to BridgingAuthorityBootstrapService
@Suppress("FunctionSignature")
fun previousOwnerOf(
    serviceHub: ServiceHub,
    output: StateAndRef<FungibleToken>,
): AbstractParty? {
    val txHash = output.ref.txhash
    val stx =
        serviceHub.validatedTransactions.getTransaction(txHash)
            ?: error("Producing transaction $txHash not found")

    val inputTokens: List<FungibleToken> =
        stx.toLedgerTransaction(serviceHub).inputsOfType<FungibleToken>()

    // We can assume here that there will be only a single owner of the input tokens as
    // the transaction is a move transaction.
    return inputTokens.map { it.holder }.toSet().singleOrNull()
}
