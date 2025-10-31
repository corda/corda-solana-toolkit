package com.r3.corda.lib.solana.bridging.token.contracts

import com.r3.corda.lib.solana.bridging.token.states.MintState
import com.r3.corda.lib.tokens.contracts.commands.MoveTokenCommand
import com.r3.corda.lib.tokens.contracts.commands.TokenCommand
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.solana.sdk.instruction.SolanaInstruction
import net.corda.solana.sdk.internal.Token2022

/**
 * Contract that governs bridging of fungible token states (from the Corda Token SDK) to Solana.
 *
 * The contract is *exhaustive* over a transaction’s non-reference states: apart from [MintState],
 * every input and output must be a Tokens SDK **fungible** token state.
 * Any other state types/contracts are not permitted in the same transaction.
 */
class MintContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val mintCommands = tx.commandsOfType<MintCommand>()
        val mintCommand = mintCommands.requireSingle {
            "Bridging transactions must have a single bridging command"
        }
        when (val cmdData = mintCommand.value) {
            is MintCommand.LockToken -> verifyLockToken(tx, cmdData)
            is MintCommand.MintToSolana -> verifyMintToSolana(tx)
        }
    }

    private fun verifyLockToken(tx: LedgerTransaction, lockingCommand: MintCommand.LockToken) {
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
        val tokenProxy = tx.outputsOfType<MintState>().requireSingle {
            "Lock transaction must have exactly one BridgedFungibleTokenProxy as output"
        }

        require(inputToken.holder == lockingCommand.bridgeAuthority) {
            "The holder of the locked token must been the bridge authority"
        }
        require(outputToken.holder == lockingCommand.lockingIdentity) {
            "The holder of the locked token must be the locking identity"
        }
        require(lockingCommand.bridgeAuthority == tokenProxy.bridgeAuthority) {
            "Bridge authority must be a participant"
        }

        require(outputToken.amount.quantity == tokenProxy.amount) {
            "BridgedFungibleTokenProxy must have the same amount as the locked token"
        }
        require(!tokenProxy.minted) { "BridgedFungibleTokenProxy must not be marked as minted when issuing" }

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
        val mintState = tx.inputsOfType<MintState>().requireSingle {
            "Bridging transaction must have exactly one MintState as input"
        }

        val solanaInstruction = tx.notaryInstructionsOfType<SolanaInstruction>().requireSingle {
            "Exactly one Solana instruction required"
        }
        val expectedMintInstruction = Token2022.mintTo(
            mintState.mint,
            mintState.mintDestination,
            mintState.mintAuthority,
            mintState.amount,
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
    sealed interface MintCommand : CommandData {
        /**
         * Locks a Corda-side fungible token balance so it cannot be spent while the
         * equivalent amount is minted on Solana.
         *
         * @property bridgeAuthority The well-known Corda [Party] operating the bridge,
         *   that owns a proxy of the fungible token [MintState] to be used for minting on Solana.
         * @property lockingIdentity The Corda identity (confidential) that owns
         *   the token being locked prior to minting on Solana.
         *
         * @throws IllegalArgumentException if [bridgeAuthority] and [lockingIdentity] refer to the same party.
         */
        data class LockToken(
            val bridgeAuthority: Party,
            val lockingIdentity: AbstractParty,
        ) : MintCommand {
            init {
                require(bridgeAuthority != lockingIdentity) {
                    "Locking identity must be different from the bridge authority"
                }
            }
        }

        /**
         * Mints the bridged amount on Solana for the designated destination.
         */
        object MintToSolana : MintCommand
    }

    companion object {
        val contractId = this::class.java.enclosingClass.canonicalName
    }
}
