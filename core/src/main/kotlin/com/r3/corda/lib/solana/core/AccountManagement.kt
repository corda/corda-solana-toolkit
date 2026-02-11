package com.r3.corda.lib.solana.core

import com.r3.corda.lib.solana.core.SolanaClient.Companion.waitForConfirmation
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.Signer
import software.sava.rpc.json.http.client.SolanaRpcClient
import software.sava.rpc.json.http.response.AccountInfo
import java.math.BigDecimal
import java.util.function.BiFunction

class AccountManagement(val client: SolanaClient) {
    companion object {
        const val LAMPORTS_PER_SOL: Long = 1_000_000_000

        /**
         * Fee for a transaction with one signature. Each further signature adds the same fee again.
         */
        const val BASE_TRANSACTION_FEE: Int = 5000

        private val LAMPORTS_PER_SOL_BD = LAMPORTS_PER_SOL.toBigDecimal()

        /**
         * Convert the given number of SOL to lamports.
         */
        @JvmStatic
        fun BigDecimal.toLamports(): Long = (this * LAMPORTS_PER_SOL_BD).longValueExact()

        /**
         * Convert the given number of SOL to lamports.
         */
        @JvmStatic
        fun Long.toLamports(): Long = Math.multiplyExact(this, LAMPORTS_PER_SOL)
    }

    /**
     * Airdrop the given amount of SOL to the account.
     */
    fun airdropSol(account: PublicKey, amount: BigDecimal) {
        airdropLamports(account, amount.toLamports())
    }

    /**
     * Airdrop the given amount of SOL to the account.
     */
    fun airdropSol(account: PublicKey, amount: Long) {
        airdropLamports(account, amount.toLamports())
    }

    /**
     * Airdrop the given amount of lamports to the account.
     */
    fun airdropLamports(account: PublicKey, amount: Long) {
        val signature = client.call(SolanaRpcClient::requestAirdrop, account, amount)
        client.asyncConfirm(signature).waitForConfirmation()
    }

    /**
     * Transfer the given amount of SOL.
     */
    fun transferSol(from: Signer, to: PublicKey, amount: BigDecimal) {
        transferLamports(from, to, amount.toLamports())
    }

    /**
     * Transfer the given amount of SOL.
     */
    fun transferSol(from: Signer, to: PublicKey, amount: Long) {
        transferLamports(from, to, amount.toLamports())
    }

    /**
     * Transfer the given amount of lamports.
     */
    fun transferLamports(from: Signer, to: PublicKey, amount: Long) {
        client.sendAndConfirm(
            { it.createTransaction(it.transferSolLamports(to, amount)) },
            from
        )
    }

    fun getAccountInfo(account: PublicKey): AccountInfo<ByteArray>? {
        return client.call(SolanaRpcClient::getAccountInfo, account).takeIf { it.lamports != 0L }
    }

    fun <T> getAccountInfo(account: PublicKey, factory: BiFunction<PublicKey, ByteArray, T>): AccountInfo<T>? {
        return client.call(SolanaRpcClient::getAccountInfo, account, factory).takeIf { it.lamports != 0L }
    }

    /**
     * Close the given account, which will transfer all remaining lamports in the account back to the given destination
     * (and thus mark the account for deletion).
     *
     * @param account Account to be defunded and deleted. This needs to sign the transaction.
     * @param destination Account where the funds should go.
     * @return The amount of lamports were transfered to the destination.
     */
    fun close(account: Signer, destination: PublicKey): Long {
        val balance = client.call(SolanaRpcClient::getBalance, account.publicKey()).amount()
        val transferAmount = (balance - BASE_TRANSACTION_FEE.toBigInteger()).longValueExact()
        transferLamports(account, destination, transferAmount)
        return transferAmount
    }
}
