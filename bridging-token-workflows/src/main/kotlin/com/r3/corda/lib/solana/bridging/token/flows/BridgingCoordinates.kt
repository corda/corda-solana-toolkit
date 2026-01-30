package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
import com.r3.corda.lib.solana.core.TokenManagement
import com.r3.corda.lib.solana.core.TokenProgram
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.identity.Party
import net.corda.solana.sdk.instruction.Pubkey

/**
 * Holds the necessary metadata to bridge a Corda token to Solana Token.
 *
 * @property mintAccount Token **mint** public key on Solana (the asset definition).
 * @property mintAuthority Public key that is authorized to mint for [mintAccount] on Solana
 * (address controlled by the bridge).
 * @property mintWalletAccount Token **wallet** public key that should receive the minted tokens on Solana.
 */
data class BridgingCoordinates(
    val mintAccount: Pubkey,
    val mintAuthority: Pubkey,
    val mintWalletAccount: Pubkey,
) {
    /**
     * Creates an unminted [BridgedFungibleTokenProxy] with ATA destination to bridge to.
     * Converts the Fungible Token amount to Solana token amount in 1:1 ration.
     * @param token the source of amount to bridge
     */
    fun toBridgedFungibleTokenProxy(token: FungibleToken, bridgeAuthority: Party): BridgedFungibleTokenProxy {
        val tokenAccount = TokenManagement
            .getAssociatedTokenAccountAddress(
                mintAccount.toPublicKey(),
                mintWalletAccount.toPublicKey(),
                TokenProgram.valueOf(tokenProgramId)
            )
            .toPubkey()
        return BridgedFungibleTokenProxy(
            amount = token.amount.quantity,
            mintAccount = this.mintAccount,
            mintAuthority = this.mintAuthority,
            bridgeTokenAccount = tokenAccount,
            bridgeAuthority = bridgeAuthority,
        )
    }
}
