package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.BridgedAssetState
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.solana.sdk.instruction.Pubkey

data class BridgingCoordinates(
    val originalOwner: AbstractParty,
    val cordaTokenId: String,
    val mint: Pubkey,
    val mintAuthority: Pubkey,
    val destination: Pubkey,
) {
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
