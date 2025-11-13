package com.r3.corda.lib.solana.bridging.token.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenBridgeContract
import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.workflows.utilities.sessionsForParties
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.NotaryChangeFlow
import net.corda.core.flows.StartableByService
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
 * @param originalHolder the identity that transferred [token] to the bridge authority prior to locking.
 * @param token The fungible token state to be bridged to Solana.
 * @param solanaNotary Notary that performs bridging (minting on Solana).
 * @param observers Optional observing parties to receive finalized transactions (may be empty).
 */
@StartableByService
@InitiatingFlow
class BridgeFungibleTokenFlow(
    val lockingHolder: Party,
    val originalHolder: Party,
    val token: StateAndRef<FungibleToken>,
    val solanaNotary: Party,
    val observers: List<Party>,
) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val bridgingService = serviceHub.cordaService(BridgingService::class.java)
        val bridgingCoordinates = bridgingService.getBridgingCoordinates(token, originalHolder)

        bridgingService.createAta(bridgingCoordinates.mint, bridgingCoordinates.mintDestination)

        // Move the token from ourIdentity (implied BridgeAuthority) to the lock holder (confidential identity).
        // Also, create a proxy of Fungible Token that will be later used to mint a token on Solana
        val moveTx = subFlow(
            MoveAndLockFungibleTokenFlow(
                token,
                bridgingCoordinates,
                lockingHolder,
                participantSessions = sessionsForParties(listOf(lockingHolder)),
                observerSessions = sessionsForParties(observers),
            )
        )

        // Change notary to Solana notary
        val bridgedFungibleTokenProxy =
            moveTx.toLedgerTransaction(serviceHub).outRefsOfType<BridgedFungibleTokenProxy>().single()
        // TODO This needs to use the new MoveNotaryFlow
        val tokenProxyOnSolanaNotary = subFlow(NotaryChangeFlow(bridgedFungibleTokenProxy, solanaNotary))

        // Mint on Solana
        val mintTx = createMintTransaction(tokenProxyOnSolanaNotary)

        // TODO ENT-14346 Shouldn't the observer sessions be passed to finality of this transaction?
        return subFlow(FinalityFlow(mintTx, emptyList()))
    }

    private fun createMintTransaction(tokenProxyRef: StateAndRef<BridgedFungibleTokenProxy>): SignedTransaction {
        val bridgedFungibleTokenProxy = tokenProxyRef.state.data
        val transactionBuilder = TransactionBuilder(solanaNotary)
        val instruction = Token2022.mintTo(
            bridgedFungibleTokenProxy.mint,
            bridgedFungibleTokenProxy.mintDestination,
            bridgedFungibleTokenProxy.mintAuthority,
            bridgedFungibleTokenProxy.amount,
        )
        transactionBuilder.addNotaryInstruction(instruction)
        transactionBuilder.addCommand(
            FungibleTokenBridgeContract.BridgeCommand.MintToSolana,
            listOf(ourIdentity.owningKey),
        )
        transactionBuilder.addInputState(tokenProxyRef)
        transactionBuilder.verify(serviceHub)
        return serviceHub.signInitialTransaction(transactionBuilder)
    }
}
