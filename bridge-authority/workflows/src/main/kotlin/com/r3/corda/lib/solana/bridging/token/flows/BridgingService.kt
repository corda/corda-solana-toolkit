package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.states.TokenAmount
import com.r3.corda.lib.solana.core.AccountManagement
import com.r3.corda.lib.solana.core.SolanaClient
import com.r3.corda.lib.solana.core.tokens.TokenAccountListener
import com.r3.corda.lib.solana.core.tokens.TokenManagement
import com.r3.corda.lib.solana.core.tokens.TokenProgram
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.workflows.utilities.toParty
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.messaging.FlowHandle
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.ServiceLifecycleEvent
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.solana.Pubkey
import net.corda.core.utilities.debug
import org.slf4j.LoggerFactory
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.token.Mint
import software.sava.core.accounts.token.TokenAccount
import software.sava.rpc.json.http.client.SolanaRpcClient
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool.commonPool

@CordaService
class BridgingService(private val appServiceHub: AppServiceHub) : SingletonSerializeAsToken() {
    companion object {
        private val logger = LoggerFactory.getLogger(BridgingService::class.java)
    }

    private val configHandler = ConfigHandler(appServiceHub)
    private val solanaClient = SolanaClient(
        configHandler.solanaRpcUrl,
        configHandler.solanaWebsocketUrl,
        globalCommitmentLevel
    )
    private val tokenAccountListener = TokenAccountListener(
        solanaClient,
        tokenProgramId,
        configHandler.redemptionCheckInterval
    )
    private val tokenManagement = TokenManagement(solanaClient)
    private val accountManagement = AccountManagement(solanaClient)
    private val mintCache = ConcurrentHashMap<PublicKey, Mint>()

    init {
        appServiceHub.registerUnloadHandler { onStop() }
        appServiceHub.register { onStartup(it) }
    }

    private fun onStop() {
        logger.info("Bridging service stopped.")
        tokenAccountListener.close()
        solanaClient.close()
    }

    private fun onStartup(event: ServiceLifecycleEvent) {
        if (event != ServiceLifecycleEvent.STATE_MACHINE_STARTED) return
        solanaClient.start()
        checkAndListenForBridging()
        checkAllBalancesForRedemption()
        listenForRedemptions()
    }

    fun getBridgingCoordinates(token: StateAndRef<FungibleToken>, originalHolder: Party): BridgingCoordinates {
        val tokenTypeId = token.state.data.amount.token.tokenType.tokenIdentifier
        val mint = getTokenMint(tokenTypeId)
        val mintWalletAccount = checkNotNull(configHandler.participants[originalHolder.nameOrNull()]) {
            "No Solana account mapping found for Corda original holder ${originalHolder.nameOrNull()}"
        }
        return BridgingCoordinates(
            mint.address.toPubkey(),
            mint.mintAuthority.toPubkey(),
            mintWalletAccount
        )
    }

    fun getRedemptionCoordinates(
        tokenTypeId: String,
        redemptionWalletAccount: Pubkey,
        redemptionTokenAccount: Pubkey,
    ): RedemptionCoordinates {
        return RedemptionCoordinates(
            getTokenMint(tokenTypeId).address.toPubkey(),
            redemptionWalletAccount,
            redemptionTokenAccount,
            tokenTypeId
        )
    }

    private fun getTokenMint(tokenTypeId: String): Mint {
        val mintAddress = checkNotNull(configHandler.tokens[tokenTypeId]?.toPublicKey()) {
            "Corda token type $tokenTypeId has not been configured with a Solana token mint"
        }
        return getMint(mintAddress)
    }

    private fun checkAndListenForBridging() {
        // `trackBy` is atomic and ensures we don't miss any states between the snapshot and the updates
        val feed = appServiceHub.vaultService.trackBy(FungibleToken::class.java)
        feed.snapshot.states.forEach { token ->
            callBridgeFlow(appServiceHub, token)
        }
        feed.updates.subscribe {
            for (newToken in it.produced) {
                callBridgeFlow(appServiceHub, newToken)
            }
        }
    }

    private fun listenForRedemptions() {
        for (redemptionWallet in configHandler.redemptionWalletAccountToHolder.keys) {
            tokenAccountListener.listenToOwner(redemptionWallet.toPublicKey()) { tokenAccount ->
                processRedemptionEvent(redemptionWallet, tokenAccount)
            }
        }
        logger.info(
            "Successfully subscribed to websocket. Scheduling redemption balance checks every " +
                "${configHandler.redemptionCheckInterval}"
        )
    }

