package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
import com.r3.corda.lib.solana.bridging.token.states.RedeemedFungibleTokenProxy
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.identity.Party
import net.corda.solana.sdk.instruction.Pubkey
import java.util.*

/**
 * Holds the necessary metadata to bridge a Corda token to Solana Token.
 *
 * @property redemptionHolder The Corda party to send the redeemed tokens to.
 * @property tokenTypeId The unique identifier for the type of token being bridged.
 * @property mint Token **mint** public key on Solana (the asset definition).
 * @property mintAuthority Public key that is authorized to mint for [mint] on Solana
 * (address controlled by the bridge).
 * @property mintDestination Token **wallet** public key that should receive the minted tokens on Solana.
 * @property bridgeRedemptionWallet Public key that will own the redemption wallet on Solana
 * and will be able to burn them during the redemption.
 */
data class BridgingCoordinates(
    val redemptionHolder: Party,
    val tokenTypeId: String,
    val mint: Pubkey,
    val mintAuthority: Pubkey,
    val mintDestination: Pubkey,
    val bridgeRedemptionWallet: Pubkey,
) {
    /**
     * Creates an unminted [BridgedFungibleTokenProxy].
     * Converts the Fungible Token amount to Solana token amount in 1:1 ration.
     * @param token the source of amount to bridge
     */
    fun toBridgedFungibleTokenProxy(token: FungibleToken, bridgeAuthority: Party) =
        BridgedFungibleTokenProxy(
            amount = token.amount.quantity,
            mint = this.mint,
            mintAuthority = this.mintAuthority,
            mintDestination = this.mintDestination,
            bridgeAuthority = bridgeAuthority,
        )

    /**
     * Creates a [RedeemedFungibleTokenProxy].
     * @param burnAccount the Solana public key where the redeemed tokens will be sent
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
        bridgeRedemptionWallet = bridgeRedemptionWallet,
        mint = mint,
        amount = amount,
        tokenTypeId = tokenTypeId,
        redemptionHolder = redemptionHolder,
        bridgeAuthority = bridgeAuthority,
        lockId = lockId
    )
}
