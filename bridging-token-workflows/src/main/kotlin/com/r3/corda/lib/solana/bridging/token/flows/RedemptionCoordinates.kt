package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.FungibleTokenBurnReceipt
import net.corda.core.identity.Party
import net.corda.core.solana.Pubkey

/**
 * Holds the necessary metadata to redeem a Corda token to Solana Token.
 *
 * @property mintAccount Token **mint** public key on Solana (the asset definition).
 * @property redemptionWalletAccount Public key that will own the redemption wallet on Solana
 * @property redemptionTokenAccount Token **token account** public key on Solana on which the tokens will be burnt
 * @property tokenId The identifier of the Corda token that is being redeemed
 * and will be able to burn them during the redemption.
 */
data class RedemptionCoordinates(
    val mintAccount: Pubkey,
    val redemptionWalletAccount: Pubkey,
    val redemptionTokenAccount: Pubkey,
    val tokenId: String,
) {
    /**
     * Creates a [FungibleTokenBurnReceipt].
     * @param amount the amount of tokens to redeem
     * @param bridgeAuthority the Corda party operating the bridge
     * */
    fun toRedeemReceiptState(
        amount: Long,
        bridgeAuthority: Party,
    ) = FungibleTokenBurnReceipt(
        redemptionTokenAccount = redemptionTokenAccount,
        redemptionWalletAccount = redemptionWalletAccount,
        mintAccount = mintAccount,
        amount = amount,
        bridgeAuthority = bridgeAuthority
    )
}
