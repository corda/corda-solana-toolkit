package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.states.FungibleTokenBurnReceipt
import com.r3.corda.lib.tokens.contracts.internal.schemas.PersistentFungibleToken
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import net.corda.core.contracts.Amount
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.MoveNotaryFlow
import net.corda.core.flows.StartableByService
import net.corda.core.identity.Party
import net.corda.core.node.services.vault.Builder.equal
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.toNonEmptySet
import net.corda.solana.sdk.instruction.Pubkey

/**
 * Flows bridges a fungible token redemption to Solana token burn.
 *
 * @param burnAccount the Solana account where the tokens will be burnt
 * @param redemptionHolder the Corda party to send the redeemed tokens to
 * @param tokenTypeId the identifier of the token being redeemed
 * @param amount the amount of tokens to redeem
 * @param solanaNotary notary to perform bridging
 * @param generalNotary notary to use for Corda-side fungible token movement to the redemption holder
 * @param lockingHolder the confidential identity that holds the fungible tokens
 */
@StartableByService
@InitiatingFlow
class RedeemFungibleTokenFlow(
    val burnAccount: Pubkey,
    val redemptionHolder: Party,
    val tokenTypeId: String,
    val amount: Long,
    val solanaNotary: Party,
    val generalNotary: Party,
    val lockingHolder: Party,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val bridgingService = serviceHub.cordaService(BridgingService::class.java)
        val redemptionCoordinates = bridgingService.getRedemptionCoordinates(tokenTypeId)
        val redeemStateAndRef = subFlow(
            BurnTokensOnSolanaFlow(
                redemptionCoordinates,
                solanaNotary,
                burnAccount,
                amount
            )
        ).toLedgerTransaction(serviceHub).outRefsOfType<FungibleTokenBurnReceipt>().single()
        val notaryChangeTx = subFlow(
            MoveNotaryFlow(listOf(redeemStateAndRef), generalNotary)
        ).single()

        // Unlock the fungible tokens from the locking holder
        val moveAmount = Amount(amount, findTokenTypeOfFungibleTokenBy(tokenTypeId))
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

        return subFlow(MoveFungibleTokens(moveAmount, redemptionHolder))
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
