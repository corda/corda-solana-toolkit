package com.r3.corda.lib.solana.bridging.token.states

import com.r3.corda.lib.solana.bridging.token.contracts.FungibleTokenRedemptionContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.solana.sdk.instruction.Pubkey
import java.util.*

@BelongsToContract(FungibleTokenRedemptionContract::class)
data class RedeemedFungibleTokenProxy(
    val burnAccount: Pubkey,
    val redemptionWallet: Pubkey,
    val mint: Pubkey,
    val amount: Long,
    val bridgeAuthority: Party,
) : ContractState {
    override val participants: List<AbstractParty>
        get() = listOf(bridgeAuthority)
}
