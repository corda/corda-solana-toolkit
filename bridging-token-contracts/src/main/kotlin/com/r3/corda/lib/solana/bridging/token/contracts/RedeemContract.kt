package com.r3.corda.lib.solana.bridging.token.contracts

import com.r3.corda.lib.solana.bridging.token.states.RedeemState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.transactions.LedgerTransaction
import net.corda.solana.sdk.instruction.SolanaInstruction
import net.corda.solana.sdk.internal.Token2022

@Suppress("MaxLineLength", "ArgumentListWrapping", "FunctionLiteral", "Wrapping", "FunctionSignature")
class RedeemContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val bridgingCommands = tx.commandsOfType<RedeemCommand>()

        require(bridgingCommands.size == 1) { "Bridging transactions must have single bridging command" }

        when (val bridgingCommand = bridgingCommands.single().value) {
            is RedeemCommand.IssueRedeemState -> verifyIssueRedeemState(tx)
            is RedeemCommand.BurnOnSolana -> verifyBurnOnSolana(tx, bridgingCommand)
        }
    }

    private fun verifyBurnOnSolana(tx: LedgerTransaction, redeemCommand: RedeemCommand.BurnOnSolana) {
        val redeemState = tx.inputsOfType<RedeemState>().singleOrNull()
        require(redeemState != null) { "Redemption requires exactly one input RedeemState" }

        // Check that there is exactly one solana instruction
        val instruction = tx.notaryInstructions.singleOrNull() as? SolanaInstruction
        require(instruction != null) { "Exactly one Solana burn instruction required" }

        val expectedInstruction = Token2022.burn(
            redeemState.mint,
            redeemState.burnSource,
            redeemState.bridgeRedemptionWallet,
            redeemState.amount
        )

        require(instruction == expectedInstruction) {
            "The instruction in the transaction does not match the calculated instruction:\n" +
                "transaction: $instruction\n" +
                "expected:    $expectedInstruction"
        }
    }

    private fun verifyIssueRedeemState(tx: LedgerTransaction) {
        val redeemState = tx.outputsOfType<RedeemState>().singleOrNull()
        require(redeemState != null) { "Redemption requires exactly one output state for a RedeemState" }
    }

    sealed interface RedeemCommand : CommandData {
        class IssueRedeemState : RedeemCommand
        class BurnOnSolana : RedeemCommand
    }
}
