package com.r3.corda.lib.solana.bridging.token.states

import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.solana.sdk.instruction.Pubkey

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
 * @property redemptionTokenAccount Token account public key on Solana from which the tokens were burnt.
 * @property redemptionWalletAccount Token wallet public key on Solana that owns the [redemptionTokenAccount].
 * @property mintAccount Token **mint** public key on Solana (the asset definition).
 * @property amount Quantity of fungible tokens that were burnt on Solana.
 * @property bridgeAuthority The party performing the redemption from Solana.
 */
@BelongsToContract(FungibleTokenRedemptionContract::class)
data class FungibleTokenBurnReceipt(
    val redemptionTokenAccount: Pubkey,
    val redemptionWalletAccount: Pubkey,
    val mintAccount: Pubkey,
    val amount: Long,
    val bridgeAuthority: Party,
) : ContractState {
    override val participants: List<AbstractParty>
        get() = listOf(bridgeAuthority)
}
