package com.r3.corda.lib.solana.bridging.token.flows

import com.lmax.solana4j.Solana
import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.api.TransactionInstruction
import com.lmax.solana4j.client.api.Blockhash
import com.lmax.solana4j.client.api.SimulateTransactionResponse
import com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClient
import com.lmax.solana4j.encoding.SolanaEncoding
import com.lmax.solana4j.programs.AssociatedTokenProgram
import net.corda.solana.aggregator.common.RpcParams
import net.corda.solana.aggregator.common.Signer
import net.corda.solana.aggregator.common.SolanaException
import net.corda.solana.aggregator.common.checkResponse
import net.corda.solana.aggregator.common.sendAndConfirm
import net.corda.solana.aggregator.common.serialiseToTransaction
import net.corda.solana.aggregator.common.simulate
import net.corda.solana.aggregator.common.toPublicKey
import net.corda.solana.sdk.internal.Token2022
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

class AccountService(private val rpcClient: SolanaJsonRpcClient, private val feeSigner: Signer) {
    companion object {
        val RPC_PARAMS = RpcParams(skipPreflight = true)
        private val PROGRAM_ACCOUNT: PublicKey = Token2022.PROGRAM_ID.toPublicKey()
    }

    // This is only accessed by a single thread so safe to re-use.
    private val buffer = ByteBuffer.allocate(Solana.MAX_MESSAGE_SIZE)

    fun createAta(mint: String, owner: String, payer: String) {
        val instruction = createAtaInstruction(mint, owner, payer)
        val (earlyResult, solanaTxBlob, blockhash) = simulateSolanaTx(instruction)
        if (earlyResult?.err != null) {
            return // TODO check if error relates to account already in use
        }
        rpcClient.sendAndConfirm(solanaTxBlob, blockhash.lastValidBlockHeight, RPC_PARAMS)
    }

    private fun simulateSolanaTx(instruction: TransactionInstruction):
        Triple<SimulateTransactionResponse?, String, Blockhash> {
        // TODO error handling
        val blockhash = rpcClient.getLatestBlockhash(RPC_PARAMS).checkResponse("getLatestBlockhash")!!
        val solanaTxBlob = serialiseSolanaTx(instruction, emptyList(), blockhash)
        val result = rpcClient.simulate(solanaTxBlob, RPC_PARAMS)
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
                feeSigner,
                additionalSigners,
                blockhash,
                buffer
            )
        } catch (_: BufferOverflowException) {
            throw SolanaException("Transaction is too big for the Bridge Authority.")
        }
    }

    private fun createAtaInstruction(
        mint: String,
        owner: String,
        payer: String,
    ): TransactionInstruction {
        val mintKey: PublicKey = SolanaEncoding.account(mint)
        val ownerKey: PublicKey = SolanaEncoding.account(owner)
        val payerKey: PublicKey = SolanaEncoding.account(payer)

        val pda = AssociatedTokenProgram.deriveAddress(ownerKey, PROGRAM_ACCOUNT, mintKey)

        return AssociatedTokenProgram.createAssociatedTokenAccount(
            pda,
            mintKey,
            ownerKey,
            payerKey,
            PROGRAM_ACCOUNT,
            true,
        )
    }
}
