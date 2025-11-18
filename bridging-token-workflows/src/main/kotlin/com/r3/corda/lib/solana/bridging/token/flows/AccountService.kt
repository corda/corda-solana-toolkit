package com.r3.corda.lib.solana.bridging.token.flows

import com.github.benmanes.caffeine.cache.Caffeine
import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClient
import com.lmax.solana4j.encoding.SolanaEncoding
import com.lmax.solana4j.programs.AssociatedTokenProgram
import net.corda.solana.notary.common.Signer
import net.corda.solana.notary.common.rpc.SolanaException
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.notary.common.rpc.sendAndConfirm
import net.corda.solana.notary.common.rpc.serialiseToTransaction
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Manages creation of Solana ATA account on the fly.
 * @param client The Solana RPC client.
 * @param feePayer The signer to pay the transaction fee.
 */
class AccountService(
    private val client: SolanaJsonRpcClient,
    private val feePayer: Signer,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AccountService::class.java)
    }

    // Indicates if for a pair of mint and owner a relevant ATA is created on chain
    private val existingAtaCache = Caffeine
        .newBuilder()
        .maximumSize(10_000)
        .build<Pair<PublicKey, PublicKey>, Unit>()

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
        if (existingAtaCache.getIfPresent(cacheKey) != null) {
            return // ATA already exists
        }
        val pda = AssociatedTokenProgram.deriveAddress(ownerAccount, tokenProgramId, mintAccount)
        try {
            if (requireAtaMatches(pda.address(), mintAccount, ownerAccount)) {
                existingAtaCache.put(cacheKey, Unit)
                return // ATA already exists but was not cached
            }
        } catch (e: IllegalStateException) {
            throw SolanaException("Error while checking if ATA exists or non-ATA found", e)
        }
        val instruction = AssociatedTokenProgram.createAssociatedTokenAccount(
            pda,
            mintAccount,
            ownerAccount,
            feePayer.account,
            tokenProgramId,
            true,
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
            existingAtaCache.put(cacheKey, Unit)
        } catch (e: Exception) {
            logger.error("Exception while creating ATA owner=$ownerAccount, mint=$mintAccount, pda=$pda", e)
            throw e
        }
    }

    private fun requireAtaMatches(
        account: PublicKey,
        expectedMintAccount: PublicKey,
        expectedWalletAccount: PublicKey,
    ): Boolean {
        val clientResponse = client.getAccountInfo(account.base58(), rpcParams)
        val response = clientResponse.response ?: return false

        val encoded = response.data?.accountInfoEncoded ?: return false
        check(encoded.size >= 2) { "Missing encoded account data" }
        check(encoded[1].equals("base64", ignoreCase = true)) { "Unsupported encoding: ${encoded[1]}" }

        val binaryData = Base64.getDecoder().decode(encoded.first())
        check(binaryData.size >= 64) { "Account data too short to be a token account" }

        val mintAccount = SolanaEncoding.account(binaryData.copyOfRange(0, 32))
        val walletAccount = SolanaEncoding.account(binaryData.copyOfRange(32, 64))

        val programMatch = response.owner == tokenProgramId.base58()
        val mintMatch = mintAccount == expectedMintAccount
        val walletMatch = walletAccount == expectedWalletAccount
        check(programMatch && mintMatch && walletMatch) {
            "ATA account does not match expected info: " +
                "programMatch=$programMatch, mintMatch=$mintMatch, walletMatch=$walletMatch"
        }
        return true
    }
}
