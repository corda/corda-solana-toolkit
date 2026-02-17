package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.Amount
import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.identity.Party
import net.corda.core.solana.Pubkey
import software.sava.core.accounts.SolanaAccounts
import software.sava.solana.programs.token.AssociatedTokenProgram

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
    val mintDecimals: Int,
) {
    /**
     * Creates an unminted [BridgedFungibleTokenProxy] with ATA destination to bridge to.
     * Converts the Fungible Token amount to Solana token amount in 1:1 ration.
     * @param token the source of amount to bridge
     */
    fun toBridgedFungibleTokenProxy(token: FungibleToken, bridgeAuthority: Party): BridgedFungibleTokenProxy {
        val tokenAccount = AssociatedTokenProgram
            .findATA(
                SolanaAccounts.MAIN_NET,
                this.mintWalletAccount.toPublicKey(),
                tokenProgramId,
                this.mintAccount.toPublicKey(),
            ).publicKey()
            .toPubkey()
        return BridgedFungibleTokenProxy(
            cordaAmount = Amount(token.amount.quantity, token.tokenType.fractionDigits),
            solanaAmount = Amount(0, mintDecimals), // TODO conversion
            mintAccount = this.mintAccount,
            mintAuthority = this.mintAuthority,
            bridgeTokenAccount = tokenAccount,
            bridgeAuthority = bridgeAuthority,
        )
    }
}
