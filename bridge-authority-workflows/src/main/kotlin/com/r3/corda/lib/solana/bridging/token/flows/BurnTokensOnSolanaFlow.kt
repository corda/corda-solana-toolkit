package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract
import com.r3.corda.lib.solana.core.cordautils.Token2022
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

class BurnTokensOnSolanaFlow(
    private val redemptionCoordinates: RedemptionCoordinates,
    private val solanaNotary: Party,
    private val cordaAmount: Long,
    private val cordaAmountDecimals: Int,
    private val solanaAmount: Long,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val transactionBuilder = TransactionBuilder(solanaNotary)
        val instruction = with(redemptionCoordinates) {
            Token2022.burn(
                mint = mintAccount,
                owner = redemptionWalletAccount,
                source = redemptionTokenAccount,
                amount = solanaAmount,
            )
        }
        transactionBuilder.addNotaryInstruction(instruction)
        transactionBuilder.addCommand(
            FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana,
            listOf(ourIdentity.owningKey),
        )
        // We issue FungibleTokenBurnReceipt state to record burning of tokens on Solana
        val redeemReceiptState = redemptionCoordinates.toRedeemReceiptState(
            cordaQuantity = cordaAmount,
            coraDecimals = cordaAmountDecimals,
            bridgeAuthority = ourIdentity
        )
        transactionBuilder.addOutputState(redeemReceiptState)
        // Verify
        transactionBuilder.verify(serviceHub)
        return subFlow(FinalityFlow(serviceHub.signInitialTransaction(transactionBuilder), emptyList()))
    }
}
