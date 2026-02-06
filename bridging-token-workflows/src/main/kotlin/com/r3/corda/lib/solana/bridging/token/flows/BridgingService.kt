package com.r3.corda.lib.solana.bridging.token.flows

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
import net.corda.core.utilities.debug
import net.corda.node.utilities.solana.SolanaClient
import net.corda.solana.sdk.instruction.Pubkey
import org.slf4j.LoggerFactory
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.SolanaAccounts
import software.sava.core.accounts.token.TokenAccount
import software.sava.rpc.json.http.client.SolanaRpcClient
import java.net.URI
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@CordaService
class BridgingService(private val appServiceHub: AppServiceHub) : SingletonSerializeAsToken() {
    companion object {
        private val logger = LoggerFactory.getLogger(BridgingService::class.java)
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val configHandler = ConfigHandler(appServiceHub)
    private val solanaClient = SolanaClient(
        URI(configHandler.solanaRpcUrl),
        URI(configHandler.solanaWsUrl),
        globalCommitmentLevel
    )
    private val tokenAccountListener = TokenAccountListener(solanaClient, SolanaAccounts.MAIN_NET.token2022Program())
    private val accountService = TokenAccountService(solanaClient, configHandler.bridgeAuthoritySigner)

    init {
        appServiceHub.registerUnloadHandler { onStop() }
        appServiceHub.register { onStartup(it) }
    }

    private fun onStop() {
        logger.info("Bridging service stopped.")
        tokenAccountListener.close()
        solanaClient.close()
        scheduler.shutdown()
    }

    fun getBridgingCoordinates(token: StateAndRef<FungibleToken>, originalHolder: Party) =
        configHandler.getBridgingCoordinates(token, originalHolder)

    private fun onStartup(event: ServiceLifecycleEvent) {
        if (event != ServiceLifecycleEvent.STATE_MACHINE_STARTED) return
        solanaClient.start()
        checkAndListenForBridging()
        checkAndListenForRedemption()
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

    private fun checkAndListenForRedemption() {
        checkAllBalancesForRedemption()
        subscribeToWebsocket()
    }

    private fun subscribeToWebsocket() {
        for (redemptionWallet in configHandler.redemptionWalletAccountToHolder.keys) {
            val owner = PublicKey.createPubKey(redemptionWallet.bytes)
            tokenAccountListener.listenToOwner(owner) { tokenAccount ->
                processRedemptionEvent(redemptionWallet, tokenAccount)
            }
        }
        logger.info(
            "Successfully subscribed to websocket. Scheduling redemption balance checks every " +
                "${configHandler.redemptionCheckIntervalSeconds} seconds"
        )
        scheduler.scheduleAtFixedRate(
            { checkAllBalancesForRedemption() },
            0,
            configHandler.redemptionCheckIntervalSeconds,
            TimeUnit.SECONDS
        )
    }

    private fun checkAllBalancesForRedemption() {
        logger.debug("Checking all balances for redemption")
        for (redemptionWallet in configHandler.redemptionWalletAccountToHolder.keys) {
            val tokenAccounts = solanaClient
                .call(
                    SolanaRpcClient::getTokenAccountsForProgramByOwner,
                    PublicKey.createPubKey(redemptionWallet.bytes),
                    SolanaAccounts.MAIN_NET.token2022Program()
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
        try {
            val tokenId = checkNotNull(configHandler.getTokenIdentifierByMint(tokenAccount.mint.toPubkey())) {
                "No token configured for mint ${tokenAccount.mint}"
            }
            val cordaOwnerName = checkNotNull(configHandler.redemptionWalletAccountToHolder[walletAccount]) {
                "No Corda owner configured for Solana redemption account $walletAccount"
            }
            val cordaOwner = checkNotNull(appServiceHub.networkMapCache.getPeerByLegalName(cordaOwnerName)) {
                "No Corda owner found for Solana redemption account $tokenAccount"
            }
            val redemptionCoordinates = configHandler.getRedemptionCoordinates(
                tokenId,
                walletAccount,
                tokenAccount.address().toPubkey()
            )
            callRedemptionFlow(cordaOwner, tokenAccount.amount, redemptionCoordinates)
        } catch (e: Exception) {
            logger.error("Error processing token account event for $walletAccount, $tokenAccount", e)
        }
    }

    fun createAta(mint: Pubkey, owner: Pubkey) {
        accountService.createAta(mint.toPublicKey(), owner.toPublicKey())
    }

    private fun callRedemptionFlow(
        cordaOwner: Party,
        amount: Long,
        redemptionCoordinates: RedemptionCoordinates,
    ) {
        logger.debug { "Web socket event for redemption coordinates: $redemptionCoordinates, amount $amount" }
        if (amount == 0L) {
            return
        }
        with(configHandler) {
            appServiceHub
                .startFlow(
                    RedeemFungibleTokenFlow(
                        redemptionCoordinates,
                        cordaOwner,
                        amount,
                        solanaNotary,
                        generalNotaryName,
                        lockingIdentity
                    )
                ).logErrorIfException()
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
        with(configHandler) {
            if (previousHolder == bridgeAuthority.party || previousHolder == lockingIdentity) {
                return
            }
            logger.info("Starting flow to bridge ${token.state.data} to Solana for $previousHolder")
            scheduler.submit {
                try {
                    appServiceHub
                        .startFlow(
                            BridgeFungibleTokenFlow(
                                lockingIdentity,
                                previousHolder.toParty(appServiceHub),
                                token,
                                solanaNotary,
                                emptyList(), // TODO ENT-14346 an observer is not a generic concept in tokens
                            )
                        ).logErrorIfException()
                } catch (e: Exception) {
                    logger.error("Unable to start BridgeFungibleTokenFlow for $token", e)
                }
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

    private fun PublicKey.toPubkey() = Pubkey(copyByteArray())
}
