package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.core.AccountManagement
import com.r3.corda.lib.solana.core.SolanaClient
import com.r3.corda.lib.solana.core.tokens.TokenAccountListener
import com.r3.corda.lib.solana.core.tokens.TokenManagement
import com.r3.corda.lib.solana.core.tokens.TokenProgram
import com.r3.corda.lib.tokens.contracts.internal.schemas.PersistentFungibleToken
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.workflows.utilities.toParty
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.messaging.FlowHandle
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.ServiceLifecycleEvent
import net.corda.core.node.services.vault.Builder.equal
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.solana.Pubkey
import net.corda.core.utilities.debug
import org.slf4j.LoggerFactory
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.token.Mint
import software.sava.core.accounts.token.TokenAccount
import software.sava.rpc.json.http.client.SolanaRpcClient
import software.sava.rpc.json.http.response.AccountInfo
import java.net.URI
import java.util.concurrent.CompletionException
import java.util.concurrent.ForkJoinPool.commonPool

@CordaService
class BridgingService(private val appServiceHub: AppServiceHub) : SingletonSerializeAsToken() {
    companion object {
        private val logger = LoggerFactory.getLogger(BridgingService::class.java)
    }

    private val configHandler = ConfigHandler(appServiceHub)
    private val solanaClient = SolanaClient(
        URI(configHandler.solanaRpcUrl),
        URI(configHandler.solanaWsUrl),
        globalCommitmentLevel
    )
    private val tokenAccountListener = TokenAccountListener(
        solanaClient,
        tokenProgramId,
        configHandler.redemptionCheckInterval
    )
    private val tokenManagement = TokenManagement(solanaClient)
    private val accountManagement = AccountManagement(solanaClient)

    init {
        appServiceHub.registerUnloadHandler { onStop() }
        appServiceHub.register { onStartup(it) }
    }

    private fun onStop() {
        logger.info("Bridging service stopped.")
        tokenAccountListener.close()
        solanaClient.close()
    }

    fun getBridgingCoordinates(token: StateAndRef<FungibleToken>, originalHolder: Party) : BridgingCoordinates {
        val coordinates = configHandler.getBridgingCoordinates(token, originalHolder)
        val solanaDecimals = getAccountMintDecimals(coordinates.mintAccount.toPublicKey())
        return coordinates.copy(mintDecimals = solanaDecimals)
    }

    private fun onStartup(event: ServiceLifecycleEvent) {
        if (event != ServiceLifecycleEvent.STATE_MACHINE_STARTED) return
        solanaClient.start()
        checkAndListenForBridging()
        checkAllBalancesForRedemption()
        listenForRedemptions()
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
            tokenAccount.address().toPubkey(),
            tokenMintDecimals = getAccountMintDecimals(tokenAccount.mint)
        )
        logger.debug { "Redemption event: $redemptionCoordinates, amount ${tokenAccount.amount}" }
        val solanaDecimals = getAccountMintDecimals(redemptionCoordinates.mintAccount.toPublicKey())
        appServiceHub.startFlow(
            RedeemFungibleTokenFlow(
                redemptionCoordinates.copy(tokenMintDecimals = solanaDecimals),
                cordaOwner,
                tokenAccount.amount,
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
                        emptyList(), // TODO ENT-14346 an observer is not a generic concept in tokens
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


    fun getAccountMintDecimals(account: PublicKey): Int {
        val accountInfo = solanaClient.call(SolanaRpcClient::getAccountInfo, account)
        val mint = Mint.read(accountInfo.pubKey(), accountInfo.data)
        return mint.decimals
    }
}
