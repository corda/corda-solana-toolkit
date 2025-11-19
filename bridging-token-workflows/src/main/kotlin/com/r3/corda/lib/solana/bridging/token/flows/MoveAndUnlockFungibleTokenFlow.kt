package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract
import com.r3.corda.lib.solana.bridging.token.states.FungibleTokenBurnReceipt
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

class MoveAndUnlockFungibleTokenFlow
@JvmOverloads
constructor(
    val burnReceiptStateAndRef: StateAndRef<FungibleTokenBurnReceipt>,
    val bridgeAuthority: Party,
    val lockingHolder: Party,
    val amount: Amount<TokenType>,
    val lockCapture: FungibleTokenLockCapture,
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

        // Capture the lockId generated during move to use it later to unlock tokens
        lockCapture.lockId = transactionBuilder.lockId

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
            "Input and output token types must correspond to each other when moving tokens"
        }

        check(outputGroups.keys.size == 1) {
            "When redeeming a fungible token, only one token type can be moved at a time."
        }

        transactionBuilder.addInputState(burnReceiptStateAndRef)
        val unlockTokenCommand = FungibleTokenRedemptionContract.RedeemCommand.UnlockToken(lockingHolder)

        outputGroups.forEach { (issuedTokenType: IssuedTokenType, _: List<AbstractToken>) ->
            val inputGroup =
                inputGroups[issuedTokenType]
                    ?: throw IllegalArgumentException(
                        "No corresponding inputs for the outputs issued token type: " +
                            "$issuedTokenType",
                    )
            val keys = inputGroup.map { it.state.data.holder.owningKey }
            transactionBuilder.addCommand(unlockTokenCommand, keys)
        }
    }
}
