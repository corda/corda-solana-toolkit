package com.r3.corda.lib.solana.bridging.token.contracts

import com.r3.corda.lib.solana.bridging.token.states.RedeemedFungibleTokenProxy
import com.r3.corda.lib.tokens.contracts.commands.MoveTokenCommand
import com.r3.corda.lib.tokens.contracts.commands.TokenCommand
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
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
            is RedeemCommand.UnlockToken -> verifyUnlockToken(tx, redeemCommand)
            is RedeemCommand.BurnOnSolana -> verifyBurnOnSolana(tx)
        }
    }

    private fun verifyUnlockToken(tx: LedgerTransaction, redeemCommand: RedeemCommand.UnlockToken) {
        val redeemState = tx.outputsOfType<RedeemedFungibleTokenProxy>().requireSingle {
            "Redemption requires exactly one output state for a RedeemedFungibleTokenProxy"
        }
        val outputFungibleState = tx.outputsOfType<FungibleToken>().requireSingle {
            "UnlockToken requires exactly one output FungibleToken state"
        }
        val inputFungibleState = tx.inputsOfType<FungibleToken>().requireSingle {
            "UnlockToken requires exactly one input FungibleToken state"
        }
        require(tx.commandsOfType<TokenCommand>().singleOrNull()?.value is MoveTokenCommand) {
            "UnlockToken must have a single token command (Move Token)"
        }
        require(redeemState.amount == outputFungibleState.amount.quantity) {
            "The amount in the RedeemState must match the amount in the FungibleToken state"
        }
        require(inputFungibleState.holder == redeemCommand.lockingIdentity) {
            "The holder of the input token must be the locking identity"
        }
        require(tx.commands.size == 2) {
            // Presence of individual commands had been verified till this point
            "UnlockToken transaction must only contain two commands"
        }

        val noSolanaInstructions = tx.notaryInstructions.none { it is SolanaInstruction }
        require(noSolanaInstructions) { "No Solana instructions allowed" }
    }

    private fun verifyBurnOnSolana(tx: LedgerTransaction) {
        val redeemState = tx.inputsOfType<RedeemedFungibleTokenProxy>().requireSingle {
            "Redemption requires exactly one input RedeemedFungibleTokenProxy"
        }

        require(tx.outputsOfType<RedeemedFungibleTokenProxy>().isEmpty()) {
            "BurnOnSolana transaction must not have any RedeemedFungibleTokenProxy outputs"
        }

        // Check that there is exactly one solana instruction
        val solanaInstruction = tx.notaryInstructionsOfType<SolanaInstruction>().requireSingle {
            "Exactly one Solana instruction required"
        }

        val expectedInstruction = Token2022.burn(
            redeemState.mint,
            redeemState.burnAccount,
            redeemState.redemptionWallet,
            redeemState.amount
        )

        require(solanaInstruction == expectedInstruction) {
            "The Solana instruction in the transaction not the expected burn instruction:\n" +
                "transaction: $solanaInstruction\n" +
                "expected:    $expectedInstruction"
        }

        require(tx.commands.size == 1) {
            "BurnOnSolana transaction must only contain a single command"
        }
    }

    sealed interface RedeemCommand : CommandData {
        data class UnlockToken(val lockingIdentity: AbstractParty) : RedeemCommand

        class BurnOnSolana : RedeemCommand
    }

    companion object {
        const val CONTRACT_ID = "com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract"
    }
}
