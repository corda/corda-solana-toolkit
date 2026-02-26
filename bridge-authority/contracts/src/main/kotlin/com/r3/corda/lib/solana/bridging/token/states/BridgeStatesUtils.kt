package com.r3.corda.lib.solana.bridging.token.states

import net.corda.core.solana.Pubkey

/**
 * Transforms [BridgedFungibleTokenProxy.bridgeTokenAccountBase58] to a [Pubkey].
 * @return The bridge token account as a [Pubkey]
 */
fun BridgedFungibleTokenProxy.bridgeTokenAccount(): Pubkey = Pubkey.fromBase58(bridgeTokenAccountBase58)

/**
 * Transforms [BridgedFungibleTokenProxy.mintAccountBase58] to a [Pubkey].
 * @return The mint account as a [Pubkey]
 */
fun BridgedFungibleTokenProxy.mintAccount(): Pubkey = Pubkey.fromBase58(mintAccountBase58)

/**
 * Transforms [BridgedFungibleTokenProxy.mintAuthorityBase58] to a [Pubkey].
 * @return The mint authority as a [Pubkey]
 */
fun BridgedFungibleTokenProxy.mintAuthority(): Pubkey = Pubkey.fromBase58(mintAuthorityBase58)

/**
 * Transforms [FungibleTokenBurnReceipt.redemptionTokenAccountBase58] to a [Pubkey].
 * @return The redemption token account as a [Pubkey]
 */
fun FungibleTokenBurnReceipt.redemptionTokenAccount(): Pubkey = Pubkey.fromBase58(redemptionTokenAccountBase58)

/**
 * Transforms [FungibleTokenBurnReceipt.redemptionWalletAccountBase58] to a [Pubkey].
 * @return The redemption wallet account as a [Pubkey]
 */
fun FungibleTokenBurnReceipt.redemptionWalletAccount(): Pubkey = Pubkey.fromBase58(redemptionWalletAccountBase58)

/**
 * Transforms [FungibleTokenBurnReceipt.mintAccountBase58] to a [Pubkey].
 * @return The mint account as a [Pubkey]
 */
fun FungibleTokenBurnReceipt.mintAccount(): Pubkey = Pubkey.fromBase58(mintAccountBase58)
