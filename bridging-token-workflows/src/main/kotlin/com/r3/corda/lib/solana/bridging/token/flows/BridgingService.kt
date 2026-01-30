package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.bridging.token.flows.SavaFactory.toPubkey
import com.r3.corda.lib.solana.bridging.token.flows.SavaFactory.toPublicKey
import com.r3.corda.lib.solana.core.SolanaClient
import com.r3.corda.lib.solana.core.TokenManagement
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
import net.corda.solana.sdk.instruction.Pubkey
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@CordaService
class BridgingService(private val appServiceHub: AppServiceHub) : SingletonSerializeAsToken() {
    companion object {
        private val logger = LoggerFactory.getLogger(BridgingService::class.java)
        private const val MAX_BACKOFF_DELAY_SECS = 10
    }

    private val socket: SavaFactory.WebSocketWrapper
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val configHandler = ConfigHandler(appServiceHub)
    private val solanaClient: SolanaClient
    private val tokenManagement: TokenManagement

    init {
        socket = SavaFactory.WebSocketWrapper(
            configHandler.solanaRpcUrl,
            configHandler.solanaWsUrl,
            ::onSocketClosed
        )
        appServiceHub.registerUnloadHandler { onStop() }
        appServiceHub.register { onStartup(it) }
        solanaClient = SolanaClient(URI(configHandler.solanaRpcUrl), URI(configHandler.solanaWsUrl))
        tokenManagement = TokenManagement(solanaClient)
    }

    private fun onStop() {
        logger.info("Bridging service stopped.")
        executor.shutdown()
    }

    private fun onSocketClosed(errorCode: Int, reason: String) {
        logger.info("WebSocket closed: $errorCode, $reason. Reconnecting...")
        executor.submit { attemptSocketReconnect() }
    }

    private fun attemptSocketReconnect(remainingAttempts: Int = MAX_BACKOFF_DELAY_SECS) {
        val connected = socket.reconnect()
        when {
            connected -> {
                logger.info("Reconnected Solana websocket")
            }
            remainingAttempts > 0 -> {
                logger.warn("Failed to reconnect to ${socket.wsUrl}. Trying again...")
                val attemptNumber = MAX_BACKOFF_DELAY_SECS - remainingAttempts + 1
                logger.info("Retrying to reconnect in $attemptNumber seconds (attempt $attemptNumber)")
                executor.schedule(
                    {
                        attemptSocketReconnect(remainingAttempts - 1)
                    },
                    attemptNumber.toLong(),
                    TimeUnit.SECONDS
                )
            }
            else -> {
                logger.info(
                    """Failed to reconnect to ${socket.wsUrl}.
                        |Trying again in $MAX_BACKOFF_DELAY_SECS seconds
                    """.trimMargin()
                )
                executor.schedule(
                    {
                        attemptSocketReconnect(0)
                    },
                    MAX_BACKOFF_DELAY_SECS.toLong(),
                    TimeUnit.SECONDS
                )
            }
        }
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
        attemptToSubscribeToWebsocket(false)
    }

    private fun attemptToSubscribeToWebsocket(
        unsubscribeBefore: Boolean,
        remainingAttempts: Int = MAX_BACKOFF_DELAY_SECS,
    ) {
        if (unsubscribeBefore) {
            SavaFactory.logger.info("Unsubscribing from socket first before attempting a new subscription...")
            val unsubscribed = socket.unsubscribe()
            check(unsubscribed) { "Unsubscribing from socket was unsuccessful." }
        }
        val subscribed = socket.onToken2022ByOwner(
            configHandler.redemptionWalletAccountToHolder.keys,
            ::processRedemptionEvent,
        )
        when {
            subscribed -> {
                logger.info(
                    """Successfully subscribed to ${socket.wsUrl}. Scheduling redemption balance checks every
                        |${configHandler.redemptionCheckIntervalSeconds} seconds"
                    """.trimMargin()
                )
                executor.scheduleAtFixedRate({
                    checkAllBalancesForRedemption()
                }, 0, configHandler.redemptionCheckIntervalSeconds, TimeUnit.SECONDS)
            }
            remainingAttempts > 0 -> {
                logger.error("Failed to subscribe to ${socket.wsUrl}. Trying again...")
                val attemptNumber = MAX_BACKOFF_DELAY_SECS - remainingAttempts + 1
                logger.info("Retrying to connect in $attemptNumber seconds (attempt $attemptNumber)")
                executor.schedule(
                    {
                        attemptToSubscribeToWebsocket(true, remainingAttempts - 1)
                    },
                    attemptNumber.toLong(),
                    TimeUnit.SECONDS
                )
            }
            else -> logger.error("No remaining attempts to connect to ${socket.wsUrl}")
        }
    }

    private fun checkAllBalancesForRedemption() {
        logger.info("Checking all balances for redemption")
        configHandler.redemptionWalletAccountToHolder.keys.forEach { redemptionWallet ->
            socket.getNonZeroTokenAccounts(redemptionWallet).forEach { tokenAccountInfo ->
                processRedemptionEvent(
                    redemptionWallet,
                    tokenAccountInfo.pubKey.toPubkey(),
                    tokenAccountInfo.data.mint.toPubkey(),
                    tokenAccountInfo.data.amount()
                )
            }
        }
    }

    private fun processRedemptionEvent(
        redemptionWalletAccount: Pubkey,
        redemptionTokenAccount: Pubkey,
        mint: Pubkey,
        amount: Long,
    ) {
        try {
            if (amount == 0L) {
                return
            }
            val tokenId = checkNotNull(configHandler.getTokenIdentifierByMint(mint)) {
                "No token configured for mint $mint"
            }
            val cordaOwnerName = checkNotNull(
                configHandler.redemptionWalletAccountToHolder[redemptionWalletAccount]
            ) {
                "No Corda owner configured for Solana redemption account $redemptionWalletAccount"
            }
            val cordaOwner = checkNotNull(appServiceHub.networkMapCache.getPeerByLegalName(cordaOwnerName)) {
                "No Corda owner found for Solana redemption account $redemptionTokenAccount"
            }
            val redemptionCoordinates = configHandler.getRedemptionCoordinates(
                tokenId,
                redemptionWalletAccount,
                redemptionTokenAccount,
            )
            callRedemptionFlow(
                cordaOwner,
                amount,
                redemptionCoordinates,
            )
        } catch (e: Exception) {
            logger.error(
                """Error processing token received event for mint $mint,
                    |redemption wallet account $redemptionWalletAccount,
                    |redemption token account $redemptionTokenAccount, amount $amount
                """.trimMargin(),
                e
            )
        }
    }

    fun createAta(mint: Pubkey, owner: Pubkey) {
        tokenManagement.createAssociatedTokenAccount(
            configHandler.bridgeAuthoritySigner,
            mint.toPublicKey(),
            owner.toPublicKey()
        )
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
            executor.submit {
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
}
