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
import java.util.UUID
import java.util.concurrent.Executors

@CordaService
class BridgingService(appServiceHub: AppServiceHub) : SingletonSerializeAsToken(), SolanaAccountsMapping {
    companion object {
        private val logger = LoggerFactory.getLogger(BridgingService::class.java)
    }

    private val participants: Map<CordaX500Name, Pubkey>
    private val mints: Map<String, Pubkey>
    private val mintAuthorities: Map<String, Pubkey>
    private val lockingIdentity: AbstractParty
    private val solanaNotary: Party
    private val bridgeAuthority = appServiceHub.myInfo.legalIdentitiesAndCerts.first()

    private val executor = Executors.newSingleThreadExecutor()

    init {
        val config = appServiceHub.getAppContext().config
        participants = config.getMap("participants", CordaX500Name::parse, Pubkey::fromBase58)
        mints = config.getMap("mints", { it }, Pubkey::fromBase58)
        mintAuthorities = config.getMap("mintAuthorities", { it }, Pubkey::fromBase58)
        lockingIdentity = getLockingIdentity(config, appServiceHub)
        solanaNotary = getSolanaNotary(config, appServiceHub)
        appServiceHub.registerUnloadHandler { onStop() }
        onStartup(appServiceHub)
    }

    private fun getLockingIdentity(config: CordappConfig, appServiceHub: AppServiceHub): Party {
        val lockingIdentityLabel = UUID.fromString(config.getString("lockingIdentityLabel"))
        val lockingIdentityPublicKey = appServiceHub
            .identityService
            .publicKeysForExternalId(lockingIdentityLabel)
            .singleOrNull()
        val identity = if (lockingIdentityPublicKey == null) {
            // Generate a new key pair and self-signed certificate for the locking identity
            appServiceHub
                .keyManagementService
                .freshKeyAndCert(bridgeAuthority, revocationEnabled = false, externalId = lockingIdentityLabel)
        } else {
            // Reuse the existing key pair and certificate for the locking identity
            checkNotNull(appServiceHub.identityService.certificateFromKey(lockingIdentityPublicKey)) {
                "Could not find certificate for key $lockingIdentityPublicKey"
            }
        }
        return identity.party
    }

    private fun getSolanaNotary(config: CordappConfig, appServiceHub: AppServiceHub): Party {
        val solanaNotaryName = try {
            CordaX500Name.parse(config.getString("solanaNotaryName"))
        } catch (_: CordappConfigException) {
            error("Could not find configuration entry 'solanaNotaryName'")
        }
        return requireNotNull(appServiceHub.networkMapCache.getNotary(solanaNotaryName)) {
            "Cound not find Solana Notary '$solanaNotaryName' in the network parameters"
        }
    }

    override fun getBridgingCoordinates(
        token: StateAndRef<FungibleToken>,
        originalHolder: AbstractParty,
    ): BridgingCoordinates {
        val cordaTokenId = when (val tokenType = token.state.data.amount.token.tokenType) {
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
            logger.warn("Could not start flow to bridge ${token.state.data}", e)
            return
        }
        if (previousHolder == bridgeAuthority.party || previousHolder == lockingIdentity) {
            return
        }
        logger.debug { "Starting flow to bridge ${token.state.data} to Solana for $previousHolder" }
        executor.submit {
            try {
                appServiceHub.startFlow(
                    BridgeFungibleTokenFlow(
                        lockingIdentity,
                        previousHolder,
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
    private inline fun <K, V> CordappConfig.getMap(
        configName: String,
        transformKey: (String) -> K,
        transformValue: (String) -> V,
    ): Map<K, V> {
        return (get(configName) as Map<String, String>)
            .map { (key, value) -> transformKey(key) to transformValue(value) }
            .toMap()
    }
}
