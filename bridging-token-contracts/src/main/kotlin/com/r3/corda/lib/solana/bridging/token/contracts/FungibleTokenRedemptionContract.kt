package com.r3.corda.lib.solana.bridging.token.contracts

import com.r3.corda.lib.solana.bridging.token.states.RedeemedFungibleTokenProxy
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.transactions.LedgerTransaction
import net.corda.solana.sdk.instruction.SolanaInstruction
import net.corda.solana.sdk.internal.Token2022

class FungibleTokenRedemptionContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val bridgingCommands = tx.commandsOfType<RedeemCommand>()

        require(bridgingCommands.size == 1) { "Redemption transactions must have single redeeming command" }

        when (bridgingCommands.single().value) {
            is RedeemCommand.UnlockToken -> verifyIssueRedeemState(tx)
            is RedeemCommand.BurnOnSolana -> verifyBurnOnSolana(tx)
        }
    }

    private fun verifyBurnOnSolana(tx: LedgerTransaction) {
        val redeemState = tx.inputsOfType<RedeemedFungibleTokenProxy>().singleOrNull()
        require(redeemState != null) { "Redemption requires exactly one input RedeemedFungibleTokenProxy" }

        // Check that there is exactly one solana instruction
        val instruction = tx.notaryInstructions.singleOrNull() as? SolanaInstruction
        require(instruction != null) { "Exactly one Solana burn instruction required" }

        val expectedInstruction = Token2022.burn(
            redeemState.mint,
            redeemState.burnAccount,
            redeemState.redemptionWallet,
            redeemState.amount
        )

        require(instruction == expectedInstruction) {
            "The instruction in the transaction does not match the calculated instruction:\n" +
                "transaction: $instruction\n" +
                "expected:    $expectedInstruction"
        }
    }

    private fun verifyIssueRedeemState(tx: LedgerTransaction) {
        val redeemState = tx.outputsOfType<RedeemedFungibleTokenProxy>().singleOrNull()
        require(redeemState != null) { "Redemption requires exactly one output state for a RedeemedFungibleTokenProxy" }
    }

    sealed interface RedeemCommand : CommandData {
        class UnlockToken : RedeemCommand

        class BurnOnSolana : RedeemCommand
    }

    companion object {
        const val CONTRACT_ID = "com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract"
    }
}
