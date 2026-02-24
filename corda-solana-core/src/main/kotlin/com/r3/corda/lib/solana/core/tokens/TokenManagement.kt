package com.r3.corda.lib.solana.core.tokens

import com.r3.corda.lib.solana.core.SolanaClient
import com.r3.corda.lib.solana.core.SolanaUtils.randomSigner
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.Signer
import software.sava.core.accounts.SolanaAccounts
import software.sava.core.accounts.meta.AccountMeta
import software.sava.core.accounts.token.Mint
import software.sava.core.accounts.token.TokenAccount
import software.sava.core.tx.Instruction
import software.sava.rpc.json.http.client.SolanaRpcClient
import software.sava.solana.programs.clients.NativeProgramAccountClient
import software.sava.solana.programs.token.AssociatedTokenProgram
import software.sava.solana.programs.token.TokenProgram.burn
import software.sava.solana.programs.token.TokenProgram.initializeAccount3
import software.sava.solana.programs.token.TokenProgram.initializeMint2
import software.sava.solana.programs.token.TokenProgram.mintTo
import software.sava.solana.programs.token.TokenProgram.transfer
import java.util.concurrent.ConcurrentHashMap

class TokenManagement(val client: SolanaClient) {
    companion object {
        /**
         * Return the associated token account (ATA) address for the given token mint and account owner.
         */
        @JvmStatic
        @JvmOverloads
        fun getAssociatedTokenAccountAddress(
            tokenMint: PublicKey,
            accountOwner: PublicKey,
            tokenProgram: TokenProgram = TokenProgram.TOKEN,
        ): PublicKey {
            return AssociatedTokenProgram
                .findATA(SolanaAccounts.MAIN_NET, accountOwner, tokenProgram.programId, tokenMint)
                .publicKey()
        }
    }

    private val tokenProgramCache = ConcurrentHashMap<PublicKey, TokenProgram>()
    private val rentExemptionCache = ConcurrentHashMap<Long, Long>()

    /**
     * Create a token mint (token definition). The public key returned needs to be stored safely as this is required to
     * access the token definition in all other operations.
     *
     * @param payer The account paying for the token creation
     * @param tokenProgram Which token program to use for the token. Defaults to [TokenProgram.TOKEN].
     * @param mintAuthority The account who will control minting and burning of the token type. Defaults to the payer.
     * @param tokenMint Optional parameter to specify the token mint address. Defaults to a random key.
     * @param decimals Decimal positions of the token. Defaults to 6.
     * @return The public key of the token definition.
     */
    @JvmOverloads
    fun createToken(
        payer: Signer,
        tokenProgram: TokenProgram = TokenProgram.TOKEN,
        mintAuthority: PublicKey = payer.publicKey(),
        tokenMint: Signer = randomSigner(),
        decimals: Int = 6,
        freezeAuthority: PublicKey? = null,
    ): PublicKey {
        client.sendAndConfirm(
            {
                it.createTransaction(
                    listOf(
                        it.createAccount(tokenMint.publicKey(), Mint.BYTES.toLong(), tokenProgram),
                        initializeMint2(
                            tokenProgram.toInvoked(),
                            tokenMint.publicKey(),
                            decimals,
                            mintAuthority,
                            freezeAuthority
                        )
                    )
                )
            },
            payer,
            listOf(tokenMint)
        )
        tokenProgramCache[tokenMint.publicKey()] = tokenProgram
        return tokenMint.publicKey()
    }

    /**
     * Create a standard token account (i.e. not an ATA) for the given token definition.
     *
     * @param payer Account paying for the transaction.
     * @param tokenMint The token definition this account will be able to hold.
     * @param accountOwner The owner of the new token account, defaults to the payer.
     * @param tokenAccount Address for the token account, defaults to a random key.
     * @return Address of the new token account.
     */
    @JvmOverloads
    fun createTokenAccount(
        payer: Signer,
        tokenMint: PublicKey,
        accountOwner: PublicKey = payer.publicKey(),
        tokenAccount: Signer = randomSigner(),
    ): PublicKey {
        val tokenProgram = getTokenProgram(tokenMint)
        client.sendAndConfirm(
            {
                it.createTransaction(
                    listOf(
                        it.createAccount(tokenAccount.publicKey(), TokenAccount.BYTES.toLong(), tokenProgram),
                        initializeAccount3(tokenProgram.toInvoked(), tokenAccount.publicKey(), tokenMint, accountOwner)
                    )
                )
            },
            payer,
            listOf(tokenAccount)
        )
        return tokenAccount.publicKey()
    }

