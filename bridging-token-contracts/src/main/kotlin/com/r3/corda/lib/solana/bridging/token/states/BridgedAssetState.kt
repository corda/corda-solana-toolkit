package com.r3.corda.lib.solana.bridging.token.states

import com.r3.corda.lib.solana.bridging.token.contracts.BridgingContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.identity.AbstractParty
import net.corda.solana.sdk.instruction.Pubkey

@BelongsToContract(BridgingContract::class)
data class BridgedAssetState(
    val originalOwner: AbstractParty,
    val amount: Long,
    val tokenTypeId: String,
    val tokenRef: StateRef,
    val minted: Boolean,
    val mintDestination: Pubkey,
    val mint: Pubkey,
    val mintAuthority: Pubkey,
    override val participants: List<AbstractParty>
) : ContractState