package com.r3.corda.lib.solana.bridging.token.contracts

import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
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
 * Contract that governs bridging of fungible token states (from the Corda Tokens SDK) to Solana.
 *
 * The contract is *exhaustive* over a transaction’s non-reference states: apart from [BridgedFungibleTokenProxy],
 * every input and output must be a Tokens SDK **fungible** token state.
 * Any other state types/contracts are not permitted in the same transaction.
 */

class BridgingContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val bridgingCommands = tx.commandsOfType<BridgingCommand>()

        require(bridgingCommands.size == 1) { "Bridging transactions must have single bridging command" }

        when (val bridgingCommand = bridgingCommands.single().value) {
            is BridgingCommand.LockToken -> verifyLockToken(tx, bridgingCommand)
            is BridgingCommand.MintToSolana -> verifyMintToSolana(tx)
        }
    }

    private fun verifyLockToken(
        tx: LedgerTransaction,
        bridgingCommand: BridgingCommand.LockToken,
    ) {
        require(tx.inputs.size == 1) { "Lock transaction must have exactly one input state" }
        require(tx.inputsOfType<FungibleToken>().size == 1) {
            "Lock transaction must have exactly one FungibleState as input state"
        }

        val lockedToken = tx.outputsOfType<FungibleToken>().singleOrNull()
        val tokenProxy = tx.outputsOfType<BridgedFungibleTokenProxy>().singleOrNull()

        require(lockedToken != null) { "Lock transaction must have exactly one FungibleToken as output" }
        require(tokenProxy != null) { "Lock transaction must have exactly one BridgedAssetState as output" }
        require(bridgingCommand.bridgeAuthority in tokenProxy.participants) {
            "Bridge Authority must be a participant"
        }

        val tokenCommand = tx.commandsOfType<TokenCommand>().singleOrNull()
        require(tokenCommand != null && tokenCommand.value is MoveTokenCommand) {
            "Lock must have a single token command (Move Token) to lock token with the locking identity"
        }
        // the correctness of input and output FungibleToken is verified by TokenSdk contract for MoveTokenCommand

        require(lockedToken.holder != bridgingCommand.bridgeAuthority)

        val lockedSum = lockedToken.amount.quantity
        val forBridgingSum = tokenProxy.amount
        require(lockedToken.amount.quantity == forBridgingSum) {
            "Locked amount of $lockedSum must match amount to recorded in the proxy $forBridgingSum"
        }
        require(!tokenProxy.minted) { "Bridging asset must not be marked as minted when issuing" }

        require(tx.commands.size == 2) {
            // Presence of individual commands verified till this point
            "Lock transaction must only contain commands LockToken and token command (Move Token)"
        }

        // TODO verify the locked token data matches as well, such as the tokenId and original owner
        //  this will come with redemption code
    }

    private fun verifyMintToSolana(
        tx: LedgerTransaction,
    ) {
        val tokenProxy = tx.outputsOfType<BridgedFungibleTokenProxy>().singleOrNull()
        require(tokenProxy != null) {
            "Bridging transaction must have exactly one BridgedFungibleTokenProxy as output"
        }

        val mintCommand = tx.commandsOfType<BridgingCommand.MintToSolana>()
        require(mintCommand.size == 1) { "Bridging must have one mint command" }

        val instruction = tx.notaryInstructions.singleOrNull() as? SolanaInstruction
        require(instruction != null) { "Exactly one Solana mint instruction required" }

        val expectedInstruction = Token2022.mintTo(
            tokenProxy.mint,
            tokenProxy.mintDestination,
            tokenProxy.mintAuthority,
            tokenProxy.amount,
        )
        require(instruction == expectedInstruction) {
            "The instruction in the transaction does not match the sum or the bridging config:\n" +
                "transaction: $instruction\n" +
                "expected:    $expectedInstruction"
        }

        val originalBridgingAssetState = tx.inputsOfType<BridgedFungibleTokenProxy>().singleOrNull()
        require(originalBridgingAssetState != null) {
            "Bridging transaction must have exactly one BridgedFungibleTokenProxy as input"
        }
        require(tokenProxy.amount == originalBridgingAssetState.amount) {
            "Bridged amount must match the input amount"
        }
        require(tokenProxy.minted) { "Bridging asset must be marked as minted" }

        require(tx.commands.size == 1) {
            "Bridging transaction must only contain a single command"
        }
    }

    /**
     * Commands for the Corda to Solana bridging flow:
     * The bridging lifecycle is:
     * 1) [LockToken] — lock (escrow) the Corda-side fungible tokens under the bridge’s control.
     * 2) [MintToSolana] — (after evidence/confirmation) mint the equivalent SPL amount on Solana.
     */
    sealed interface BridgingCommand : CommandData {
        /**
         * Locks a Corda-side fungible token balance so it cannot be spent while the
         * equivalent amount is minted on Solana.
         *
         * @property bridgeAuthority The well-known Corda [Party] operating the bridge,
         *   that owns a proxy of the fungible token [BridgedFungibleTokenProxy] to be used for minting on Solana.
         * @property lockingIdentity The Corda identity (confidential) that owns
         *   the token being locked prior to minting on Solana.
         *
         * @throws IllegalArgumentException if [bridgeAuthority] and [lockingIdentity] refer to the same party.
         */
        data class LockToken(
            val bridgeAuthority: Party,
            val lockingIdentity: AbstractParty,
        ) : BridgingCommand {
            init {
                require(bridgeAuthority != lockingIdentity) {
                    "Locking Identity must be different from Bridge Authority"
                }
            }
        }

        /**
         * Mints the bridged amount on Solana for the designated destination.
         */
        object MintToSolana : BridgingCommand
    }

    companion object {
        const val BRIDGE_PROGRAM_ID = "com.r3.corda.lib.solana.bridging.token.contracts.BridgingContract"
    }
}
