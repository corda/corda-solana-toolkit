package com.r3.corda.lib.solana.bridging.token.flows

import net.corda.node.utilities.solana.SolanaUtils
import net.corda.testing.solana.SolanaTestValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.Signer
import software.sava.core.accounts.token.TokenAccount
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

class TokenAccountListenerTest {
    companion object {
        private val testValidator = SolanaTestValidator()

        private lateinit var mintAuthority: Signer
        private lateinit var owner1: Signer
        private lateinit var owner2: Signer
        private lateinit var tokenMint1: PublicKey
        private lateinit var tokenMint2: PublicKey

        @BeforeAll
        @JvmStatic
        fun start() {
            mintAuthority = SolanaUtils.randomSigner()
            owner1 = SolanaUtils.randomSigner()
            owner2 = SolanaUtils.randomSigner()
            testValidator.startAndWait()
            testValidator.accounts.airdropSol(mintAuthority.publicKey(), 10)
            testValidator.accounts.airdropSol(owner1.publicKey(), 10)
            testValidator.accounts.airdropSol(owner2.publicKey(), 10)
            tokenMint1 = testValidator.tokens.createToken(mintAuthority)
            tokenMint2 = testValidator.tokens.createToken(mintAuthority)
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            testValidator.close()
        }
    }

    private val tokenAccountListener = TokenAccountListener(testValidator.client)

    @Test
    fun `mint to new token account`() {
        val updates = listenTo(owner1.publicKey())
        val tokenAccountAddress = testValidator.tokens.createTokenAccount(owner1, tokenMint1)
        updates.assertLatestUpdate(tokenAccountAddress, tokenMint1, 0)
        testValidator.tokens.mintTo(tokenAccountAddress, tokenMint1, mintAuthority, 10_000)
        updates.assertLatestUpdate(tokenAccountAddress, tokenMint1, 10_000)
    }

    @Test
    fun transfer() {
        val owner1Token = testValidator.tokens.createTokenAccount(owner1, tokenMint1)
        val owner2Token = testValidator.tokens.createTokenAccount(owner2, tokenMint1)
        testValidator.tokens.mintTo(owner1Token, tokenMint1, mintAuthority, 1_000)

        val owner1Updates = listenTo(owner1.publicKey())
        val owner2Updates = listenTo(owner2.publicKey())
        testValidator.tokens.transfer(owner1, owner1Token, owner2Token, 400)
        owner1Updates.assertLatestUpdate(owner1Token, tokenMint1, 600) // Owner 1 balance is now 600
        owner2Updates.assertLatestUpdate(owner2Token, tokenMint1, 400)
    }

    @Test
    fun `does not receive prior updates`() {
        val tokenAccountAddress = testValidator.tokens.createTokenAccount(owner1, tokenMint1)
        val updates = listenTo(owner1.publicKey())
        updates.assertNoUpdates()
        testValidator.tokens.mintTo(tokenAccountAddress, tokenMint1, mintAuthority, 10_000)
        updates.assertLatestUpdate(tokenAccountAddress, tokenMint1, 10_000)
    }

    @Test
    fun `does not receive update for another owner`() {
        val owner1Updates = listenTo(owner1.publicKey())
        val owner2Updates = listenTo(owner2.publicKey())

        val owner1Token = testValidator.tokens.createTokenAccount(owner1, tokenMint1)
        owner2Updates.assertNoUpdates()
        owner1Updates.assertLatestUpdate(owner1Token, tokenMint1, 0)

        val owner2Token = testValidator.tokens.createTokenAccount(owner2, tokenMint1)
        owner1Updates.assertNoUpdates()
        owner2Updates.assertLatestUpdate(owner2Token, tokenMint1, 0)
    }

    @Test
    fun unsubscribe() {
        val updates = listenTo(owner1.publicKey())
        val tokenAccountAddress = testValidator.tokens.createTokenAccount(owner1, tokenMint1)
        updates.assertLatestUpdate(tokenAccountAddress, tokenMint1, 0)
        tokenAccountListener.unsubscribe(owner1.publicKey())
        testValidator.tokens.mintTo(tokenAccountAddress, tokenMint1, mintAuthority, 10_000)
        updates.assertNoUpdates()
    }

    private fun listenTo(owner: PublicKey): TokenUpdates {
        val tokenUpdates = TokenUpdates(owner)
        tokenAccountListener.listenToOwner(owner, tokenUpdates.queue::add)
        return tokenUpdates
    }

    @AfterEach
    fun close() {
        tokenAccountListener.close()
    }

    private class TokenUpdates(private val owner: PublicKey) {
        val queue = LinkedBlockingQueue<TokenAccount>()

        fun assertLatestUpdate(address: PublicKey, tokenMint: PublicKey, amount: Long) {
            val tokenAccount = queue.poll(3, SECONDS)
            checkNotNull(tokenAccount) { "Did not receive new TokenAccount update" }
            assertThat(tokenAccount.address).isEqualTo(address)
            assertThat(tokenAccount.owner).isEqualTo(owner)
            assertThat(tokenAccount.mint).isEqualTo(tokenMint)
            assertThat(tokenAccount.amount).isEqualTo(amount)
        }

        fun assertNoUpdates() {
            assertThat(queue.poll(3, SECONDS)).isNull()
        }
    }
}
