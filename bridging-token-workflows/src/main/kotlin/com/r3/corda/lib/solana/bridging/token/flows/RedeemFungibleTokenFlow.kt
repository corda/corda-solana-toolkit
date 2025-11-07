package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract
import com.r3.corda.lib.solana.bridging.token.states.RedeemedFungibleTokenProxy
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.MoveNotaryFlow
import net.corda.core.flows.StartableByService
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.toNonEmptySet
import net.corda.solana.sdk.instruction.Pubkey
import net.corda.solana.sdk.internal.Token2022
import java.math.BigDecimal

/**
 * Flows bridges a fungible token redemption to Solana token burn.
 *
 * @param burnAccount the Solana account where the tokens will be burnt
 * @param originalHolder the owner of the token before it was moved to Bridging Authority
 * @param tokenTypeId the identifier of the token being redeemed
 * @param amount the amount of tokens to redeem
 * @param solanaNotary notary to perform bridging
 * @param lockingHolder the confidential identity that holds the fungible tokens
 */
@StartableByService
@InitiatingFlow
class RedeemFungibleTokenFlow(
    val burnAccount: Pubkey,
    val originalHolder: Party,
    val tokenTypeId: String,
    val amount: Long,
    val solanaNotary: Party,
    val lockingHolder: Party,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val bridgingService = serviceHub.cordaService(BridgingService::class.java)
        val bridgingCoordinates = bridgingService.configHandler.getBridgingCoordinates(tokenTypeId, originalHolder)
        val token = bridgingService.findTokenTypeOfFungibleTokenBy(tokenTypeId)
        val moveAmount = Amount.fromDecimal(BigDecimal.valueOf(amount).multiply(token.displayTokenSize), token)
        // Move the token from ourIdentity (implied BridgeAuthority) to the lock holder (confidential identity).
        // Also, create a RedeemState that will be later used to mint the tokens on Solana
        val unlockTx =
            subFlow(
                MoveAndUnlockFungibleTokenFlow(
                    bridgingCoordinates,
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
        val redeemTxState = notaryChangeTx.state

        // Burn on Solana
        val transactionBuilder = TransactionBuilder(solanaNotary)
        val instruction = with(redeemTxState.data) {
            Token2022.burn(
                mint = mint,
                owner = bridgeRedemptionWallet,
                source = burnAccount,
                amount = amount,
            )
        }

        transactionBuilder.addNotaryInstruction(instruction)
        transactionBuilder.addCommand(
            FungibleTokenRedemptionContract.RedeemingCommand.BurnOnSolana(),
            listOf(ourIdentity.owningKey),
        )
        // We consume the RedeemState to mark the token is burnt on Solana
        transactionBuilder.addInputState(StateAndRef(redeemTxState, notaryChangeTx.ref))

        // Verify
        transactionBuilder.verify(serviceHub)
        val bridgeAuthoritySignedTransaction = serviceHub.signInitialTransaction(transactionBuilder)

        subFlow(FinalityFlow(bridgeAuthoritySignedTransaction, emptyList()))

        // Return the moved tokens to the original holder

        // First, release the soft lock on the moved tokens
        serviceHub.vaultService
            .softLockRelease(
                redeemTxState.data.lockId,
                unlockLedgerTx
                    .outRefsOfType<FungibleToken>()
                    .map { it.ref }
                    .toNonEmptySet()
            )

        return subFlow(
            MoveFungibleTokens(moveAmount, originalHolder)
        )
    }
}
