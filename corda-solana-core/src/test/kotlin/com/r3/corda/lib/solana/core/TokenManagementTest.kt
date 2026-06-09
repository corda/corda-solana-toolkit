package com.r3.corda.lib.solana.core

import com.r3.corda.lib.solana.core.tokens.TokenManagement
import com.r3.corda.lib.solana.core.tokens.TokenProgram
import com.r3.corda.lib.solana.testing.SolanaTestClass
import com.r3.corda.lib.solana.testing.SolanaTestValidator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.meta.AccountMeta.createInvoked
import software.sava.core.accounts.token.Mint
import software.sava.core.tx.Instruction
import software.sava.rpc.json.http.client.SolanaRpcClient
import software.sava.solana.programs.clients.NativeProgramAccountClient
import software.sava.solana.programs.token.TokenProgram.initializeMint2

@SolanaTestClass(waitForReadiness = false)
class TokenManagementTest {
    companion object {
        private val mintAuthority = SolanaUtils.randomSigner()
        private val owner1 = SolanaUtils.randomSigner()
        private val owner2 = SolanaUtils.randomSigner()

        private lateinit var testValidator: SolanaTestValidator
        private lateinit var tokenManagement: TokenManagement

        @BeforeAll
        @JvmStatic
        fun beforeAll(testValidator: SolanaTestValidator) {
            testValidator.accounts().airdropSol(mintAuthority.publicKey(), 10)
            testValidator.accounts().airdropSol(owner1.publicKey(), 10)
            testValidator.accounts().airdropSol(owner2.publicKey(), 10)
            this.testValidator = testValidator
            tokenManagement = testValidator.tokens()
        }
    }

    @ParameterizedTest
    @EnumSource
    fun `mint and burn tokens`(tokenProgram: TokenProgram) {
        val tokenMint = tokenManagement.createToken(mintAuthority, tokenProgram)
        val tokenAccount = tokenManagement.createTokenAccount(owner1, tokenMint)
        tokenManagement.mintTo(tokenAccount, tokenMint, mintAuthority, 100_000)

        assertThat(getTokenBalance(tokenAccount)).isEqualTo(100_000)

        tokenManagement.burn(owner1, tokenMint, tokenAccount, 50_000)
        assertThat(getTokenBalance(tokenAccount)).isEqualTo(50_000)
    }

    @ParameterizedTest
    @EnumSource
    fun `move tokens`(tokenProgram: TokenProgram) {
        val tokenMint = tokenManagement.createToken(mintAuthority, tokenProgram)
        val tokenAccount1 = tokenManagement.createTokenAccount(owner1, tokenMint)
        val tokenAccount2 = tokenManagement.createTokenAccount(owner2, tokenMint)

        tokenManagement.mintTo(tokenAccount1, tokenMint, mintAuthority, 100_000)
        assertThat(getTokenBalance(tokenAccount1)).isEqualTo(100_000)

        tokenManagement.transfer(owner1, tokenAccount1, tokenAccount2, 50_000)
        assertThat(getTokenBalance(tokenAccount1)).isEqualTo(50_000)
        assertThat(getTokenBalance(tokenAccount2)).isEqualTo(50_000)
    }

    @ParameterizedTest
    @EnumSource
    fun `move tokens to ATA`(tokenProgram: TokenProgram) {
        val tokenMint = tokenManagement.createToken(mintAuthority, tokenProgram)
        val owner1Account = tokenManagement.createTokenAccount(owner1, tokenMint)

        tokenManagement.mintTo(owner1Account, tokenMint, mintAuthority, 100_000)
        assertThat(getTokenBalance(owner1Account)).isEqualTo(100_000)

        val owner2Ata = tokenManagement.createAssociatedTokenAccount(owner2, tokenMint)
        assertThat(getTokenBalance(owner2Ata)).isZero

        tokenManagement.transfer(owner1, owner1Account, owner2Ata, 60_000)
        assertThat(getTokenBalance(owner1Account)).isEqualTo(40_000)
        assertThat(getTokenBalance(owner2Ata)).isEqualTo(60_000)
    }

    @ParameterizedTest
    @EnumSource
    fun `creating ATA is idempotent`(tokenProgram: TokenProgram) {
        val tokenMint = tokenManagement.createToken(mintAuthority, tokenProgram)
        val ata1 = tokenManagement.createAssociatedTokenAccount(owner1, tokenMint)
        testValidator.client().getBlockhashInfo(forceFetch = true)
        val ata2 = tokenManagement.createAssociatedTokenAccount(owner1, tokenMint)
        assertThat(ata1).isEqualTo(ata2)
    }

    @ParameterizedTest
    @EnumSource
    fun `getTokenProgram on externally created token`(tokenProgram: TokenProgram) {
        val tokenMint = SolanaUtils.randomSigner()
        testValidator.client().sendAndConfirm(
            {
                it.createTransaction(
                    listOf(
                        it.createAccount(tokenMint.publicKey(), Mint.BYTES.toLong(), tokenProgram),
                        initializeMint2(
                            createInvoked(tokenProgram.programId),
                            tokenMint.publicKey(),
                            2,
                            mintAuthority.publicKey(),
                            null
                        )
                    )
                )
            },
            mintAuthority,
            listOf(tokenMint)
        )
        assertThat(tokenManagement.getTokenProgram(tokenMint.publicKey())).isEqualTo(tokenProgram)
    }

    @Test
    fun `getTokenProgram on non-token account`() {
        val account = SolanaUtils.randomSigner().publicKey()
        testValidator.accounts().airdropSol(account, 10)
        assertThatIllegalArgumentException().isThrownBy {
            tokenManagement.getTokenProgram(account)
        }
    }

    private fun NativeProgramAccountClient.createAccount(
        account: PublicKey,
        size: Long,
        tokenProgram: TokenProgram,
    ): Instruction {
        val rentExemption = testValidator.client().call(SolanaRpcClient::getMinimumBalanceForRentExemption, size)
        return createAccount(account, rentExemption, size, tokenProgram.programId)
    }

    private fun getTokenBalance(tokenAccount: PublicKey): Long {
        return testValidator
            .client()
            .call(SolanaRpcClient::getTokenAccountBalance, tokenAccount)
            .amount
            .longValueExact()
    }
}
