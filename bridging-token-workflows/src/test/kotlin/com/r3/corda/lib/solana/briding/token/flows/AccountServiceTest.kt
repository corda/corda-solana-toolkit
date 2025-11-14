package com.r3.corda.lib.solana.briding.token.flows

import com.lmax.solana4j.client.api.AccountInfo
import com.lmax.solana4j.client.api.Blockhash
import com.lmax.solana4j.client.api.SimulateTransactionResponse
import com.lmax.solana4j.client.api.SolanaClientResponse
import com.lmax.solana4j.client.api.TransactionResponse
import com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClient
import com.lmax.solana4j.encoding.SolanaEncoding
import com.r3.corda.lib.solana.bridging.token.flows.AccountService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import net.corda.solana.aggregator.common.Signer
import net.corda.solana.aggregator.common.sendAndConfirm
import net.corda.solana.aggregator.common.serialiseToTransaction
import net.corda.solana.aggregator.common.simulate
import net.corda.solana.sdk.instruction.Pubkey
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AccountServiceTest {
    private val rpcClient = mockk<SolanaJsonRpcClient>()
    private lateinit var service: AccountService
    private val mint = Pubkey.fromBase58("4Nd1mYQKMnHkBc7FAuoRdNff7kwh28ykVZCENKxw7d9X")
    private val owner = Pubkey.fromBase58("7z7N2fHcQ6FqLwV8pK7Kqs6QZtqv4sZgQ8Q3jL2xG4nQ")
    private val feeSigner = mockk<Signer>()

    private val blockhash = mockk<Blockhash>()
    private val result = mockk<SolanaClientResponse<Blockhash>>()
    private val simulatedResult = mockk<SimulateTransactionResponse>(relaxed = true)
    private val getAccountInfoResult = mockk<SolanaClientResponse<AccountInfo>>()

    @BeforeEach
    fun setUp() {
        service = AccountService(rpcClient, feeSigner)
        mockkStatic("net.corda.solana.aggregator.common.Solana4jUtilsKt")
        every { feeSigner.account } returns SolanaEncoding.account("9w9kL7JH2Brw39i2e3D9o1bT2PukUq3FkSmQnG8Yx1aP")
        every { blockhash.lastValidBlockHeight } returns 123
        every { result.error } returns null
        every { result.response } returns blockhash
        every { getAccountInfoResult.error } returns null
        every { getAccountInfoResult.response } returns null
        every { rpcClient.getLatestBlockhash(any()) } returns result
        every { rpcClient.simulate(any(), any()) } returns simulatedResult
        every { rpcClient.getAccountInfo(any(), any()) } returns getAccountInfoResult
        every {
            serialiseToTransaction(any(), feeSigner, emptyList(), blockhash, any())
        } returns "SERIALISED_TX"
    }

    @Test
    fun `createAta calls Solana RPC with correct transaction`() {
        every { simulatedResult.err } returns null as Any?
        val sendAndConfirmResponse = mockk<TransactionResponse>()
        every {
            rpcClient.sendAndConfirm(
                eq("SERIALISED_TX"),
                eq(123),
                eq(AccountService.RPC_PARAMS)
            )
        } returns sendAndConfirmResponse

        service.createAta(mint, owner)

        verifyOrder {
            rpcClient.getAccountInfo(any(), any())
            rpcClient.getLatestBlockhash(AccountService.RPC_PARAMS)
            rpcClient.simulate("SERIALISED_TX", AccountService.RPC_PARAMS)
            rpcClient.sendAndConfirm("SERIALISED_TX", 123, AccountService.RPC_PARAMS)
        }
    }

    @Test
    fun `createAta detecks ATA already exists`() {
        every { getAccountInfoResult.response } returns mockk<AccountInfo>(relaxed = true)

        service.createAta(mint, owner)

        verifyOrder {
            rpcClient.getAccountInfo(any(), any())
        }
        verify(exactly = 0) {
            rpcClient.getLatestBlockhash(AccountService.RPC_PARAMS)
            rpcClient.simulate("SERIALISED_TX", AccountService.RPC_PARAMS)
            rpcClient.sendAndConfirm("SERIALISED_TX", any(), any())
        }
    }

    @Test
    fun `ATA was created after the check but before running simulate`() {
        every { simulatedResult.err } returns "Associated token account already in use"

        service.createAta(mint, owner)

        verifyOrder {
            rpcClient.getAccountInfo(any(), any())
            rpcClient.getLatestBlockhash(AccountService.RPC_PARAMS)
            rpcClient.simulate("SERIALISED_TX", AccountService.RPC_PARAMS)
        }
        verify(exactly = 0) {
            rpcClient.sendAndConfirm("SERIALISED_TX", any(), any())
        }
    }
}
