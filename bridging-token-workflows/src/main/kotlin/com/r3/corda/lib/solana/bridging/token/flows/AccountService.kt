package com.r3.corda.lib.solana.bridging.token.flows

import com.lmax.solana4j.Solana
import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.api.TransactionInstruction
import com.lmax.solana4j.client.api.Blockhash
import com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClient
import com.lmax.solana4j.programs.AssociatedTokenProgram
import net.corda.solana.notary.common.Signer
import net.corda.solana.notary.common.rpc.DefaultRpcParams
import net.corda.solana.notary.common.rpc.SolanaException
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.notary.common.rpc.sendAndConfirm
import net.corda.solana.notary.common.rpc.serialiseToTransaction
import org.slf4j.LoggerFactory
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

/**
 * Manages creation of Solana ATA account on the fly.
 * @param client The Solana RPC client.
 * @param feePayer The signer to pay the transaction fee.
 */
class AccountService(
    private val client: SolanaJsonRpcClient,
    private val feePayer: Signer,
    private val programAccount: PublicKey,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(AccountService::class.java)
        private val RPC_PARAMS = DefaultRpcParams()
    }

    /**
     * Creates an associated token account (ATA) for the given SPL token [mintAccount] and [ownerAccount]
     * if one does not already exist.
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
     * @throws com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClientException if the underlying RPC calls
     *         fail after exhausting retry attempts.
     */
    fun createAta(mintAccount: PublicKey, ownerAccount: PublicKey) {
        val pda = AssociatedTokenProgram.deriveAddress(ownerAccount, programAccount, mintAccount)
        // TODO use cache first
        if (isAtaPresent(pda.address())) {
            return // no need to create ATA
        }
        val instruction = AssociatedTokenProgram.createAssociatedTokenAccount(
            pda,
            mintAccount,
            ownerAccount,
            feePayer.account,
            programAccount,
            true,
        )
        val blockhash = client.getLatestBlockhash(RPC_PARAMS).checkResponse("getLatestBlockhash")!!
        val solanaTxBlob = serialiseSolanaTx(instruction, emptyList(), blockhash)
        try {
            val result = client.sendAndConfirm(solanaTxBlob, blockhash.lastValidBlockHeight, RPC_PARAMS)
            logger.info(
                "ATA created successfully, slot=${result.slot}, owner=$ownerAccount, mint=$mintAccount, pda=$pda."
            )
            // TODO add to cache
        } catch (e: Exception) {
            logger.error("Exception while creating ATA owner=$ownerAccount, mint=$mintAccount, pda=$pda", e)
            throw e
        }
    }

    private fun serialiseSolanaTx(
        instruction: TransactionInstruction,
        additionalSigners: Collection<Signer>,
        blockhash: Blockhash,
    ): String {
        val buffer = ByteBuffer.allocate(Solana.MAX_MESSAGE_SIZE)
        return try {
            serialiseToTransaction(
                { txBuilder ->
                    txBuilder.append(instruction)
                },
                feePayer,
                additionalSigners,
                blockhash,
                buffer
            )
        } catch (_: BufferOverflowException) {
            throw SolanaException("Transaction is too big for the Bridge Authority.")
        }
    }

    private fun isAtaPresent(publicKey: PublicKey): Boolean {
        val response = client
            .getAccountInfo(publicKey.base58(), DefaultRpcParams())
        return response.error == null && response.response != null
    }
}
