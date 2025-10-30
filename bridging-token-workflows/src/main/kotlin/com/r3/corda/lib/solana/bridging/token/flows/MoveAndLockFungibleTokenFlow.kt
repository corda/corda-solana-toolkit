package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenBridgingContract
import com.r3.corda.lib.tokens.contracts.states.AbstractToken
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import com.r3.corda.lib.tokens.workflows.flows.move.AbstractMoveTokensFlow
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveTokens
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowSession
import net.corda.core.identity.AbstractParty
import net.corda.core.transactions.TransactionBuilder

/**
 * Flow moves a token to new holder to lock it and creates a token equivalent for bridging purposes.
 * Token equivalent contains data for Solana to perform minting and redemption.
 **/
class MoveAndLockFungibleTokenFlow
@JvmOverloads
constructor(
    val token: StateAndRef<FungibleToken>,
    val bridgingCoordinates: BridgingCoordinates,
    val lockingHolder: AbstractParty,
    override val participantSessions: List<FlowSession>,
    override val observerSessions: List<FlowSession> = emptyList(),
) : AbstractMoveTokensFlow() {
    @Suspendable
    override fun addMove(transactionBuilder: TransactionBuilder) {
        val output = token.state.data.withNewHolder(lockingHolder)
        addMoveTokens(transactionBuilder = transactionBuilder, inputs = listOf(token), outputs = listOf(output))

        val outputGroups: Map<IssuedTokenType, List<AbstractToken>> =
            transactionBuilder
                .outputStates()
                .map { it.data }
                .filterIsInstance<AbstractToken>()
                .groupBy { it.issuedTokenType }
        val inputGroups: Map<IssuedTokenType, List<StateAndRef<AbstractToken>>> =
            transactionBuilder
                .inputStates()
                .map { serviceHub.toStateAndRef<AbstractToken>(it) }
                .groupBy { it.state.data.issuedTokenType }

        check(outputGroups.keys == inputGroups.keys) {
            "Input and output token types must correspond to each other when moving tokensToIssue"
        }

        // Added for clarity, this is implied condition because only a single token is moved at a time
        check(outputGroups.keys.size == 1) {
            "When bridging a fungible token, only one token type can be moved at a time."
        }

        val bridgingState: ContractState = bridgingCoordinates.toFungibleTokenProxy(token.state.data, ourIdentity)

        transactionBuilder.addOutputState(bridgingState)

        val bridgingCommand = FungibleTokenBridgingContract.BridgingCommand.LockToken(ourIdentity, lockingHolder)

        for (issuedTokenType in outputGroups.keys) {
            val inputGroup = requireNotNull(inputGroups[issuedTokenType]) {
                "No corresponding inputs for the outputs issued token type: $issuedTokenType"
            }
            val keys = inputGroup.map { it.state.data.holder.owningKey }
            transactionBuilder.addCommand(bridgingCommand, keys)
        }
    }
}
