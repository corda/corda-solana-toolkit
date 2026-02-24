package com.r3.corda.lib.solana.core

import com.r3.corda.lib.solana.core.AccountManagement.Companion.LAMPORTS_PER_SOL
import com.r3.corda.lib.solana.core.AccountManagement.Companion.toLamports
import com.r3.corda.lib.solana.testing.SolanaTestClass
import com.r3.corda.lib.solana.testing.SolanaTestValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import software.sava.core.accounts.PublicKey
import software.sava.rpc.json.http.client.SolanaRpcClient

@SolanaTestClass
class AccountManagementTest {
    @Test
    fun toLamports() {
        assertThat(1L.toLamports()).isEqualTo(LAMPORTS_PER_SOL)
        assertThat(10L.toLamports()).isEqualTo(10 * LAMPORTS_PER_SOL)
        assertThat(0.5.toBigDecimal().toLamports()).isEqualTo(LAMPORTS_PER_SOL / 2)
    }

    @Test
    fun `funding and defunding`(testValidator: SolanaTestValidator) {
        val account1 = SolanaUtils.randomSigner()
        testValidator.accounts().airdropSol(account1.publicKey(), 10)
        assertThat(testValidator.getBalance(account1.publicKey())).isEqualTo(10L.toLamports())

        val account2 = SolanaUtils.randomSigner()
        testValidator.accounts().transferLamports(account1, account2.publicKey(), LAMPORTS_PER_SOL)
        assertThat(testValidator.getBalance(account2.publicKey())).isEqualTo(LAMPORTS_PER_SOL)

        testValidator.accounts().close(account2, account1.publicKey())
        assertThat(testValidator.getBalance(account2.publicKey())).isZero
    }

    @Test
    fun getAccountInfo(testValidator: SolanaTestValidator) {
        val account = SolanaUtils.randomSigner()
        assertThat(testValidator.accounts().getAccountInfo(account.publicKey())).isNull()
        testValidator.accounts().airdropLamports(account.publicKey(), 10_000_000)
        val accountInfo = testValidator.accounts().getAccountInfo(account.publicKey())
        assertThat(accountInfo).isNotNull
        assertThat(accountInfo!!.lamports()).isEqualTo(10_000_000)
    }

    private fun SolanaTestValidator.getBalance(account: PublicKey): Long {
        return client().call(SolanaRpcClient::getBalance, account).lamports
    }
}
