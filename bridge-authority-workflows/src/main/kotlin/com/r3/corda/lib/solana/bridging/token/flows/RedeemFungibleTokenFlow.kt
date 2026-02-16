package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.states.FungibleTokenBurnReceipt
import com.r3.corda.lib.tokens.contracts.internal.schemas.PersistentFungibleToken
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount
import net.corda.core.contracts.Amount
import net.corda.core.crypto.toStringShort
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.MoveNotaryFlow
import net.corda.core.flows.StartableByService
import net.corda.core.identity.Party
import net.corda.core.node.services.vault.Builder.equal
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.toNonEmptySet

/**
 * Flows bridges a fungible token redemption to Solana token burn.
 *
 * @param redemptionCoordinates the Solana redemption coordinates
 * @param redemptionHolder the Corda party to send the redeemed tokens to
 * @param amount the amount of tokens to redeem
 * @param solanaNotary notary to perform bridging
 * @param generalNotary notary to use for Corda-side fungible token movement to the redemption holder
 * @param lockingHolder the confidential identity that holds the fungible tokens
 */
@StartableByService
@InitiatingFlow
class RedeemFungibleTokenFlow(
    val redemptionCoordinates: RedemptionCoordinates,
    val redemptionHolder: Party,
    val amount: Long,
    val solanaNotary: Party,
    val generalNotary: Party,
    val lockingHolder: Party,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val tokenType = findTokenTypeOfFungibleTokenBy(redemptionCoordinates.tokenId)
        val conversionMultiplier : Long = 1 // TODO conversion

        val cordaAmount = truncateByFactor(amount, conversionMultiplier)
        val newSolanaAmount = zeroOutFractionDigits(amount, conversionMultiplier)

        val redeemStateAndRef = subFlow(
            BurnTokensOnSolanaFlow(
                redemptionCoordinates,
                solanaNotary,
                cordaAmount,
                tokenType.fractionDigits,
                newSolanaAmount
            )
        ).toLedgerTransaction(serviceHub).outRefsOfType<FungibleTokenBurnReceipt>().single()
        val notaryChangeTx = subFlow(
            MoveNotaryFlow(listOf(redeemStateAndRef), generalNotary)
        ).single()

        // Unlock the fungible tokens from the locking holder
        val moveAmount = Amount(cordaAmount, tokenType)
        val lockCapture = FungibleTokenLockCapture()
        val unlockLedgerTx = subFlow(
            MoveAndUnlockFungibleTokenFlow(
                notaryChangeTx,
                bridgeAuthority = ourIdentity,
                lockingHolder,
                moveAmount,
                lockCapture
            )
        ).toLedgerTransaction(serviceHub)

        // First, release the soft lock on the moved tokens
        serviceHub.vaultService
            .softLockRelease(
                checkNotNull(lockCapture.lockId) { "Lock ID has not been captured during the unlock fungible tokens" },
                unlockLedgerTx.outRefsOfType<FungibleToken>().map { it.ref }.toNonEmptySet()
            )
        return subFlow(
            MoveFungibleTokens(
                partyAndAmount = PartyAndAmount(redemptionHolder, moveAmount),
                queryCriteria = QueryCriteria.VaultCustomQueryCriteria(
                    builder {
                        PersistentFungibleToken::owningKeyHash.equal(ourIdentity.owningKey.toStringShort())
                    }
                )
            )
        )
    }

    private fun findTokenTypeOfFungibleTokenBy(tokenTypeIdentifier: String): TokenType {
        val predicate = PersistentFungibleToken::tokenIdentifier.equal(tokenTypeIdentifier)
        val criteria = QueryCriteria.VaultCustomQueryCriteria(predicate)
        val matches = serviceHub.vaultService
            .queryBy(
                contractStateType = FungibleToken::class.java,
                criteria = criteria,
                paging = PageSpecification(pageSize = 1, pageNumber = 1)
            ).states

        val matched = requireNotNull(matches.firstOrNull()) {
            "No fungible token with type identifier '$tokenTypeIdentifier' found in the vault"
        }
        return matched.state.data.amount.token
    }
}
