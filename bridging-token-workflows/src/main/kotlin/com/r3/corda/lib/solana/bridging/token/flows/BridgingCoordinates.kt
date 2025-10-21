package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.BridgedAssetState
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.solana.sdk.instruction.Pubkey

/**
 * Holds the necessary information to bridge a Corda token to Solana and then redeem it back.
 */
data class BridgingCoordinates(
    val originalOwner: AbstractParty,
    val cordaTokenId: String,
    val mint: Pubkey,
    val mintAuthority: Pubkey,
    val destination: Pubkey,
) {
    /**
     * Creates an unminted [BridgedAssetState] from the given [FungibleToken].
     * Converts the Fungible Token amount to Solana token amount in 1:1 ration.
     * @param token the fungible token to be bridged
     * @param participants the list of participants for the [BridgedAssetState], should at least contain the bridging authority
     */
    fun toUnmintedBridgedAssetState(token: StateAndRef<FungibleToken>, participants: List<Party>) = BridgedAssetState(
        amount = token.state.data.amount
            .toDecimal()
            .toLong(),
        originalOwner = originalOwner,
        tokenTypeId = cordaTokenId,
        tokenRef = token.ref,
        minted = false,
        mint = mint,
        mintAuthority = mintAuthority,
        mintDestination = destination,
        participants = participants,
    )
}
