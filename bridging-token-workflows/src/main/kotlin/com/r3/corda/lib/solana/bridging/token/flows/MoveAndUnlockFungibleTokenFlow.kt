package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.contracts.RedeemContract
import com.r3.corda.lib.solana.bridging.token.states.toDecimalAmount
import com.r3.corda.lib.tokens.contracts.states.AbstractToken
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.workflows.flows.move.AbstractMoveTokensFlow
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveFungibleTokens
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowSession
import net.corda.core.identity.Party
import net.corda.core.transactions.TransactionBuilder
import net.corda.solana.sdk.instruction.Pubkey

class MoveAndUnlockFungibleTokenFlow
@JvmOverloads
constructor(
    val bridgingCoordinates: BridgingCoordinates,
    val bridgeAuthority: Party,
    val lockingHolder: Party,
    val amount: Amount<TokenType>,
    val burnSource: Pubkey,
    override val participantSessions: List<FlowSession> = emptyList(),
    override val observerSessions: List<FlowSession> = emptyList(),
) : AbstractMoveTokensFlow() {
    @Suspendable
    override fun addMove(transactionBuilder: TransactionBuilder) {
        addMoveFungibleTokens(
            transactionBuilder = transactionBuilder,
            serviceHub = serviceHub,
            amount = amount,
            holder = bridgeAuthority,
            changeHolder = lockingHolder
        )

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

        val redeemState = bridgingCoordinates.toRedeemState(
            burnSource = burnSource,
            amount = amount.toDecimalAmount(),
            bridgeAuthority = bridgeAuthority,
            transactionBuilder.lockId
        )

        transactionBuilder.addOutputState(redeemState)

        val issueRedeemStateCommand = RedeemContract.RedeemCommand.IssueRedeemState()

        outputGroups.forEach { (issuedTokenType: IssuedTokenType, _: List<AbstractToken>) ->
            val inputGroup =
                inputGroups[issuedTokenType]
                    ?: throw IllegalArgumentException(
                        "No corresponding inputs for the outputs issued token type: " +
                            "$issuedTokenType",
                    )
            val keys = inputGroup.map { it.state.data.holder.owningKey }
            transactionBuilder.addCommand(issueRedeemStateCommand, keys)
        }
    }
}
