package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.contracts.BridgingContract
import com.r3.corda.lib.solana.bridging.token.states.BridgedAssetState
import com.r3.corda.lib.tokens.contracts.states.AbstractToken
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import com.r3.corda.lib.tokens.contracts.types.TokenPointer
import com.r3.corda.lib.tokens.workflows.flows.move.AbstractMoveTokensFlow
import com.r3.corda.lib.tokens.workflows.flows.move.MoveTokensFlowHandler
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveTokens
import com.r3.corda.lib.tokens.workflows.utilities.sessionsForParties
import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.NotaryChangeFlow
import net.corda.core.flows.ReceiveFinalityFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByService
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.solana.sdk.internal.Token2022
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import kotlin.collections.map

/**
 * Initiating flow used to bridge token of the same party.
 *
 * @param observers optional observing parties to which the transaction will be broadcast
 */
@StartableByService
@InitiatingFlow
class BridgeFungibleTokenFlow(
    val lockingHolder: Party,
    val originalOwner: AbstractParty,
    val observers: List<Party> = emptyList(),
    val token: StateAndRef<FungibleToken>,
    val bridgeAuthority: Party,
    val solanaNotary: Party,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    @Suppress("LongMethod")
    override fun call(): SignedTransaction {
        val participants = listOf(lockingHolder)
        val observerSessions = sessionsForParties(observers)
        val participantSessions = sessionsForParties(participants)

        val cordaTokenId =
            when (val tokenType = token.state.data.amount.token.tokenType) {
                // TODO while testing StockCordapp check if tokenType.tokenIdentifier can replace TokenPointer<*>
                is TokenPointer<*> ->
                    tokenType.pointer.pointer.id
                        .toString()

                else -> tokenType.tokenIdentifier
            }

        val previousOwner =
            checkNotNull(previousOwnerOf(serviceHub, token)) {
                "Previous owner of the token $token could not be determined"
            }
        val solanaAccountMapping = serviceHub.cordaService(SolanaAccountsMappingService::class.java)
        val destination = checkNotNull(solanaAccountMapping.participants[previousOwner.nameOrNull()]) {
            "No Solana account mapping found for previous owner ${previousOwner.nameOrNull()}"
        }
        val mint = checkNotNull(solanaAccountMapping.mints[cordaTokenId]) {
            "No mint mapping found for token type id $cordaTokenId"
        }
        val mintAuthority = checkNotNull(solanaAccountMapping.mintAuthorities[cordaTokenId]) {
            "No mint authority mapping found for token type id $cordaTokenId"
        }

        val amount =
            token.state.data.amount
                .toDecimal()
                .toLong()

        val bridgingCommand = BridgingContract.BridgingCommand.IssueBridgingAsset(ourIdentity, lockingHolder)
        val bridgingState: ContractState =
            BridgedAssetState(
                amount = amount,
                originalOwner = originalOwner,
                tokenTypeId = cordaTokenId,
                tokenRef = token.ref,
                minted = false,
                mint = mint,
                mintAuthority = mintAuthority,
                mintDestination = destination,
                participants = listOf(ourIdentity),
            )

        // We move the token from BridgeAuthority to the lock holder (confidential identity).
        // Also, we create a BridgedAssetState that will be later used to mint the tokens on Solana
        val moveTx =
            subFlow(
                MoveAndLockFungibleToken(
                    participantSessions,
                    observerSessions,
                    token,
                    bridgingState,
                    bridgingCommand,
                    lockingHolder,
                    token.state.data.amount,
                ),
            )

        // Change notary to Solana notary
        val moveBridgingAssetState = moveTx.toLedgerTransaction(serviceHub).outRefsOfType<BridgedAssetState>().single()
        val notaryChangeTx = subFlow(NotaryChangeFlow(moveBridgingAssetState, solanaNotary))

        // Mint on Solana
        val transactionBuilder = TransactionBuilder(solanaNotary)
        val instruction = Token2022.mintTo(mint, destination, mintAuthority, amount)
        transactionBuilder.addNotaryInstruction(instruction)
        transactionBuilder.addCommand(
            BridgingContract.BridgingCommand.MintToSolana(bridgeAuthority),
            listOf(ourIdentity.owningKey),
        )
        transactionBuilder.addInputState(StateAndRef(notaryChangeTx.state, notaryChangeTx.ref))
        transactionBuilder.addOutputState(
            state = notaryChangeTx.state.data.copy(minted = true),
            contract = BridgingContract::class.qualifiedName!!,
        )

        // Verify
        transactionBuilder.verify(serviceHub)
        val bridgingAuthoritySignedTransaction = serviceHub.signInitialTransaction(transactionBuilder)
        return subFlow(FinalityFlow(bridgingAuthoritySignedTransaction, emptyList()))
    }
}

/**
 * Responder flow for [BridgeFungibleTokenFlow].
 */
@InitiatedBy(BridgeFungibleTokenFlow::class)
class BridgeFungibleTokensHandler(
    val otherSession: FlowSession,
) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(MoveTokensFlowHandler(otherSession))

        val signedTransaction =
            subFlow(
                object : SignTransactionFlow(otherSession) {
                    override fun checkTransaction(stx: SignedTransaction) {
                        // Nothing additional to check
                    }
                },
            )
        subFlow(ReceiveFinalityFlow(otherSession, signedTransaction.id))
    }
}

class MoveAndLockFungibleToken
@JvmOverloads
constructor(
    override val participantSessions: List<FlowSession>,
    override val observerSessions: List<FlowSession> = emptyList(),
    val token: StateAndRef<FungibleToken>,
    val bridgingState: ContractState,
    val bridgingCommand: BridgingContract.BridgingCommand,
    val lockingHolder: AbstractParty,
    val amount: Amount<IssuedTokenType>,
) : AbstractMoveTokensFlow() {
    @Suspendable
    override fun addMove(transactionBuilder: TransactionBuilder) {
        val output = FungibleToken(amount, lockingHolder)
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

        transactionBuilder.addOutputState(bridgingState)

        outputGroups.forEach { (issuedTokenType: IssuedTokenType, _: List<AbstractToken>) ->
            val inputGroup =
                inputGroups[issuedTokenType]
                    ?: throw IllegalArgumentException(
                        "No corresponding inputs for the outputs issued token type: " +
                            "$issuedTokenType",
                    )
            val keys = inputGroup.map { it.state.data.holder.owningKey }
            transactionBuilder.addCommand(bridgingCommand, keys)
        }
    }
}
