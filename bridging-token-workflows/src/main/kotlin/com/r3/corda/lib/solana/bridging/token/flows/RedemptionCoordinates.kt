package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.FungibleTokenBurnReceipt
import net.corda.core.identity.Party
import net.corda.solana.sdk.instruction.Pubkey

/**
 * Holds the necessary metadata to redeem a Corda token to Solana Token.
 *
 * @property mintAccount Token **mint** public key on Solana (the asset definition).
 * @property redeemWalletAccount Public key that will own the redemption wallet on Solana
 * and will be able to burn them during the redemption.
 */
data class RedemptionCoordinates(
    val mintAccount: Pubkey,
    val redeemWalletAccount: Pubkey,
) {
    /**
     * Creates a [FungibleTokenBurnReceipt].
     * @param redeemTokenAccount the Solana wallet to which tokens are sent to be burnt/redeemed
     * @param amount the amount of tokens to redeem
     * @param bridgeAuthority the Corda party operating the bridge
     * */
    fun toRedeemReceiptState(
        redeemTokenAccount: Pubkey,
        amount: Long,
        bridgeAuthority: Party,
    ) = FungibleTokenBurnReceipt(
        redeemTokenAccount = redeemTokenAccount,
        redeemWalletAccount = redeemWalletAccount,
        mintAccount = mintAccount,
        amount = amount,
        bridgeAuthority = bridgeAuthority
    )
}
