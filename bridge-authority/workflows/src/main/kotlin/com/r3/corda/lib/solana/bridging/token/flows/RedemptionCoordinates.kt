package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.FungibleTokenBurnReceipt
import com.r3.corda.lib.solana.bridging.token.states.TokenAmount
import net.corda.core.identity.Party

/**
 * Holds the necessary metadata to redeem a Corda token to Solana Token.
 *
 * @property mintAccount Token **mint** public key (base58 string) on Solana (the asset definition).
 * @property redemptionWalletAccount Public key (base58 string) that will own the redemption wallet on Solana
 * @property redemptionTokenAccount Token **token account** public key (base58 string) on Solana
 * on which the tokens will be burnt
 * @property tokenId The identifier of the Corda token that is being redeemed
 * and will be able to burn them during the redemption.
 */
data class RedemptionCoordinates(
    val mintAccount: String,
    val redemptionWalletAccount: String,
    val redemptionTokenAccount: String,
    val tokenId: String,
) {
    /**
     * Creates a [FungibleTokenBurnReceipt].
     * @param solanaAmount the amount of tokens to redeem
     * @param cordaAmount the amount of tokens to redeem in Corda representation (for recording in the receipt)
     * @param bridgeAuthority the Corda party operating the bridge
     * */
    fun toRedeemReceiptState(
        solanaAmount: TokenAmount,
        cordaAmount: TokenAmount,
        bridgeAuthority: Party,
    ) = FungibleTokenBurnReceipt(
        redemptionTokenAccount,
        redemptionWalletAccount,
        mintAccount,
        cordaAmount,
        solanaAmount,
        bridgeAuthority,
    )
}
