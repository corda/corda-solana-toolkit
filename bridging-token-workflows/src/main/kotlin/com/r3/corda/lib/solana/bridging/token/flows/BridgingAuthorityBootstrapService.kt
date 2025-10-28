package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.contracts.StateAndRef
import net.corda.core.cordapp.CordappConfigException
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.AppServiceHub
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.debug
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.Executors

@CordaService
class BridgingAuthorityBootstrapService(appServiceHub: AppServiceHub) : SingletonSerializeAsToken() {
    companion object {
        private val logger = LoggerFactory.getLogger(BridgingAuthorityBootstrapService::class.java)
    }

    private val holdingIdentity: AbstractParty
    private val solanaNotary: Party
    private val bridgeAuthority = appServiceHub.myInfo.legalIdentities.first()

    private val executor = Executors.newSingleThreadExecutor()

    init {
        val config = appServiceHub.getAppContext().config
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

        solanaNotary =
            appServiceHub.networkParameters.notaries
                .firstOrNull { it.identity.name == solanaNotaryName }
                ?.identity
                ?: error("Cound not find Solana Notary '$solanaNotaryName' in the network parameters")
        appServiceHub.registerUnloadHandler { onStop() }
        onStartup(appServiceHub)
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
        // Retrieve states from receiver
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
                        emptyList(), // TODO an observer is not a generic concept in tokens
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
}
