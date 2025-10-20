package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.workflows.flows.issue.IssueTokensFlow
import com.r3.corda.lib.tokens.workflows.flows.issue.IssueTokensFlowHandler
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction

@InitiatingFlow
@StartableByRPC
class IssueSimpleTokenFlow(
    private val mint: TokenType,
    private val quantity: Long,
    private val notaryName: CordaX500Name,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val token: FungibleToken =
            (quantity of mint).issuedBy(ourIdentity).heldBy(ourIdentity)

        return subFlow(IssueTokensFlow(token))
        // TODO make sure to use specific Corda Notary
//        val notary = serviceHub.networkMapCache.notaryIdentities
//            .first { it.name == notaryName }
//        val amount = (quantity of SIMPLE).issuedBy(ourIdentity)
//        val token: FungibleToken = FungibleToken(amount = amount, holder = ourIdentity, tokenTypeJarHash = null)
//        val txb = TransactionBuilder(notary)
//        addIssueTokens(txb, listOf(token))
//        val ptx = serviceHub.signInitialTransaction(txb)
//        return subFlow(FinalityFlow(ptx, emptyList()))
    }
}

@InitiatedBy(IssueSimpleTokenFlow::class)
class IssueSimpleTokenResponder(
    private val session: FlowSession,
) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(IssueTokensFlowHandler(session))
    }
}

@StartableByRPC
class QuerySimpleTokensFlow(
    private val issuer: Party,
    private val tokenType: TokenType,
) : FlowLogic<List<StateAndRef<FungibleToken>>>() {
    @Suspendable
    override fun call(): List<StateAndRef<FungibleToken>> {
        val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
        val all = serviceHub.vaultService.queryBy(FungibleToken::class.java, criteria).states

        val result =
            all.filter { sar ->
                val ft = sar.state.data

                val holderWellKnown = serviceHub.identityService.wellKnownPartyFromAnonymous(ft.holder)
                val issuerWellKnown = serviceHub.identityService.wellKnownPartyFromAnonymous(ft.amount.token.issuer)

                val isSimple = ft.amount.token.tokenType == tokenType
                val holderOk = holderWellKnown == ourIdentity
                val issuerOk = issuerWellKnown == issuer

                isSimple && holderOk && issuerOk
            }
        return result
    }
}
