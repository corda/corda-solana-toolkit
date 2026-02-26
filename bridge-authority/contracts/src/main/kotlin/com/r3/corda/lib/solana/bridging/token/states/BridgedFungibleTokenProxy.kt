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
 * @property bridgeTokenAccountBase58 Token account public key (base58 string) that should receive
 * the minted tokens on Solana.
 * @property mintAccountBase58 Token **mint** public key (base58 string) on Solana (the asset definition).
 * @property mintAuthorityBase58 Public key (base58 string) that is authorized to mint for [mintAccount]
 * on Solana (address controlled by the bridge).
 * @property bridgeAuthority The party performing the bridge onto Solana.
 */
@BelongsToContract(FungibleTokenBridgeContract::class)
data class BridgedFungibleTokenProxy(
    val cordaAmount: TokenAmount,
    val solanaAmount: TokenAmount,
    val bridgeTokenAccountBase58: String,
    val mintAccountBase58: String,
    val mintAuthorityBase58: String,
    val bridgeAuthority: Party,
    override val participants: List<AbstractParty> = listOf(bridgeAuthority),
) : ContractState {
    init {
        // future-proof extra check in case an object is deserialized by AMQP on a node that doesn't have this class
        require(bridgeAuthority in participants) { "Bridge Authority is not present in participants list." }
        require(cordaAmount.isNumericallyEqual(solanaAmount)) { "Corda amount must be equal to Solana amount." }
    }

    /**
     * Transforms [bridgeTokenAccountBase58] to a [Pubkey].
     * @return The bridge token account as a [Pubkey]
     */
    fun bridgeTokenAccount(): Pubkey = Pubkey.fromBase58(bridgeTokenAccountBase58)

    /**
     * Transforms [mintAccountBase58] to a [Pubkey].
     * @return The mint account as a [Pubkey]
     */
    fun mintAccount(): Pubkey = Pubkey.fromBase58(mintAccountBase58)

    /**
     * Transforms [mintAuthorityBase58] to a [Pubkey].
     * @return The mint authority as a [Pubkey]
     */
    fun mintAuthority(): Pubkey = Pubkey.fromBase58(mintAuthorityBase58)
}
