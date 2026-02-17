package com.r3.corda.lib.solana.bridging.token.states

import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenBridgeContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.solana.Pubkey

/**
 * A proxy state that mirrors a Corda `FungibleToken` while carrying
 * the minting metadata required to create an equivalent token amount on Solana.
 *
 * This state exists to work around the inability to change the notary of `FungibleToken` directly:
 * instead of moving the original token state across notaries,
 * flows can operate on this proxy and keep the Solana-bridging data alongside the fungible quantity.
 *
 * Note: The following is the applied nomenclature:
 * mintAuthority
 * mintAccount
 * [bridge|redeem]WalletAccount
 * [bridge|redeem]TokenAccount
 *
 * @property cordaAmount Quantity of Corda fungible tokens represented by this
 * been minted on Solana for [bridgeTokenAccount].
 * @property solanaAmount Quantity of tokens represented by this proxy
 * been minted on Solana for [bridgeTokenAccount].
 * @property bridgeTokenAccount Token account public key that should receive the minted tokens on Solana.
 * @property mintAccount Token **mint** public key on Solana (the asset definition).
 * @property mintAuthority Public key that is authorized to mint for [mintAccount] on Solana
 * (address controlled by the bridge).
 * @property bridgeAuthority The party performing the bridge onto Solana.
 */
@BelongsToContract(FungibleTokenBridgeContract::class)
data class BridgedFungibleTokenProxy(
    val cordaAmount: Amount,
    val solanaAmount: Amount,
    val bridgeTokenAccount: Pubkey,
    val mintAccount: Pubkey,
    val mintAuthority: Pubkey,
    val bridgeAuthority: Party,
    override val participants: List<AbstractParty> = listOf(bridgeAuthority),
) : ContractState {
    init {
        // future-proof extra check in case an object is deserialized by AMQP on a node that doesn't have this class
        require(bridgeAuthority in participants) { "Bridge Authority is not present in participants list." }
        require(cordaAmount.hasSameValueAs(solanaAmount)) { "Corda amount must be equal to Solana amount." }
    }
}
