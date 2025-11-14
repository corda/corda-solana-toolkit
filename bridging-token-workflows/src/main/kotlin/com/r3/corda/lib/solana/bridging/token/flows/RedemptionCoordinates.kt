package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.RedeemedFungibleTokenProxy
import net.corda.core.identity.Party
import net.corda.solana.sdk.instruction.Pubkey
import java.util.*

/**
 * Holds the necessary metadata to redeem a Corda token to Solana Token.
 *
 * @property mint Token **mint** public key on Solana (the asset definition).
 * @property redemptionWallet Public key that will own the redemption wallet on Solana
 * and will be able to burn them during the redemption.
 */
data class RedemptionCoordinates(
    val mint: Pubkey,
    val redemptionWallet: Pubkey,
) {
    /**
     * Creates a [RedeemedFungibleTokenProxy].
     * @param burnAccount the Solana wallet to which tokens are sent to be burnt/redeemed
     * @param amount the amount of tokens to redeem
     * @param bridgeAuthority the Corda party operating the bridge
     * @param lockId the unique identifier of lock used to soft-lock the fungible tokens on Corda
     * */
    fun toRedeemState(
        burnAccount: Pubkey,
        amount: Long,
        bridgeAuthority: Party,
        lockId: UUID,
    ) = RedeemedFungibleTokenProxy(
        burnAccount = burnAccount,
        redemptionWallet = redemptionWallet,
        mint = mint,
        amount = amount,
        lockId = lockId,
        bridgeAuthority = bridgeAuthority
    )
}
