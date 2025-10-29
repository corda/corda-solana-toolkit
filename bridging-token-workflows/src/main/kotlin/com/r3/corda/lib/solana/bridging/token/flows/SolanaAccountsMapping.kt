package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.AbstractParty

interface SolanaAccountsMapping {
    /**
     * Returns Solana mint metadata for a given original holder of a fungible token.
     * @param token the Corda fungible token
     * @param originalHolder the identity that transferred [token] to the bridging authority prior to locking.
     */
    fun getBridgingCoordinates(token: StateAndRef<FungibleToken>, originalHolder: AbstractParty): BridgingCoordinates
}
