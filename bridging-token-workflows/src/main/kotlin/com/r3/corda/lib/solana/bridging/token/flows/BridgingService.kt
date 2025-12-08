package com.r3.corda.lib.solana.bridging.token.flows

import com.lmax.solana4j.client.jsonrpc.SolanaJsonRpcClient
import com.r3.corda.lib.solana.bridging.token.flows.SavaFactory.toPubkey
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
import java.net.http.HttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@CordaService
class BridgingService(private val appServiceHub: AppServiceHub) : SingletonSerializeAsToken() {
    companion object {
        private val logger = LoggerFactory.getLogger(BridgingService::class.java)
        private const val MAXIMUM_CONNECTION_ATTEMPTS = 10
    }

    private val socket: SavaFactory.WebSocketWrapper
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val configHandler = ConfigHandler(appServiceHub)
    private val rpcClient: SolanaJsonRpcClient
    private val accountService: TokenAccountService

    init {
        socket = SavaFactory.WebSocketWrapper(configHandler.solanaRpcUrl, configHandler.solanaWsUrl)
        appServiceHub.registerUnloadHandler { onStop() }
        appServiceHub.register { onStartup(it) }
        rpcClient = SolanaJsonRpcClient(HttpClient.newHttpClient(), configHandler.solanaRpcUrl)
        accountService = TokenAccountService(rpcClient, configHandler.bridgeAuthoritySigner)
    }

    private fun onStop() {
        executor.shutdown()
    }

    fun getBridgingCoordinates(token: StateAndRef<FungibleToken>, originalHolder: Party) =
        configHandler.getBridgingCoordinates(token, originalHolder)

    private fun onStartup(event: ServiceLifecycleEvent) {
        if (event != ServiceLifecycleEvent.STATE_MACHINE_STARTED) return
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
        attemptToSubscribeToWebsocket()
    }

    private fun attemptToSubscribeToWebsocket(remainingAttempts: Int = MAXIMUM_CONNECTION_ATTEMPTS) {
        val subscribed = socket.onToken2022ByOwner(
            configHandler.redemptionWalletAccountToHolder.keys,
            ::processRedemptionEvent,
        )
        if (subscribed) {
            logger.info("Successfully subscribed to ${socket.wsUrl}")
            if (remainingAttempts >= 0) {
                logger.info(
                    "Scheduling redemption balance checks every ${configHandler.redemptionCheckIntervalSeconds} seconds"
                )
                executor.scheduleAtFixedRate({
                    if (socket.isClosed()) {
                        logger.warn("Redemption wallet is closed. Reconnecting websocket...")
                        attemptToSubscribeToWebsocket(0)
                    } else {
                        checkAllBalancesForRedemption()
                    }
                }, 0, configHandler.redemptionCheckIntervalSeconds, TimeUnit.SECONDS)
            } else {
                // No need to do anything as in this case the method was called from the periodic balance check task
            }
        } else {
            when {
                remainingAttempts > 0 -> {
                    logger.error("Failed to subscribe to ${socket.wsUrl}. Trying again...")
                    val attemptNumber = MAXIMUM_CONNECTION_ATTEMPTS - remainingAttempts + 1
                    val backOffSeconds = attemptNumber
                    logger.info("Retrying to connect in $backOffSeconds seconds (attempt $attemptNumber)")
                    executor.schedule(
                        {
                            attemptToSubscribeToWebsocket(remainingAttempts - 1)
                        },
                        backOffSeconds.toLong(),
                        TimeUnit.SECONDS
                    )
                }

                remainingAttempts == 0 -> logger.error("No remaining attempts to connect to ${socket.wsUrl}")
                else -> {
                    // When remainingAttempts < 0 we do nothing as this is the call from the periodic balance check task
                    logger.error("Failed to subscribe to ${socket.wsUrl} during interval Solana balance check.")
                }
            }
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

    fun processRedemptionEvent(
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
        val future = returnValue.toCompletableFuture()
        future.whenComplete { _, ex ->
            if (ex != null) {
                logger.error("Flow $id failed", ex)
            }
        }
    }

    private fun callBridgeFlow(appServiceHub: AppServiceHub, token: StateAndRef<FungibleToken>) {
        val previousHolder = try {
            findPreviousHolderOfToken(token) ?: return
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

    private fun findPreviousHolderOfToken(output: StateAndRef<FungibleToken>): AbstractParty? {
        val txHash = output.ref.txhash
        val stx = appServiceHub.validatedTransactions.getTransaction(txHash) ?: error("Transaction $txHash not found")

        val inputTokens: List<FungibleToken> = stx.toLedgerTransaction(appServiceHub).inputsOfType<FungibleToken>()
        require(inputTokens.isNotEmpty()) { "The transaction must have at least one input token." }

        val holders = inputTokens.map { it.holder }.toSet()
        require(holders.size == 1) { "Transaction contains tokens of multiple holders" } // This should not happen

        return holders.single()
    }
}
