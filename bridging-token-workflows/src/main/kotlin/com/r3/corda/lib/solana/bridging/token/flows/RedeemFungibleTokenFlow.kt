package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract
import com.r3.corda.lib.solana.bridging.token.states.RedeemedFungibleTokenProxy
import com.r3.corda.lib.tokens.contracts.internal.schemas.PersistentFungibleToken
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.MoveNotaryFlow
import net.corda.core.flows.StartableByService
import net.corda.core.identity.Party
import net.corda.core.node.services.vault.Builder.equal
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.toNonEmptySet
import net.corda.solana.sdk.instruction.Pubkey
import net.corda.solana.sdk.internal.Token2022

/**
 * Flows bridges a fungible token redemption to Solana token burn.
 *
 * @param burnAccount the Solana account where the tokens will be burnt
 * @param redemptionHolder the Corda party to send the redeemed tokens to
 * @param tokenTypeId the identifier of the token being redeemed
 * @param amount the amount of tokens to redeem
 * @param solanaNotary notary to perform bridging
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
    val lockingHolder: Party,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val bridgingService = serviceHub.cordaService(BridgingService::class.java)
        val redemptionCoordinates = bridgingService.getRedemptionCoordinates(tokenTypeId)
        val token = findTokenTypeOfFungibleTokenBy(tokenTypeId)
        val moveAmount = Amount(amount, token)
        // Move the token from ourIdentity (implied BridgeAuthority) to the lock holder (confidential identity).
        // Also, create a RedeemState that will be later used to mint the tokens on Solana
        val unlockTx =
            subFlow(
                MoveAndUnlockFungibleTokenFlow(
                    redemptionCoordinates,
                    ourIdentity,
                    lockingHolder,
                    moveAmount,
                    burnAccount
                ),
            )

        // Change notary to Solana notary
        val unlockLedgerTx = unlockTx.toLedgerTransaction(serviceHub)
        val redeemStateAndRef = unlockLedgerTx.outRefsOfType<RedeemedFungibleTokenProxy>().single()
        val notaryChangeTx = subFlow(
            MoveNotaryFlow(listOf(redeemStateAndRef), solanaNotary)
        ).single()

        val bridgeAuthoritySignedTransaction = createBurnTransaction(notaryChangeTx)

        subFlow(FinalityFlow(bridgeAuthoritySignedTransaction, emptyList()))

        // First, release the soft lock on the moved tokens
        serviceHub.vaultService
            .softLockRelease(
                notaryChangeTx.state.data.lockId,
                unlockLedgerTx
                    .outRefsOfType<FungibleToken>()
                    .map { it.ref }
                    .toNonEmptySet()
            )

        return subFlow(MoveFungibleTokens(moveAmount, redemptionHolder))
    }

    private fun createBurnTransaction(redeemStateAndRef: StateAndRef<RedeemedFungibleTokenProxy>): SignedTransaction {
        val transactionBuilder = TransactionBuilder(solanaNotary)
        val instruction = with(redeemStateAndRef.state.data) {
            Token2022.burn(
                mint = mint,
                owner = redemptionWallet,
                source = burnAccount,
                amount = amount,
            )
        }
        transactionBuilder.addNotaryInstruction(instruction)
        transactionBuilder.addCommand(
            FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana(),
            listOf(ourIdentity.owningKey),
        )
        // We consume the RedeemState to mark the token is burnt on Solana
        transactionBuilder.addInputState(redeemStateAndRef)

        // Verify
        transactionBuilder.verify(serviceHub)
        return serviceHub.signInitialTransaction(transactionBuilder)
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
