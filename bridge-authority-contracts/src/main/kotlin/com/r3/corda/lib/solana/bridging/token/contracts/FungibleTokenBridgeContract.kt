package com.r3.corda.lib.solana.bridging.token.contracts

import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
import com.r3.corda.lib.solana.core.cordautils.Token2022
import com.r3.corda.lib.tokens.contracts.commands.MoveTokenCommand
import com.r3.corda.lib.tokens.contracts.commands.TokenCommand
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.solana.SolanaInstruction
import net.corda.core.transactions.LedgerTransaction

/**
 * Contract that governs bridging of fungible token states (from the Corda Token SDK) to Solana.
 *
 * The contract is *exhaustive* over a transaction’s non-reference states: apart from [BridgedFungibleTokenProxy],
 * every input and output must be a Tokens SDK **fungible** token state.
 * Any other state types/contracts are not permitted in the same transaction.
 */
class FungibleTokenBridgeContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val bridgeCommands = tx.commandsOfType<BridgeCommand>()
        val bridgeCommand = bridgeCommands.requireSingle {
            "Bridging transactions must have a single bridge command"
        }
        when (bridgeCommand.value) {
            is BridgeCommand.LockToken -> verifyLockToken(tx)
            is BridgeCommand.MintToSolana -> verifyMintToSolana(tx)
        }
    }

    private fun verifyLockToken(tx: LedgerTransaction) {
        require(tx.inputs.size == 1) { "Lock transaction must have exactly one input state" }
        val inputToken = tx.inputsOfType<FungibleToken>().requireSingle {
            "Lock transaction must have exactly one FungibleState as input state"
        }
        // The correctness of the input and output tokens is verified by Token SDK MoveTokenCommand
        require(tx.commandsOfType<TokenCommand>().singleOrNull()?.value is MoveTokenCommand) {
            "Lock must have a single token command (Move Token) to lock token with the locking identity"
        }

        val outputToken = tx.outputsOfType<FungibleToken>().requireSingle {
            "Lock transaction must have exactly one FungibleToken as output"
        }
        val tokenProxy = tx.outputsOfType<BridgedFungibleTokenProxy>().requireSingle {
            "Lock transaction must have exactly one BridgedFungibleTokenProxy as output"
        }

        require(inputToken.holder == tokenProxy.bridgeAuthority) {
            "The holder of the locked token must match the bridge authority in the token proxy"
        }

        require(inputToken.holder != outputToken.holder) {
            "The token holder must change when locking the token"
        }

        require(outputToken.amount.toTokenAmount() == tokenProxy.cordaAmount) {
            "BridgedFungibleTokenProxy must have the same amount as the locked token"
        }

        require(tx.commands.size == 2) {
            // Presence of individual commands had been verified till this point
            "Lock transaction must only contain commands LockToken and token command (Move Token)"
        }

        val noSolanaInstructions = tx.notaryInstructions.none { it is SolanaInstruction }
        require(noSolanaInstructions) { "No Solana instructions allowed" }

        // TODO verify the locked token data matches as well, such as the tokenId and original owner
        //  this will come with redemption code
    }

    private fun verifyMintToSolana(tx: LedgerTransaction) {
        val bridgedFungibleTokenProxy = tx.inputsOfType<BridgedFungibleTokenProxy>().requireSingle {
            "Bridge to Solana transaction must have exactly one BridgedFungibleTokenProxy as input"
        }
        require(tx.outputsOfType<BridgedFungibleTokenProxy>().isEmpty()) {
            "Bridge to Solana transaction must not have any BridgedFungibleTokenProxy outputs"
        }
        val solanaInstruction = tx.notaryInstructionsOfType<SolanaInstruction>().requireSingle {
            "Exactly one Solana instruction required"
        }
        val expectedMintInstruction = Token2022.mintTo(
            bridgedFungibleTokenProxy.mintAccount,
            bridgedFungibleTokenProxy.bridgeTokenAccount,
            bridgedFungibleTokenProxy.mintAuthority,
            bridgedFungibleTokenProxy.solanaAmount.quantity,
        )
        require(solanaInstruction == expectedMintInstruction) {
            "Solana instruction in the transaction not the expected mint instruction:\n" +
                "transaction: $solanaInstruction\n" +
                "expected:    $expectedMintInstruction"
        }

        require(tx.commands.size == 1) {
            "Bridging transaction must only contain a single command"
        }
    }

    private inline fun <T> List<T>.requireSingle(errorMessage: () -> Any): T {
        return requireNotNull(singleOrNull(), errorMessage)
    }

    /**
     * Commands for the Corda to Solana bridging flow:
     * The bridging lifecycle is:
     * 1) [LockToken] — lock (escrow) the Corda-side fungible tokens under the bridge’s control.
     * 2) [MintToSolana] — (after evidence/confirmation) mint the equivalent SPL amount on Solana.
     */
    sealed interface BridgeCommand : CommandData {
        /**
         * Locks a Corda-side fungible token balance so it cannot be spent while the
         * equivalent amount is minted on Solana.
         */
        object LockToken : BridgeCommand

        /**
         * Mints the bridged amount on Solana for the designated destination.
         */
        object MintToSolana : BridgeCommand
    }

    companion object {
        const val CONTRACT_ID = "com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenBridgeContract"
    }
}
