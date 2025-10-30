package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.workflows.flows.issue.addIssueTokens
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

@InitiatingFlow
@StartableByRPC
class IssueSimpleTokenFlow(
    private val mint: TokenType,
    private val quantity: Long,
    private val notaryName: CordaX500Name,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first { it.name == notaryName }
        val amount = (quantity of mint).issuedBy(ourIdentity)
        val token = FungibleToken(amount, ourIdentity)
        val txb = TransactionBuilder(notary)
        addIssueTokens(txb, listOf(token))
        val ptx = serviceHub.signInitialTransaction(txb)
        return subFlow(FinalityFlow(ptx, emptyList()))
    }
}

@StartableByRPC
class QuerySimpleTokensFlow(
    private val issuer: Party,
    private val tokenType: TokenType,
    private val status: Vault.StateStatus = Vault.StateStatus.UNCONSUMED,
) : FlowLogic<List<StateAndRef<FungibleToken>>>() {
    @Suspendable
    override fun call(): List<StateAndRef<FungibleToken>> {
        val criteria = QueryCriteria.VaultQueryCriteria(status)
        val all = serviceHub.vaultService.queryBy(FungibleToken::class.java, criteria).states
        return all.filter { stateAndRef ->
            val fungibleToken = stateAndRef.state.data

            val holderWellKnown = serviceHub.identityService.wellKnownPartyFromAnonymous(fungibleToken.holder)
            val issuerWellKnown =
                serviceHub.identityService.wellKnownPartyFromAnonymous(fungibleToken.amount.token.issuer)

            val isSimple = fungibleToken.amount.token.tokenType == tokenType
            val holderOk = holderWellKnown == ourIdentity
            val issuerOk = issuerWellKnown == issuer

            isSimple && holderOk && issuerOk
        }
    }
}
