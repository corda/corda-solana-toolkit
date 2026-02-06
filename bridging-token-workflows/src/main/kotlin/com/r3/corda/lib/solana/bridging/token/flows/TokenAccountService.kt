package com.r3.corda.lib.solana.bridging.token.flows

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import net.corda.node.utilities.solana.SolanaClient
import net.corda.solana.notary.common.rpc.SolanaTransactionException
import org.slf4j.LoggerFactory
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.Signer
import software.sava.core.accounts.SolanaAccounts
import software.sava.rpc.json.http.response.IxError
import software.sava.rpc.json.http.response.TransactionError
import software.sava.solana.programs.token.AssociatedTokenProgram

/**
 * Manages creation of Solana ATA account on the fly,
 * ATA requests are cached internally in-memory to avoid unnecessary requests to Solana.
 * @param client The Solana RPC client.
 * @param feePayer The signer to pay the transaction fee.
 * @param existingAtaCache The internal results cache, exposed for testing.
 */
class TokenAccountService(
    private val client: SolanaClient,
    private val feePayer: Signer,
    private val existingAtaCache: ExistingAtaCache = BoundedExistingAtaCache(), // configurable for testing
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TokenAccountService::class.java)
    }

    /**
     * Optimistically attempts to create an associated token account (ATA) for the given SPL token [mintAccount],
     * [ownerAccount] and Token2022. The method is idempotent and may be rerun in case a flow restart.
     *
     * The transaction is built and signed using this service's fee payer and submitted with preflight
     * checks enabled. If submission fails due to a stale blockhash, the creation is retried with a new blockhash.
     *
     * @param mintAccount  The SPL token mint for which the associated token account is created.
     * @param ownerAccount The owner of the associated token account.
     *
     * @throws net.corda.solana.notary.common.rpc.SolanaException if the transaction cannot be constructed
     *         or is too large.
     */
    fun createAta(mintAccount: PublicKey, ownerAccount: PublicKey) {
        if (existingAtaCache.contains(mintAccount, ownerAccount)) {
            return // ATA already exists
        }
        val tokenAccount = AssociatedTokenProgram
            .findATA(SolanaAccounts.MAIN_NET, ownerAccount, tokenProgramId, mintAccount)
            .publicKey()
        try {
            client.sendAndConfirm(
                {
                    it.createTransaction(
                        AssociatedTokenProgram.createATAForProgram(
                            false,
                            SolanaAccounts.MAIN_NET,
                            feePayer.publicKey(),
                            tokenAccount,
                            ownerAccount,
                            mintAccount,
                            tokenProgramId
                        )
                    )
                },
                feePayer
            )
            logger.info("ATA created successfully, owner=$ownerAccount, mint=$mintAccount, tokenAccount=$tokenAccount")
        } catch (e: SolanaTransactionException) {
            if (!doesAtaAlreadyExist(e)) {
                logger.error(
                    "Exception while creating ATA owner=$ownerAccount, mint=$mintAccount, tokenAccount=$tokenAccount",
                    e
                )
                throw e
            }
        }
        existingAtaCache.put(mintAccount, ownerAccount)
    }

    // Checks for an expected error when ATA already exists
    private fun doesAtaAlreadyExist(e: SolanaTransactionException): Boolean {
        return (e.error as? TransactionError.InstructionError)?.ixError() is IxError.IllegalOwner
    }
}

/**
 * Holds pairs of mint account and owner account that represents a relevant PDA.
 */
interface ExistingAtaCache {
    fun put(mintAccount: PublicKey, ownerAccount: PublicKey)

    fun contains(mintAccount: PublicKey, ownerAccount: PublicKey): Boolean
}

class BoundedExistingAtaCache : ExistingAtaCache {
    private val cache: Cache<Pair<PublicKey, PublicKey>, Unit> = Caffeine
        .newBuilder()
        .maximumSize(10_000)
        .build()

    override fun put(mintAccount: PublicKey, ownerAccount: PublicKey) {
        cache.put(mintAccount to ownerAccount, Unit)
    }

    override fun contains(mintAccount: PublicKey, ownerAccount: PublicKey): Boolean {
        return cache.getIfPresent(mintAccount to ownerAccount) != null
    }
}
