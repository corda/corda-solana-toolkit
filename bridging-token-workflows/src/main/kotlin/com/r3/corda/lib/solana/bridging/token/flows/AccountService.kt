package com.r3.corda.lib.solana.bridging.token.flows

import com.lmax.solana4j.Solana
import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.api.TransactionInstruction
import com.lmax.solana4j.client.api.Blockhash
import com.lmax.solana4j.client.api.SimulateTransactionResponse
import com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClient
import com.lmax.solana4j.programs.AssociatedTokenProgram
import net.corda.solana.notary.common.Signer
import net.corda.solana.notary.common.rpc.DefaultRpcParams
import net.corda.solana.notary.common.rpc.SolanaException
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.notary.common.rpc.sendAndConfirm
import net.corda.solana.notary.common.rpc.serialiseToTransaction
import net.corda.solana.notary.common.rpc.simulate
import net.corda.solana.sdk.instruction.Pubkey
import net.corda.solana.sdk.internal.Token2022
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

/**
 * Manages creation of Solana ATA account on the fly.
 * @param client The Solana RPC client.
 * @param feePayer The signer to pay the transaction fee.
 */
class AccountService(private val client: SolanaJsonRpcClient, private val feePayer: Signer) {
    companion object {
        val RPC_PARAMS = DefaultRpcParams(skipPreflight = true)
        private val PROGRAM_ACCOUNT: PublicKey = Token2022.PROGRAM_ID.toPublicKey()
    }

    // This is only accessed by a single thread so safe to re-use.
    private val buffer = ByteBuffer.allocate(Solana.MAX_MESSAGE_SIZE)

    /**
     * Creates an associated token account (ATA) for the given SPL token [mint] and [owner] if the ATA doesn't exist.
     * The transaction is built and signed using this service's fee payer, with first simulating the result.
     * This method will retry creation attempts if the ATA cannot be created due to connection issues.
     *
     * @param mint The SPL token mint for which the associated token account is created.
     * @param owner The owner of the associated token account.
     *
     * @throws net.corda.solana.notary.common.rpc.SolanaException if the transaction cannot be constructed
     * or is too large.
     * @throws com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClientException if the underlying RPC calls fail after
     * exhausting retry attempts
     */
    fun createAta(mint: Pubkey, owner: Pubkey) {
        val mintKey = mint.toPublicKey()
        val ownerKey = owner.toPublicKey()
        val pda = AssociatedTokenProgram.deriveAddress(ownerKey, PROGRAM_ACCOUNT, mintKey)
        // TODO add cache
        if (isAtaPresent(pda.address())) {
            return // no need to create ATA
        }
        val payerKey = feePayer.account
        val instruction = AssociatedTokenProgram.createAssociatedTokenAccount(
            pda,
            mintKey,
            ownerKey,
            payerKey,
            PROGRAM_ACCOUNT,
            true,
        )
        val (earlyResult, solanaTxBlob, blockhash) = simulateSolanaTx(instruction)
        if (earlyResult?.err != null) {
            return // TODO check if error relates to account already in use
        }
        client.sendAndConfirm(solanaTxBlob, blockhash.lastValidBlockHeight, RPC_PARAMS)
    }

    private fun simulateSolanaTx(instruction: TransactionInstruction):
        Triple<SimulateTransactionResponse?, String, Blockhash> {
        // TODO error handling
        val blockhash = client.getLatestBlockhash(RPC_PARAMS).checkResponse("getLatestBlockhash")!!
        val solanaTxBlob = serialiseSolanaTx(instruction, emptyList(), blockhash)
        val result = client.simulate(solanaTxBlob, RPC_PARAMS)
        return Triple(result, solanaTxBlob, blockhash)
    }

    private fun serialiseSolanaTx(
        instruction: TransactionInstruction,
        additionalSigners: Collection<Signer>,
        blockhash: Blockhash,
    ): String {
        buffer.clear()
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
