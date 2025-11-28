package com.r3.corda.lib.solana.bridging.token.contracts

import com.r3.corda.lib.solana.bridging.token.states.FungibleTokenBurnReceipt
import com.r3.corda.lib.tokens.contracts.commands.MoveTokenCommand
import com.r3.corda.lib.tokens.contracts.commands.TokenCommand
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrNull
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.identity.AbstractParty
import net.corda.core.transactions.LedgerTransaction
import net.corda.solana.sdk.instruction.SolanaInstruction
import net.corda.solana.sdk.internal.Token2022

class FungibleTokenRedemptionContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val redeemCommands = tx.commandsOfType<RedeemCommand>()

        require(redeemCommands.size == 1) { "Redeem transactions must have single redeem command" }

        when (val redeemCommand = redeemCommands.single().value) {
            is RedeemCommand.BurnOnSolana -> verifyBurnOnSolana(tx)
            is RedeemCommand.UnlockToken -> verifyUnlockToken(tx, redeemCommand)
        }
    }

    /*
     * We do not verify where the burn instruction comes from as this does not matter.
     * The burn receipt that captures this event and is used later in the redemption flow.
     */
    private fun verifyUnlockToken(tx: LedgerTransaction, redeemCommand: RedeemCommand.UnlockToken) {
        val burnReceiptState = tx.inputsOfType<FungibleTokenBurnReceipt>().requireSingle {
            "Token burning transaction requires exactly one input state for a FungibleTokenBurnReceipt"
        }
        val inputFungibleStates = tx.inputsOfType<FungibleToken>()
        require(inputFungibleStates.isNotEmpty()) {
            "UnlockToken requires at least one input FungibleToken state"
        }
        require(tx.commandsOfType<TokenCommand>().singleOrNull()?.value is MoveTokenCommand) {
            "UnlockToken must have a single token command (Move Token)"
        }
        val inputFungibleTokenHolder = requireNotNull(inputFungibleStates.map { it.holder }.toSet().singleOrNull()) {
            "All input FungibleToken states must have the same holder"
        }
        require(inputFungibleTokenHolder == redeemCommand.lockingIdentity) {
            "Only the identity that locked the fungible tokens may unlock it"
        }
        require(tx.outputsOfType<FungibleTokenBurnReceipt>().isEmpty()) {
            "Transaction cannot have outputs of type FungibleTokenBurnReceipt"
        }
        val outputFungibleStates = tx.outputsOfType<FungibleToken>()
        require(outputFungibleStates.isNotEmpty()) {
            "UnlockToken requires at least one output FungibleToken state"
        }
        // There might be a change in that needs to be returned to the locking identity therefore we can have a set
        val outputFungibleTokenHolders = outputFungibleStates.map { it.holder }.toSet()
        require(outputFungibleTokenHolders.isNotEmpty()) {
            "Output FungibleToken states must have at least one holder"
        }
        require(burnReceiptState.bridgeAuthority in outputFungibleTokenHolders) {
            "One of the output FungibleToken states must have the bridge authority as the holder"
        }
        val redeemedAmount = requireNotNull(
            outputFungibleStates.filter { it.holder == burnReceiptState.bridgeAuthority }.sumTokenStatesOrNull()
        ) {
            "The output FungibleToken states must contain an amount for the same token type as in the burn receipt"
        }
        require(burnReceiptState.amount == redeemedAmount.quantity) {
            "The amount in the FungibleTokenBurnReceipt must match the sum FungibleToken amounts"
        }
        require(tx.commands.size == 2) {
            // Presence of individual commands had been verified till this point
            "UnlockToken transaction must only contain two commands"
        }

        val noSolanaInstructions = tx.notaryInstructions.none { it is SolanaInstruction }
        require(noSolanaInstructions) { "No Solana instructions allowed" }
    }

    private fun verifyBurnOnSolana(tx: LedgerTransaction) {
        val burnReceiptState = tx.outputsOfType<FungibleTokenBurnReceipt>().requireSingle {
            "Token burning transaction requires exactly one output FungibleTokenBurnReceipt"
        }
        require(tx.commands.size == 1) {
            "BurnOnSolana transaction must only contain a single command"
        }
        require(tx.commands.single().signers.contains(burnReceiptState.bridgeAuthority.owningKey)) {
            "The bridge authority must sign the BurnOnSolana transaction"
        }
        require(tx.inputsOfType<FungibleTokenBurnReceipt>().isEmpty()) {
            "BurnOnSolana transaction must not have any FungibleTokenBurnReceipt inputs"
        }
        val solanaInstruction = tx.notaryInstructionsOfType<SolanaInstruction>().requireSingle {
            "Exactly one Solana instruction required"
        }
        val expectedInstruction = Token2022.burn(
            burnReceiptState.mintAccount,
            burnReceiptState.redemptionTokenAccount,
            burnReceiptState.redemptionWalletAccount,
            burnReceiptState.amount
        )
        require(solanaInstruction == expectedInstruction) {
            "The Solana instruction in the transaction not the expected burn instruction:\n" +
                "transaction: $solanaInstruction\n" +
                "expected:    $expectedInstruction"
        }
    }

    sealed interface RedeemCommand : CommandData {
        data class UnlockToken(val lockingIdentity: AbstractParty) : RedeemCommand

        object BurnOnSolana : RedeemCommand
    }

    companion object {
        const val CONTRACT_ID = "com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract"
    }
}