    /**
     * Create an associated token account (ATA) for the given token definition if it doesn't exist.
     *
     * @param payer Account paying for the transaction.
     * @param tokenMint The token definition this account will be able to hold.
     * @param accountOwner The owner of the new token account, defaults to the payer.
     * @return The deterministic address of the token account.
     */
    @JvmOverloads
    fun createAssociatedTokenAccount(
        payer: Signer,
        tokenMint: PublicKey,
        accountOwner: PublicKey = payer.publicKey(),
    ): PublicKey {
        val tokenProgram = getTokenProgram(tokenMint)
        val tokenAccount = getAssociatedTokenAccountAddress(tokenMint, accountOwner, tokenProgram)
        client.sendAndConfirm(
            {
                it.createTransaction(
                    AssociatedTokenProgram.createATAForProgram(
                        true,
                        SolanaAccounts.MAIN_NET,
                        payer.publicKey(),
                        tokenAccount,
                        accountOwner,
                        tokenMint,
                        tokenProgram.programId
                    )
                )
            },
            payer
        )
        return tokenAccount
    }

    /**
     * Mints tokens of the given type to the given token account. Will fail with an exception if the token mint does not
     * match the account, or the account cannot hold tokens.
     *
     * @param tokenAccount token account that will hold the newly minted tokens
     * @param tokenMint (token definition) to be minted
     * @param mintAuthority the mint authority authorising the minting of the new tokens.
     * @param amount The amount of tokens (as the smallest unit) to be minted
     * @param payer Account paying for the transaction, defaults to the mint authority.
     */
    @JvmOverloads
    fun mintTo(
        tokenAccount: PublicKey,
        tokenMint: PublicKey,
        mintAuthority: Signer,
        amount: Long,
        payer: Signer = mintAuthority,
    ) {
        val tokenProgram = getTokenProgram(tokenMint)
        client.sendAndConfirm(
            {
                it.createTransaction(
                    mintTo(
                        tokenProgram.toInvoked(),
                        tokenMint,
                        tokenAccount,
                        mintAuthority.publicKey(),
                        amount
                    )
                )
            },
            payer,
            listOf(mintAuthority),
        )
    }

    /**
     * Burn tokens of a given mint type
     *
     * @param owner The owner of the token account who can sign for getting rid of tokens.
     * @param tokenMint The token definition
     * @param tokenAccount the account holding the tokens to be burnt
     * @param amount The amount of tokens (in the smallest unit) to be burnt
     * @param payer Account paying for the transaction, defaults to the owner.
     */
    @JvmOverloads
    fun burn(
        owner: Signer,
        tokenMint: PublicKey,
        tokenAccount: PublicKey,
        amount: Long,
        payer: Signer = owner,
    ) {
        val tokenProgram = getTokenProgram(tokenMint)
        client.sendAndConfirm(
            {
                it.createTransaction(
                    burn(
                        tokenProgram.toInvoked(),
                        tokenMint,
                        tokenAccount,
                        owner.publicKey(),
                        amount
                    )
                )
            },
            payer,
            listOf(owner)
        )
    }

    /**
     * Transfer tokens between two token accounts.
     *
     * @param owner Owner of the source account that needs to sign the transaction.
     * @param source Token account that will provide the tokens to be transferred
     * @param destination Token account that will receive the tokens
     * @param amount The amount of tokens (in the smallest unit) to be transferred
     * @param payer Account paying for the transaction, defaults to the owner.
     *
     */
    @JvmOverloads
    fun transfer(
        owner: Signer,
        source: PublicKey,
        destination: PublicKey,
        amount: Long,
        payer: Signer = owner,
    ) {
        val tokenProgram = getTokenProgram(source)
        client.sendAndConfirm(
            {
                it.createTransaction(
                    transfer(
                        tokenProgram.toInvoked(),
                        source,
                        destination,
                        amount,
                        owner.publicKey()
                    )
                )
            },
            payer,
            listOf(owner)
        )
    }

    private fun TokenProgram.toInvoked() = AccountMeta.createInvoked(programId)

    /**
     * Returns the [TokenProgram] the given account (token mint or token account) belongs to.
     *
     * @throws IllegalArgumentException If the account is not related to either token program.
     */
    fun getTokenProgram(account: PublicKey): TokenProgram {
        return tokenProgramCache.computeIfAbsent(account) {
            val owner = client.call(SolanaRpcClient::getAccountInfo, account).owner()
            TokenProgram.valueOf(owner)
        }
    }

    private fun NativeProgramAccountClient.createAccount(
        account: PublicKey,
        size: Long,
        tokenProgram: TokenProgram,
    ): Instruction {
        val rentExemption = rentExemptionCache.computeIfAbsent(size) {
            client.call(SolanaRpcClient::getMinimumBalanceForRentExemption, size)
        }
        return createAccount(account, rentExemption, size, tokenProgram.programId)
    }
}
