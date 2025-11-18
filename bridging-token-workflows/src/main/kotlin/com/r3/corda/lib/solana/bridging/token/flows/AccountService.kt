package com.r3.corda.lib.solana.bridging.token.flows

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClient
import com.lmax.solana4j.programs.AssociatedTokenProgram
import net.corda.solana.notary.common.Signer
import net.corda.solana.notary.common.rpc.SolanaTransactionException
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.notary.common.rpc.sendAndConfirm
import net.corda.solana.notary.common.rpc.serialiseToTransaction
import org.slf4j.LoggerFactory

/**
 * Manages creation of Solana ATA account on the fly.
 * @param client The Solana RPC client.
 * @param feePayer The signer to pay the transaction fee.
 * @param existingAtaCache For caching results
 */
class AccountService(
    private val client: SolanaJsonRpcClient,
    private val feePayer: Signer,
    private val existingAtaCache: AtaCache = BoundedAtaCache(),
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AccountService::class.java)
    }

    /**
     * Creates an associated token account (ATA) for the given SPL token [mintAccount] and [ownerAccount]
     * if one does not already exist. The method is idempotent and may be rerun in case f a flow restart.
     *
     * The transaction is built and signed using this service's fee payer and submitted with preflight
     * checks enabled. If submission fails due to a stale blockhash, the creation is retried with a new
     * blockhash.
     *
     * @param mintAccount  The SPL token mint for which the associated token account is created.
     * @param ownerAccount The owner of the associated token account.
     *
     * @throws net.corda.solana.notary.common.rpc.SolanaException if the transaction cannot be constructed
     *         or is too large.
     * @throws com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClientException if the underlying RPC calls fail.
     */
    fun createAta(mintAccount: PublicKey, ownerAccount: PublicKey) {
        val cacheKey = Pair(mintAccount, ownerAccount)
        if (existingAtaCache.contains(cacheKey)) {
            return // ATA already exists
        }
        val pda = AssociatedTokenProgram.deriveAddress(ownerAccount, tokenProgramId, mintAccount)
        val instruction = AssociatedTokenProgram.createAssociatedTokenAccount(
            pda,
            mintAccount,
            ownerAccount,
            feePayer.account,
            tokenProgramId,
            false,
        )
        val blockhash = client.getLatestBlockhash(rpcParams).checkResponse("getLatestBlockhash")!!
        val solanaTxBlob = serialiseToTransaction(
            { txBuilder ->
                txBuilder.append(instruction)
            },
            feePayer,
            emptyList(),
            blockhash,
        )
        try {
            val result = client.sendAndConfirm(solanaTxBlob, blockhash.lastValidBlockHeight, rpcParams)
            logger.info(
                "ATA created successfully, slot=${result.slot}, owner=$ownerAccount, mint=$mintAccount, pda=$pda."
            )
            existingAtaCache.put(cacheKey)
        } catch (e: SolanaTransactionException) {
            val errors = e.error
            if (errors is Map<*, *>) {
                val error = errors["InstructionError"]
                if (error is List<*> && error.contains("IllegalOwner")) {
                    existingAtaCache.put(cacheKey)
                    return // expected error when ATA already exists
                }
            }
            logger.error("Exception while creating ATA owner=$ownerAccount, mint=$mintAccount, pda=$pda", e)
            throw e
        }
    }
}

interface AtaCache {
    fun put(key: Pair<PublicKey, PublicKey>)

    fun contains(key: Pair<PublicKey, PublicKey>): Boolean
}

class BoundedAtaCache : AtaCache {
    private val cache: Cache<Pair<PublicKey, PublicKey>, Unit> = Caffeine
        .newBuilder()
        .maximumSize(10_000)
        .build()

    override fun put(key: Pair<PublicKey, PublicKey>) {
        cache.put(key, Unit)
    }

    override fun contains(key: Pair<PublicKey, PublicKey>): Boolean {
        return cache.getIfPresent(key) != null
    }
}
