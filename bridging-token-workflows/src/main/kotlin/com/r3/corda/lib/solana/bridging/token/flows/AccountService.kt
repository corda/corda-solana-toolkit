package com.r3.corda.lib.solana.bridging.token.flows

import com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClient
import net.corda.solana.aggregator.common.Signer
import net.corda.solana.sdk.instruction.Pubkey

/**
 * Manages creation of Solana ATA account on the fly.
 * @param client The Solana RPC client.
 * @param feePayer The signer to pay the transaction fee.
 */
class AccountService(private val client: SolanaJsonRpcClient, private val feePayer: Signer) {
    /**
     * Creates an associated token account (ATA) for the given SPL token [mint] and [owner] if the ATA doesn't exist.
     * The transaction is built and signed using this service's fee payer, with first simulating the result.
     * This method will retry creation attempts if the ATA cannot be created due to connection issues.
     *
     * @param mint  The SPL token mint for which the associated token account is created.
     * @param owner The owner of the associated token account.
     *
     * @throws net.corda.solana.aggregator.common.SolanaException if the transaction cannot be constructed
     * or is too large.
     * @throws com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClientException if the underlying RPC calls fail after
     * exhausting retry attempts
     */
    @Suppress("UnusedParameter")
    fun createAta(mint: Pubkey, owner: Pubkey) = Unit // TODO to be implemented
}
