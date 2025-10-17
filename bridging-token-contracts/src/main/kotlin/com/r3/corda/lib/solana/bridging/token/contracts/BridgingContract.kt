package com.r3.corda.lib.solana.bridging.token.contracts

import com.lmax.solana4j.programs.TokenProgramBase
import com.r3.corda.lib.solana.bridging.token.states.BridgedAssetState
import com.r3.corda.lib.tokens.contracts.commands.MoveTokenCommand
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.solana.sdk.instruction.SolanaInstruction
import net.corda.solana.sdk.internal.Token2022
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("MaxLineLength", "ArgumentListWrapping", "FunctionLiteral", "Wrapping", "ClassSignature", "FunctionSignature")
class BridgingContract : Contract {
    override fun verify(tx: LedgerTransaction) {
        val bridgingCommands = tx.commandsOfType<BridgingCommand>()

        require(bridgingCommands.size == 1) { "Bridging transactions must have single bridging command" }

        when (val bridgingCommand = bridgingCommands.single().value) {
            is BridgingCommand.MintToSolana -> verifyMintToSolana(tx, bridgingCommand)
            is BridgingCommand.IssueBridgingAsset -> verifyIssueBridgingAsset(tx, bridgingCommand)
        }
    }

    private fun verifyIssueBridgingAsset(
        tx: LedgerTransaction,
        bridgingCommand: BridgingCommand.IssueBridgingAsset,
    ) {
        val bridgingAssetState = tx.outputsOfType<BridgedAssetState>().singleOrNull()
        val tokenState = tx.outputsOfType<FungibleToken>().singleOrNull()

        require(bridgingAssetState != null) { "Bridging transaction must have exactly one BridgedAssetState as output" }
        require(tokenState != null) { "Bridging transaction must have exactly one FungibleToken as output" }
        require(bridgingCommand.bridgeAuthority in bridgingAssetState.participants) { "Holding identity must be a participant" }

        val moveCommands = tx.commandsOfType<MoveTokenCommand>()

        require(moveCommands.size == 1) { "Bridging must have one move command to lock token with the holding identity" }

        val lockedSum =
            tx
                .outputsOfType<FungibleToken>()
                .filter { it.holder != bridgingCommand.bridgeAuthority }
                // ... currently can't distinguish between locked and a change, both are for same holder
                .sumOf {
                    it.amount.toDecimal().toLong()
                }

        require(lockedSum == bridgingAssetState.amount) {
            "Locked amount of $lockedSum must match requested lock amount ${bridgingAssetState.amount}."
        }
        require(bridgingAssetState.minted.not()) { "Bridging asset must not be marked as minted when issuing." }
    }

    private fun verifyMintToSolana(
        tx: LedgerTransaction,
        bridgingCommand: BridgingCommand.MintToSolana,
    ) {
        val bridgingAssetState = tx.outputsOfType<BridgedAssetState>().singleOrNull()

        require(bridgingAssetState != null) { "Bridging transaction must have exactly one BridgedAssetState as output" }
        require(bridgingCommand.bridgeAuthority in bridgingAssetState.participants) {
            "BridgedAssetState must have holding identity as participant"
        }

        val mintCommand = tx.commandsOfType<BridgingCommand.MintToSolana>()

        require(mintCommand.size == 1) { "Bridging must have one mint command" }

        val instruction = tx.notaryInstructions.singleOrNull() as? SolanaInstruction

        require(instruction != null) { "Exactly one Solana mint instruction required" }

        require(instruction.programId == Token2022.PROGRAM_ID) { "Solana program id must be Token2022 program" }
        require(instruction.accounts[1].pubkey == bridgingAssetState.mintDestination) {
            "Target in instructions does not match mint destination address"
        }

        @Suppress("MagicNumber")
        require(instruction.data.size == 9) { "Expecting 9 bytes of instruction data" }

        val instructionBytes = ByteBuffer.wrap(instruction.data.bytes).order(ByteOrder.LITTLE_ENDIAN)

        val tokenInstruction = instructionBytes.get().toInt()

        val amount = instructionBytes.getLong()
        require(tokenInstruction == TokenProgramBase.MINT_TO_INSTRUCTION) { "Token instruction must be MINT_TO_INSTRUCTION" }
        require(amount == bridgingAssetState.amount) { "BridgeAssetState amount must match requested mint amount $amount." }

        val originalBridgingAssetState = tx.inputsOfType<BridgedAssetState>().singleOrNull()
        require(originalBridgingAssetState != null) { "Bridging transaction must have exactly one BridgingAssetState as input" }
        require(bridgingAssetState.amount == originalBridgingAssetState.amount) { "Bridged amount must match the input amount." }
    }

    sealed interface BridgingCommand : CommandData {
        data class IssueBridgingAsset(
            val bridgeAuthority: Party,
            val holdingIdentity: Party,
        ) : BridgingCommand

        data class MintToSolana(
            val bridgeAuthority: Party,
        ) : BridgingCommand
    }
}
