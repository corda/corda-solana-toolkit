package com.r3.corda.lib.solana.bridging.token.states

import net.corda.core.solana.Pubkey

/**
 * Returns [BridgedFungibleTokenProxy.bridgeTokenAccountBase58] as a [Pubkey].
 */
val BridgedFungibleTokenProxy.bridgeTokenAccount: Pubkey
    get() = Pubkey.fromBase58(bridgeTokenAccountBase58)

/**
 * Returns [BridgedFungibleTokenProxy.mintAccountBase58] to a [Pubkey].
 */
val BridgedFungibleTokenProxy.mintAccount: Pubkey
    get() = Pubkey.fromBase58(mintAccountBase58)

/**
 * Returns [BridgedFungibleTokenProxy.mintAuthorityBase58] as a [Pubkey].
 */
val BridgedFungibleTokenProxy.mintAuthority: Pubkey
    get() = Pubkey.fromBase58(mintAuthorityBase58)

/**
 * Returns [FungibleTokenBurnReceipt.redemptionTokenAccountBase58] as a [Pubkey].
 */
val FungibleTokenBurnReceipt.redemptionTokenAccount: Pubkey
    get() = Pubkey.fromBase58(redemptionTokenAccountBase58)

/**
 * Returns [FungibleTokenBurnReceipt.redemptionWalletAccountBase58] as a [Pubkey].
 */
val FungibleTokenBurnReceipt.redemptionWalletAccount: Pubkey
    get() = Pubkey.fromBase58(redemptionWalletAccountBase58)

/**
 * Returns [FungibleTokenBurnReceipt.mintAccountBase58] as a [Pubkey].
 */
val FungibleTokenBurnReceipt.mintAccount: Pubkey
    get() = Pubkey.fromBase58(mintAccountBase58)
