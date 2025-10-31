package com.r3.corda.lib.solana.bridging.token.states

import com.r3.corda.lib.solana.bridging.token.contracts.RedeemContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.solana.sdk.instruction.Pubkey
import java.util.UUID

@BelongsToContract(RedeemContract::class)
data class RedeemState(
    val burnSource: Pubkey,
    val bridgeRedemptionWallet: Pubkey,
    val mint: Pubkey,
    val amount: Long,
    val tokenTypeId: String,
    val originalOwner: AbstractParty,
    val bridgingAuthority: AbstractParty,
    val lockId: UUID
) : ContractState {
    override val participants: List<AbstractParty>
        get() = listOf(bridgingAuthority)
}
