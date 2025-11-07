package com.r3.corda.lib.solana.bridging.token.states

import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenBridgeContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.solana.sdk.instruction.Pubkey

/**
 * A proxy state that mirrors a Corda `FungibleToken` while carrying
 * the minting metadata required to create an equivalent token amount on Solana.
 *
 * This state exists to work around the inability to change the notary of `FungibleToken` directly:
 * instead of moving the original token state across notaries,
 * flows can operate on this proxy and keep the Solana-bridging data alongside the fungible quantity.
 *
 * @property amount Quantity of fungible tokens represented by this proxy.
 * @property minted Flag indicating whether the corresponding SPL token has already
 * been minted on Solana for [mintDestination].
 * @property mintDestination Token **wallet** public key that should receive the minted tokens on Solana.
 * @property mint Token **mint** public key on Solana (the asset definition).
 * @property mintAuthority Public key that is authorized to mint for [mint] on Solana
 * (address controlled by the bridge).
 * @property bridgeAuthority The party performing the bridge onto Solana.
 */
@BelongsToContract(FungibleTokenBridgeContract::class)
data class BridgedFungibleTokenProxy(
    // TODO originalOwner, tokenTypeId and tokenRef will be added alongside redemption code
    val amount: Long,
    val minted: Boolean,
    val mintDestination: Pubkey,
    val mint: Pubkey,
    val mintAuthority: Pubkey,
    val bridgeAuthority: Party,
) : ContractState {
    override val participants: List<AbstractParty>
        get() = listOf(bridgeAuthority)
}
