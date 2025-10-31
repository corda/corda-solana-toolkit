package com.r3.corda.lib.solana.bridging.token.testing

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.EvolvableTokenContract
import com.r3.corda.lib.tokens.contracts.states.EvolvableTokenType
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import com.r3.corda.lib.tokens.contracts.types.TokenPointer
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.workflows.flows.rpc.CreateEvolvableTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens
import net.corda.core.contracts.Amount
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.Contract
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.schemas.StatePersistable
import net.corda.core.transactions.LedgerTransaction
import java.util.UUID

class TestEvolvableTokenContract : EvolvableTokenContract(), Contract {
    override fun additionalCreateChecks(tx: LedgerTransaction) {
        requireThat {
        }
    }

    override fun additionalUpdateChecks(tx: LedgerTransaction) {
        requireThat {
        }
    }
}

@BelongsToContract(TestEvolvableTokenContract::class)
data class StockTokenType(
    val ticker: String,
    override val maintainers: List<Party>,
    override val fractionDigits: Int = 0,
    override val linearId: UniqueIdentifier,
) : EvolvableTokenType(), StatePersistable {
    override val participants: List<AbstractParty> get() = maintainers
}

@StartableByRPC
@InitiatingFlow
class IssueEvolvableTokenTypeFlow(
    private val ticker: String,
    private val tokenTypeIdentifier: UUID,
    private val quantity: Long,
    private val notaryName: CordaX500Name,
) : FlowLogic<FungibleToken>() {
    @Suspendable
    override fun call(): FungibleToken {
        val stock = StockTokenType(
            ticker = ticker,
            maintainers = listOf(ourIdentity),
            fractionDigits = 0,
            linearId = UniqueIdentifier(id = tokenTypeIdentifier)
        )
        val notary = serviceHub.networkMapCache.notaryIdentities.first { it.name == notaryName }
        subFlow(CreateEvolvableTokens(listOf(TransactionState(stock, notary = notary))))
        val pointer: TokenPointer<StockTokenType> = stock.toPointer<StockTokenType>()
        val issued = pointer issuedBy ourIdentity
        val amount: Amount<IssuedTokenType> = Amount(quantity, issued)
        val token = FungibleToken(amount = amount, holder = ourIdentity)
        subFlow(IssueTokens(listOf(token)))
        return token
    }
}
