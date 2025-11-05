package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.MintState
import com.r3.corda.lib.solana.bridging.token.states.RedeemState
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.identity.Party
import net.corda.solana.sdk.instruction.Pubkey
import java.util.UUID

/**
 * Holds the necessary metadata to bridge a Corda token to Solana Token.
 *
 * @property originalOwner The Corda identity that owns the original fungible token being bridged.
 * @property tokenTypeId The unique identifier for the type of token being bridged.
 * @property mint Token **mint** public key on Solana (the asset definition).
 * @property mintAuthority Public key that is authorized to mint for [mint] on Solana
 * (address controlled by the bridge).
 * @property mintDestination Token **wallet** public key that should receive the minted tokens on Solana.
 * @property bridgeRedemptionWallet Public key that will own the redemption wallet on Solana
 * and will be able to burn them during the redemption.
 */
data class BridgingCoordinates(
    val originalOwner: Party,
    val tokenTypeId: String,
    val mint: Pubkey,
    val mintAuthority: Pubkey,
    val mintDestination: Pubkey,
    val bridgeRedemptionWallet: Pubkey,
) {
    /**
     * Creates an unminted [MintState].
     * Converts the Fungible Token amount to Solana token amount in 1:1 ration.
     * @param token the source of amount to bridge
     */
    fun toMintState(token: FungibleToken, bridgeAuthority: Party) =
        MintState(
            originalOwner = this.originalOwner,
            amount = token.amount.quantity,
            mint = this.mint,
            mintAuthority = this.mintAuthority,
            mintDestination = this.mintDestination,
            bridgeAuthority = bridgeAuthority,
        )

    /**
     * Creates a [RedeemState].
     * @param burnSource the Solana public key where the redeemed tokens will be sent
     * @param amount the amount of tokens to redeem*/
    fun toRedeemState(
        burnSource: Pubkey,
        amount: Long,
        bridgeAuthority: Party,
        lockId: UUID,
    ) = RedeemState(
        burnSource = burnSource,
        bridgeRedemptionWallet = bridgeRedemptionWallet,
        mint = mint,
        amount = amount,
        tokenTypeId = tokenTypeId,
        originalOwner = originalOwner,
        bridgingAuthority = bridgeAuthority,
        lockId = lockId
    )
}
