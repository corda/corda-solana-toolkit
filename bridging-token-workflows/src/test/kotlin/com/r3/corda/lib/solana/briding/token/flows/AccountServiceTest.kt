package com.r3.corda.lib.solana.briding.token.flows

import com.lmax.solana4j.client.api.AccountInfo
import com.lmax.solana4j.client.api.Blockhash
import com.lmax.solana4j.client.api.SolanaClientResponse
import com.lmax.solana4j.client.api.TransactionResponse
import com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClient
import com.lmax.solana4j.encoding.SolanaEncoding
import com.r3.corda.lib.solana.bridging.token.flows.AccountService
import com.r3.corda.lib.solana.bridging.token.flows.toPublicKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import net.corda.solana.notary.common.Signer
import net.corda.solana.notary.common.rpc.DefaultRpcParams
import net.corda.solana.notary.common.rpc.sendAndConfirm
import net.corda.solana.notary.common.rpc.serialiseToTransaction
import net.corda.solana.sdk.instruction.Pubkey
import net.corda.solana.sdk.internal.Token2022
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AccountServiceTest {
    private val mint = Pubkey.fromBase58("4Nd1mYQKMnHkBc7FAuoRdNff7kwh28ykVZCENKxw7d9X").toPublicKey()
    private val owner = Pubkey.fromBase58("7z7N2fHcQ6FqLwV8pK7Kqs6QZtqv4sZgQ8Q3jL2xG4nQ").toPublicKey()
    private val feeSigner = mockk<Signer> {
        every { account } returns SolanaEncoding.account("9w9kL7JH2Brw39i2e3D9o1bT2PukUq3FkSmQnG8Yx1aP")
    }
    private val blockhash = mockk<Blockhash> {
        every { blockhashBase58 } returns "EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N"
        every { lastValidBlockHeight } returns 123
    }
    private val latestBlockhashResponse = mockk<SolanaClientResponse<Blockhash>> {
        every { error } returns null
        every { response } returns blockhash
    }
    private val accountInfoResponse = mockk<SolanaClientResponse<AccountInfo>> {
        every { error } returns null
        every { response } returns null
    }
    private val rpcClient = mockk<SolanaJsonRpcClient> {
        every { getLatestBlockhash(any()) } returns latestBlockhashResponse
        every { getAccountInfo(any(), any()) } returns accountInfoResponse
    }
    private val service: AccountService = AccountService(rpcClient, feeSigner, Token2022.PROGRAM_ID.toPublicKey())

    @BeforeEach
    fun setUp() { // Mocking an extension function is a bit more convoluted:
        mockkStatic("net.corda.solana.notary.common.rpc.SolanaApiExt")
        mockkStatic("net.corda.solana.notary.common.rpc.Solana4jUtilsKt")
        every {
            serialiseToTransaction(any(), feeSigner, emptyList(), blockhash, any())
        } returns "SERIALISED_TX"
    }

    // This test also covers situation when ATA was created in the meantime,
    // after getAccountInfo indicated the account doesn't exists
    @Test
    fun `createAta calls Solana RPC with correct transaction`() {
        val sendAndConfirmResponse = mockk<TransactionResponse> {
            every { slot } returns 123
        }
        every {
            rpcClient.sendAndConfirm(
                eq("SERIALISED_TX"),
                eq(123),
                eq(DefaultRpcParams())
            )
        } returns sendAndConfirmResponse

        service.createAta(mint, owner)

        verifyOrder {
            rpcClient.getAccountInfo(any(), any())
            rpcClient.getLatestBlockhash(DefaultRpcParams())
            rpcClient.sendAndConfirm("SERIALISED_TX", 123, DefaultRpcParams())
        }
    }

    @Test
    fun `createAta detects ATA already exists and doesn't create it`() {
        every { accountInfoResponse.response } returns mockk<AccountInfo>(relaxed = true)

        service.createAta(mint, owner)

        verifyOrder {
            rpcClient.getAccountInfo(any(), any())
        }
        verify(exactly = 0) {
            rpcClient.getLatestBlockhash(DefaultRpcParams())
            rpcClient.sendAndConfirm("SERIALISED_TX", any(), any())
        }
    }
}
