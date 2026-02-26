package com.r3.corda.lib.solana.bridging.token.states

import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party

/**
 * A state issued to record the burning of fungible tokens on Solana
 * as part of the redemption process back to Corda.
 *
 * Note: The following is the applied nomenclature:
 * mintAuthority
 * mintAccount
 * [bridge|redeem]WalletAccount
 * [bridge|redeem]TokenAccount
 *
 * @property redemptionTokenAccountBase58 Token account public key (base58 string) on Solana from which
 * the tokens were burnt.
 * @property redemptionWalletAccountBase58 Token wallet public key (base58 string) on Solana that owns
 * the [redemptionTokenAccount].
 * @property mintAccountBase58 Token **mint** public key (base58 string) on Solana (the asset definition).
 * @property cordaAmount Quantity of fungible tokens that were burnt on Solana.
 * @property solanaAmount Quantity of tokens that were burnt on Solana.
 * @property bridgeAuthority The party performing the redemption from Solana.
 */
@BelongsToContract(FungibleTokenRedemptionContract::class)
data class FungibleTokenBurnReceipt(
    val redemptionTokenAccountBase58: String,
    val redemptionWalletAccountBase58: String,
    val mintAccountBase58: String,
    val cordaAmount: TokenAmount,
    val solanaAmount: TokenAmount,
    val bridgeAuthority: Party,
    override val participants: List<AbstractParty> = listOf(bridgeAuthority),
) : ContractState {
    init {
        // extra check when object is deserialized by AMQP on a node that redeemed token and doesn't have this class
        require(bridgeAuthority in participants) { "Bridge Authority is not present in participants list." }
        require(cordaAmount.isNumericallyEqual(solanaAmount)) {
            "Corda amount must be equal to Solana amount in the burn receipt."
        }
    }
}
