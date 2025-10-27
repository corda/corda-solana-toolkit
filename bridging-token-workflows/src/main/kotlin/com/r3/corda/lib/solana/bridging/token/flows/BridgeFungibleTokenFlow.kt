package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.contracts.BridgingContract
import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
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
 * Bridges a Corda-side fungible token position to an equivalent SPL token on Solana.
 *
 * The flow orchestrates a three-phase protocol:
 *
 * 1. **Lock phase** — Move the Corda fungible amount under the bridge’s control to a confidential identity
 *    owned by the identity running the flow and produce a proxy state carrying Solana minting metadata.
 *
 * 2. **Notary move phase** — Change the notary of the proxy state to [solanaNotary].
 *
 * 3. **Mint phase** — Mint the corresponding token amount on Solana and mark the proxy state as bridged.
 *
 * @param lockingHolder Confidential identity party that operates the bridge/escrow. The actual lock
 *   may use a confidential identity derived from this party.
 * @param originalHolder Current owner of [token] prior to locking.
 * @param token The fungible token state to be bridged to Solana.
 * @param solanaNotary Notary that performs bridging (minting on Solana).
 * @param observers Optional observing parties to receive finalized transactions (may be empty).
 */
@StartableByService
@InitiatingFlow
class BridgeFungibleTokenFlow(
    val lockingHolder: AbstractParty,
    val originalHolder: AbstractParty,
    val token: StateAndRef<FungibleToken>,
    val solanaNotary: Party,
    val observers: List<Party>,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val solanaMapping = serviceHub.cordaService(SolanaAccountsMappingService::class.java)
        val bridgingCoordinates = solanaMapping.getBridgingCoordinates(token, originalHolder)

        val participants = listOf(lockingHolder)
        val observerSessions = sessionsForParties(observers)
        val participantSessions = sessionsForParties(participants)

        // Move the token from ourIdentity (implied BridgeAuthority) to the lock holder (confidential identity).
        // Also, create a proxy of Fungible Token that will be later used to mint a token on Solana
        val moveTx =
            subFlow(
                MoveAndLockFungibleTokenFlow(
                    token,
                    bridgingCoordinates,
                    lockingHolder,
                    participantSessions,
                    observerSessions,
                ),
            )

        // Change notary to Solana notary
        val moveBridgingAssetState =
            moveTx.toLedgerTransaction(serviceHub).outRefsOfType<BridgedFungibleTokenProxy>().single()
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
            BridgingContract.BridgingCommand.MintToSolana(),
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
class BridgeFungibleTokenHandler(
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
