package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.AbstractParty
import net.corda.core.node.ServiceHub

// TODO this potentially could go to BridgingAuthorityBootstrapService
fun findPreviousHolderOfToken(
    serviceHub: ServiceHub,
    output: StateAndRef<FungibleToken>,
): AbstractParty {
    val txHash = output.ref.txhash
    val stx =
        serviceHub.validatedTransactions.getTransaction(txHash)
            ?: error("Transaction $txHash not found")

    val inputTokens: List<FungibleToken> =
        stx.toLedgerTransaction(serviceHub).inputsOfType<FungibleToken>()
    require(inputTokens.isNotEmpty()) { "Transaction doesn't contains inputs of fungible token" }

    val holders = inputTokens.map { it.holder }.toSet()
    require(holders.size == 1) { "Transaction contains tokens of multiple holders" } // This should not happen

    return holders.single()
}
