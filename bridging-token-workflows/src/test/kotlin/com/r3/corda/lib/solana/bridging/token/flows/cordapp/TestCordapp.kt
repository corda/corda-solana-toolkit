package com.r3.corda.lib.solana.bridging.token.flows.cordapp

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
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction

object SIMPLE : TokenType("SIMPLE", 0)
object SIMPLE_2 : TokenType("SIMPLE_2", 0)

@InitiatingFlow
@StartableByRPC
class IssueSimpleTokenFlow(
    private val quantity: Long
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val token: FungibleToken =
            (quantity of SIMPLE).issuedBy(ourIdentity).heldBy(ourIdentity)

        return subFlow(IssueTokensFlow(token))
    }
}


@InitiatingFlow
@StartableByRPC
class IssueSimpleToken2Flow(
    private val quantity: Long
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val token: FungibleToken =
            (quantity of SIMPLE_2).issuedBy(ourIdentity).heldBy(ourIdentity)

        return subFlow(IssueTokensFlow(token))
    }
}

@InitiatedBy(IssueSimpleTokenFlow::class)
class IssueSimpleTokenResponder(private val session: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(IssueTokensFlowHandler(session))
    }
}

@StartableByRPC
class QuerySimpleTokensFlow(
    private val issuer: Party? = null,
    private val tokenType: TokenType = SIMPLE
) : FlowLogic<List<StateAndRef<FungibleToken>>>() {
    @Suspendable
    override fun call(): List<StateAndRef<FungibleToken>> {
        val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
        val all = serviceHub.vaultService.queryBy(FungibleToken::class.java, criteria).states

        return all.filter { sar ->
            val ft = sar.state.data

            val holderWellKnown = serviceHub.identityService.wellKnownPartyFromAnonymous(ft.holder)
            val issuerWellKnown = serviceHub.identityService.wellKnownPartyFromAnonymous(ft.amount.token.issuer)

            val isSimple = ft.amount.token.tokenType == tokenType
            val holderOk = holderWellKnown == ourIdentity
            val issuerOk = (issuer == null) || (issuerWellKnown == issuer)

            isSimple && holderOk && issuerOk
        }
    }
}