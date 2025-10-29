package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.Party
import net.corda.solana.sdk.instruction.Pubkey

/**
 * Holds the necessary metadata to bridge a Corda token to Solana Token.
 *
 * @property mint Token **mint** public key on Solana (the asset definition).
 * @property mintAuthority Public key that is authorized to mint for [mint] on Solana
 * (address controlled by the bridge).
 * @property destination Token **wallet** public key that should receive the minted tokens on Solana.
 */
data class BridgingCoordinates(
    // TODO originalOwner, cordaTokenId will be added alongside redemption code
    val mint: Pubkey,
    val mintAuthority: Pubkey,
    val destination: Pubkey,
) {
    /**
     * Creates an unminted [BridgedFungibleTokenProxy].
     * Converts the Fungible Token amount to Solana token amount in 1:1 ration.
     * @param token the source of amount to bridge
     * @param participants Corda participants who should see and record this state (the bridge party).
     */
    fun toFungibleTokenProxy(token: StateAndRef<FungibleToken>, participants: List<Party>) =
        BridgedFungibleTokenProxy(
            amount = token.state.data.amount.quantity,
            minted = false,
            mint = this.mint,
            mintAuthority = this.mintAuthority,
            mintDestination = this.destination,
            participants = participants,
        )
}
