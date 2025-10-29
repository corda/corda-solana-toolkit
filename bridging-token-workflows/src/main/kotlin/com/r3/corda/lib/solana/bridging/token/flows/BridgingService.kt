package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenPointer
import net.corda.core.contracts.StateAndRef
import net.corda.core.cordapp.CordappConfig
import net.corda.core.cordapp.CordappConfigException
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.AppServiceHub
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.debug
import net.corda.solana.sdk.instruction.Pubkey
import org.slf4j.LoggerFactory
import java.lang.IllegalStateException
import java.util.*
import java.util.concurrent.Executors
import kotlin.collections.component1
import kotlin.collections.component2

@CordaService
class BridgingService(appServiceHub: AppServiceHub) : SingletonSerializeAsToken(), SolanaAccountsMapping {
    companion object {
        private val logger = LoggerFactory.getLogger(BridgingService::class.java)
    }

    private var participants: Map<CordaX500Name, Pubkey>
    private var mints: Map<String, Pubkey>
    private var mintAuthorities: Map<String, Pubkey>
    private val holdingIdentity: AbstractParty
    private val solanaNotary: Party
    private val bridgeAuthority = appServiceHub.myInfo.legalIdentities.first()

    private val executor = Executors.newSingleThreadExecutor()

    init {
        val config = appServiceHub.getAppContext().config

        participants =
            (config.getUnchecked("participants"))
                ?.map { (k, v) -> CordaX500Name.parse(k) to Pubkey.fromBase58(v) }
                ?.toMap() ?: throw IllegalStateException("Missing participants configuration")

        mints =
            (config.getUnchecked("mints"))
                ?.map { (k, v) -> k to Pubkey.fromBase58(v) }
                ?.toMap() ?: throw IllegalStateException("Missing mints configuration")

        mintAuthorities =
            (config.getUnchecked("mintAuthorities"))
                ?.map { (k, v) -> k to Pubkey.fromBase58(v) }
                ?.toMap()
                ?: throw IllegalStateException("Missing mintAuthorities configuration")

        val holdingIdentityLabel = UUID.fromString(config.getString("holdingIdentityLabel"))
        val holdingIdentityPublicKey = appServiceHub
            .identityService
            .publicKeysForExternalId(holdingIdentityLabel)
            .singleOrNull()
        holdingIdentity = if (holdingIdentityPublicKey == null) {
            createHoldingIdentity(appServiceHub, holdingIdentityLabel)
        } else {
            // Reuse the existing key pair and certificate for the holding identity
            checkNotNull(appServiceHub.identityService.certificateFromKey(holdingIdentityPublicKey)?.party) {
                "Could not find certificate for key $holdingIdentityPublicKey"
            }
        }
        val solanaNotaryName = try {
            CordaX500Name.parse(config.getString("solanaNotaryName"))
        } catch (_: CordappConfigException) {
            error("Could not find configuration entry 'solanaNotaryName'")
        }
        solanaNotary = requireNotNull(appServiceHub.networkMapCache.getNotary(solanaNotaryName)) {
            "Cound not find Solana Notary '$solanaNotaryName' in the network parameters"
        }
        appServiceHub.registerUnloadHandler { onStop() }
        onStartup(appServiceHub)
    }

    override fun getBridgingCoordinates(token: StateAndRef<FungibleToken>, originalHolder: AbstractParty):
        BridgingCoordinates {
        val cordaTokenId =
            when (val tokenType = token.state.data.amount.token.tokenType) {
                // TODO ENT-14343 while testing StockCordapp
                //  check if tokenType.tokenIdentifier can replace TokenPointer<*>
                is TokenPointer<*> -> tokenType.pointer.pointer.id.toString()
                else -> tokenType.tokenIdentifier
            }

        val destination = checkNotNull(participants[originalHolder.nameOrNull()]) {
            "No Solana account mapping found for previous owner ${originalHolder.nameOrNull()}"
        }
        val mint = checkNotNull(mints[cordaTokenId]) {
            "No mint mapping found for token type id $cordaTokenId"
        }
        val mintAuthority = checkNotNull(mintAuthorities[cordaTokenId]) {
            "No mint authority mapping found for token type id $cordaTokenId"
        }
        return BridgingCoordinates(mint, mintAuthority, destination)
    }

    private fun createHoldingIdentity(appServiceHub: AppServiceHub, holdingIdentityLabel: UUID): Party {
        // Generate a new key pair and self-signed certificate for the holding identity
        val identity = requireNotNull(appServiceHub.identityService.certificateFromKey(bridgeAuthority.owningKey)) {
            "Could not find certificate for key ${bridgeAuthority.owningKey}"
        }
        return appServiceHub
            .keyManagementService
            .freshKeyAndCert(identity = identity, revocationEnabled = false, externalId = holdingIdentityLabel)
            .party
    }

    private fun onStop() {
        executor.shutdown()
    }

    private fun onStartup(appServiceHub: AppServiceHub) {
        // Retrieve unprocessed fungible tokens received while the node was offline
        val receivedStates = appServiceHub.vaultService.queryBy(FungibleToken::class.java).states

        receivedStates.forEach { token ->
            callBridgeFlow(appServiceHub, token)
        }
        listenForFungibleTokens(appServiceHub)
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
            findPreviousHolderOfToken(appServiceHub, token)
        } catch (e: Exception) {
            logger.warn("Could not start flow to bridge for ${token.state.data.amount} due to ${e.message}")
            return
        }
        if (previousHolder == bridgeAuthority || previousHolder == holdingIdentity) {
            return
        }
        logger.debug { "Starting flow to bridge ${token.state.data.amount} to Solana" }
        executor.submit {
            try {
                appServiceHub.startFlow(
                    BridgeFungibleTokenFlow(
                        holdingIdentity,
                        previousHolder,
                        token,
                        solanaNotary,
                        emptyList(), // TODO ENT-14346 an observer is not a generic concept in tokens
                    ),
                )
            } catch (e: Exception) {
                logger.error("Unable to start BridgeFungibleTokenFlow for $token", e)
            }
        }
    }

    private fun findPreviousHolderOfToken(serviceHub: ServiceHub, output: StateAndRef<FungibleToken>): AbstractParty {
        val txHash = output.ref.txhash
        val stx = serviceHub.validatedTransactions.getTransaction(txHash) ?: error("Transaction $txHash not found")

        val inputTokens: List<FungibleToken> = stx.toLedgerTransaction(serviceHub).inputsOfType<FungibleToken>()
        require(inputTokens.isNotEmpty()) { "Transaction doesn't contains inputs of fungible token" }

        val holders = inputTokens.map { it.holder }.toSet()
        require(holders.size == 1) { "Transaction contains tokens of multiple holders" } // This should not happen

        return holders.single()
    }

    @Suppress("UNCHECKED_CAST")
    private fun CordappConfig.getUnchecked(configName: String) = this.get(configName) as? Map<String, String>
}
