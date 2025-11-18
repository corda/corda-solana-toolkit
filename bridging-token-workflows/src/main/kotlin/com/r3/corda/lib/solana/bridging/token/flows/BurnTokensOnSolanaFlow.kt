package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.solana.sdk.instruction.Pubkey
import net.corda.solana.sdk.internal.Token2022

class BurnTokensOnSolanaFlow(
    private val redemptionCoordinates: RedemptionCoordinates,
    private val solanaNotary: Party,
    private val burnAccount: Pubkey,
    private val amount: Long,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val transactionBuilder = TransactionBuilder(solanaNotary)
        val instruction = with(redemptionCoordinates) {
            Token2022.burn(
                mint = mint,
                owner = redemptionWallet,
                source = burnAccount,
                amount = amount,
            )
        }
        transactionBuilder.addNotaryInstruction(instruction)
        transactionBuilder.addCommand(
            FungibleTokenRedemptionContract.RedeemCommand.BurnOnSolana,
            listOf(ourIdentity.owningKey),
        )
        // We issue FungibleTokenBurnReceipt state to record burning of tokens on Solana
        val redeemState = redemptionCoordinates.toRedeemState(
            burnAccount = burnAccount,
            amount = amount,
            bridgeAuthority = ourIdentity
        )
        transactionBuilder.addOutputState(redeemState)
        // Verify
        transactionBuilder.verify(serviceHub)
        return subFlow(FinalityFlow(serviceHub.signInitialTransaction(transactionBuilder), emptyList()))
    }
}
