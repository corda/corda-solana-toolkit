package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.contracts.BridgingContract
import com.r3.corda.lib.solana.bridging.token.states.BridgedAssetState
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.workflows.flows.move.MoveTokensFlowHandler
import com.r3.corda.lib.tokens.workflows.utilities.sessionsForParties
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
    val observers: List<Party>,
    val token: StateAndRef<FungibleToken>,
    val solanaNotary: Party,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val solanaMapping = serviceHub.cordaService(SolanaAccountsMappingService::class.java)
        val bridgingCoordinates = solanaMapping.getBridgingCoordinates(token, originalOwner)

        val participants = listOf(lockingHolder)
        val observerSessions = sessionsForParties(observers)
        val participantSessions = sessionsForParties(participants)

        // Move the token from ourIdentity (implied BridgeAuthority) to the lock holder (confidential identity).
        // Also, create a BridgedAssetState that will be later used to mint the tokens on Solana
        val moveTx =
            subFlow(
                MoveAndLockFungibleTokenFlow(
                    participantSessions,
                    observerSessions,
                    token,
                    bridgingCoordinates,
                    lockingHolder,
                ),
            )

        // Change notary to Solana notary
        val moveBridgingAssetState = moveTx.toLedgerTransaction(serviceHub).outRefsOfType<BridgedAssetState>().single()
        val notaryChangeTx = subFlow(NotaryChangeFlow(moveBridgingAssetState, solanaNotary))

        // Mint on Solana
        val transactionBuilder = TransactionBuilder(solanaNotary)
        val instruction = Token2022.mintTo(
            bridgingCoordinates.mint,
            bridgingCoordinates.destination,
            bridgingCoordinates.mintAuthority,
            moveBridgingAssetState.state.data.amount,
        )
        transactionBuilder.addNotaryInstruction(instruction)
        transactionBuilder.addCommand(
            BridgingContract.BridgingCommand.MintToSolana(ourIdentity),
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