    private fun checkAllBalancesForRedemption() {
        logger.debug("Checking all balances for redemption")
        for (redemptionWallet in configHandler.redemptionWalletAccountToHolder.keys) {
            val tokenAccounts = solanaClient
                .call(
                    SolanaRpcClient::getTokenAccountsForProgramByOwner,
                    redemptionWallet.toPublicKey(),
                    tokenProgramId
                )
            for (accountInfo in tokenAccounts) {
                processRedemptionEvent(redemptionWallet, accountInfo.data)
            }
        }
    }

    private fun processRedemptionEvent(walletAccount: Pubkey, tokenAccount: TokenAccount) {
        if (tokenAccount.amount == 0L) {
            return
        }
        val tokenId = configHandler.tokens.entries.find { it.value.toPublicKey() == tokenAccount.mint }?.key
        checkNotNull(tokenId) {
            "No token configured for mint ${tokenAccount.mint}"
        }
        val cordaOwnerName = checkNotNull(configHandler.redemptionWalletAccountToHolder[walletAccount]) {
            "No Corda owner configured for Solana redemption account $walletAccount"
        }
        val cordaOwner = checkNotNull(appServiceHub.networkMapCache.getPeerByLegalName(cordaOwnerName)) {
            "No Corda owner found for Solana redemption account $tokenAccount"
        }
        val redemptionCoordinates = getRedemptionCoordinates(
            tokenId,
            walletAccount,
            tokenAccount.address().toPubkey()
        )
        logger.debug { "Redemption event: $redemptionCoordinates, amount ${tokenAccount.amount}" }
        val solanaAmount = TokenAmount(tokenAccount.amount, getMint(tokenAccount.mint).decimals)

        appServiceHub.startFlow(
            RedeemFungibleTokenFlow(
                redemptionCoordinates,
                cordaOwner,
                solanaAmount,
                configHandler.solanaNotary,
                configHandler.generalNotaryName,
                configHandler.lockingIdentity
            )
        ).logErrorIfException()
    }

    fun createAta(mint: PublicKey, owner: PublicKey) {
        val ata = TokenManagement.getAssociatedTokenAccountAddress(mint, owner, TokenProgram.valueOf(tokenProgramId))
        // First check the ATA doesn't exist before spending the transaction fee.
        if (accountManagement.getAccountInfo(ata) == null) {
            tokenManagement.createAssociatedTokenAccount(configHandler.bridgeAuthoritySigner, mint, owner)
        }
    }

    private fun FlowHandle<*>.logErrorIfException() {
        returnValue.toCompletableFuture().whenComplete { _, ex ->
            when {
                ex is CompletionException -> logger.error("Flow $id failed", ex.cause)
                ex != null -> logger.error("Flow $id failed", ex)
            }
        }
    }

    private fun callBridgeFlow(appServiceHub: AppServiceHub, token: StateAndRef<FungibleToken>) {
        val previousHolder = try {
            findPreviousHolderOfToken(token)
        } catch (e: Exception) {
            logger.warn("Could not start flow to bridge ${token.state.data}", e)
            return
        }
        if (previousHolder == configHandler.bridgeAuthority.party || previousHolder == configHandler.lockingIdentity) {
            return
        }
        logger.info("Starting flow to bridge ${token.state.data} to Solana for $previousHolder")
        commonPool().execute {
            try {
                appServiceHub.startFlow(
                    BridgeFungibleTokenFlow(
                        configHandler.lockingIdentity,
                        previousHolder.toParty(appServiceHub),
                        token,
                        configHandler.solanaNotary,
                        emptyList(), // Passing no observers, as an observer is not a generic concept in Tokens SDK
                    )
                ).logErrorIfException()
            } catch (e: Exception) {
                logger.error("Unable to start BridgeFungibleTokenFlow for $token", e)
            }
        }
    }

    private fun findPreviousHolderOfToken(output: StateAndRef<FungibleToken>): AbstractParty {
        val txHash = output.ref.txhash
        val stx = appServiceHub.validatedTransactions.getTransaction(txHash) ?: error("Transaction $txHash not found")

        val inputTokens: List<FungibleToken> = stx.toLedgerTransaction(appServiceHub).inputsOfType<FungibleToken>()
        require(inputTokens.isNotEmpty()) { "The transaction must have at least one input token." }

        val holders = inputTokens.map { it.holder }.toSet()
        require(holders.size == 1) { "Transaction contains tokens of multiple holders" } // This should not happen

        return holders.single()
    }

    fun getMint(mintAddress: PublicKey): Mint {
        return mintCache.computeIfAbsent(mintAddress) {
            solanaClient.call(SolanaRpcClient::getAccountInfo, it, Mint.FACTORY).data
        }
    }
}
