package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.workflows.utilities.toParty
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.ServiceLifecycleEvent
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.debug
import net.corda.solana.aggregator.common.sava.SavaFactory
import net.corda.solana.sdk.instruction.Pubkey
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

@CordaService
class BridgingService(private val appServiceHub: AppServiceHub) : SingletonSerializeAsToken() {
    companion object {
        private val logger = LoggerFactory.getLogger(BridgingService::class.java)
    }

    private val socket: SavaFactory.WebSocketWrapper
    private val executor = Executors.newSingleThreadExecutor()
    private val configHandler = ConfigHandler(appServiceHub)

    init {
        socket = SavaFactory.WebSocketWrapper(configHandler.solanaRpcUrl, configHandler.solanaWsUrl)
        appServiceHub.registerUnloadHandler { onStop() }
        appServiceHub.register { onStartup(it) }
    }

    private fun onStop() {
        executor.shutdown()
    }

    private fun onStartup(event: ServiceLifecycleEvent) {
        if (event != ServiceLifecycleEvent.STATE_MACHINE_STARTED) return
        // Retrieve unprocessed fungible tokens received while the node was offline
        val receivedStates = appServiceHub.vaultService.queryBy(FungibleToken::class.java).states

        receivedStates.forEach { token ->
            callBridgeFlow(appServiceHub, token)
        }
        listenForFungibleTokens(appServiceHub)

        // Redemption initialization
        val subscribed = socket.onToken2022ByOwner(
            configHandler.bridgeRedemptionWallet
        ) { _, burnAccount, mint, amount ->
            // TODO perhaps move those to the flow so it can be tracked by the flow hospital
            val tokenId = checkNotNull(configHandler.getTokenIdentifierByMint(mint)) {
                "No token configured for mint $mint"
            }
            val cordaOwnerName = checkNotNull(configHandler.redemptionHolders[burnAccount]) {
                "No Corda owner configured for Solana redemption account $burnAccount"
            }
            val coraOwner = checkNotNull(appServiceHub.networkMapCache.getPeerByLegalName(cordaOwnerName)) {
                "No Corda owner found for Solana redemption account $burnAccount"
            }
            onTokenReceivedCallback(
                configHandler.bridgeRedemptionWallet,
                coraOwner,
                amount,
                tokenId,
                burnAccount
            )
        }
        if (!subscribed) {
            logger.error(
                "Failed to subscribe to ${socket.wsUrl} for wallet ${configHandler.bridgeRedemptionWallet}"
            )
        }
    }

    fun getBridgingCoordinates(tokenTypeId: String, originalHolder: Party) =
        configHandler.getBridgingCoordinates(tokenTypeId, originalHolder)

    fun getBridgingCoordinates(
        token: StateAndRef<FungibleToken>,
        originalHolder: Party,
    ) = configHandler.getBridgingCoordinates(token, originalHolder)

    private fun onTokenReceivedCallback(
        solanaOwner: Pubkey,
        cordaOwner: Party,
        amount: Long,
        tokenId: String,
        burnAccount: Pubkey,
    ) {
        logger.debug { "Web socket event for $solanaOwner amount $amount" }
        if (amount == 0L) {
            return
        }
        val flowHandle = with(configHandler) {
            appServiceHub.startFlow(
                RedeemFungibleTokenFlow(
                    burnAccount,
                    cordaOwner,
                    tokenId,
                    amount,
                    solanaNotary,
                    lockingIdentity
                )
            )
        }
        flowHandle.returnValue.get()
    }

    private fun listenForFungibleTokens(appServiceHub: AppServiceHub) {
        appServiceHub.vaultService.trackBy(FungibleToken::class.java).updates.subscribe {
            for (newToken in it.produced) {
                callBridgeFlow(appServiceHub, newToken)
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
                    appServiceHub.startFlow(
                        BridgeFungibleTokenFlow(
                            lockingIdentity,
                            previousHolder.toParty(appServiceHub),
                            token,
                            solanaNotary,
                            emptyList(), // TODO ENT-14346 an observer is not a generic concept in tokens
                        )
                    )
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
        if (inputTokens.isEmpty()) {
            // This is possible if the token was issued in this transaction
            return null
        }

        val holders = inputTokens.map { it.holder }.toSet()
        require(holders.size == 1) { "Transaction contains tokens of multiple holders" } // This should not happen

        return holders.single()
    }
}
