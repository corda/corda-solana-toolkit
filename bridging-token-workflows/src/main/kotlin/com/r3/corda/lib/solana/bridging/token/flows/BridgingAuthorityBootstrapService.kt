package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.debug
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.Executors

@CordaService
class BridgingAuthorityBootstrapService(appServiceHub: AppServiceHub) : SingletonSerializeAsToken() {
    private val holdingIdentity: Party
    private val solanaNotary: Party
    private val bridgeAuthority = appServiceHub.myInfo.legalIdentities.first()
    private val logger = LoggerFactory.getLogger(BridgingAuthorityBootstrapService::class.java)

    private val executor = Executors.newSingleThreadExecutor()

    init {
        val cfg = appServiceHub.getAppContext().config
        val holdingIdentityLabel = UUID.fromString(cfg.getString("holdingIdentityLabel"))
        val holdingIdentityPublicKey = appServiceHub
            .identityService
            .publicKeysForExternalId(holdingIdentityLabel)
            .singleOrNull()
        holdingIdentity = if (holdingIdentityPublicKey == null) {
            // Generate a new key pair and self-signed certificate for the holding identity
            appServiceHub.keyManagementService.freshKeyAndCert(
                identity = requireNotNull(appServiceHub.identityService.certificateFromKey(bridgeAuthority.owningKey)) {
                    "Could not find certificate for key ${bridgeAuthority.owningKey}"
                },
                revocationEnabled = false,
                externalId = holdingIdentityLabel
            ).party
        } else {
            // Reuse the existing key pair and certificate for the holding identity
            checkNotNull(
                appServiceHub.identityService.certificateFromKey(holdingIdentityPublicKey)?.party
            ) {
                "Could not find certificate for key $holdingIdentityPublicKey"
            }
        }
        val solanaNotaryName = CordaX500Name.parse(cfg.getString("solanaNotaryName"))
        solanaNotary = appServiceHub.networkParameters.notaries.first { it.identity.name == solanaNotaryName }.identity
        appServiceHub.registerUnloadHandler { onStop() }
        onStartup(appServiceHub)
    }

    private fun onStop() {
        executor.shutdown()
    }

    private fun onStartup(appServiceHub: AppServiceHub) {
        //Retrieve states from receiver
        val receivedStates = appServiceHub.vaultService.queryBy(FungibleToken::class.java).states


        callFlow(receivedStates, appServiceHub)
        addVaultListener(appServiceHub)
    }

    private fun addVaultListener(appServiceHub: AppServiceHub) {
        appServiceHub.vaultService.trackBy(FungibleToken::class.java).updates.subscribe {
            val producedStockStates = it.produced
            callFlow(producedStockStates, appServiceHub)
        }
    }

    private fun callFlow(fungibleTokens: Collection<StateAndRef<FungibleToken>>, appServiceHub: AppServiceHub) {
        fungibleTokens.forEach { token ->
            val previousOwner = previousOwnerOf(appServiceHub, token) ?: return@forEach
            if (previousOwner !in listOf(bridgeAuthority, holdingIdentity)) {
                logger.debug { "Starting flow to bridge ${token.state.data.amount} to Solana" }
                executor.submit {
                    appServiceHub.startFlow(
                        BridgeFungibleTokenFlow(
                            holdingIdentity,
                            previousOwner,
                            emptyList(),
                            token,
                            bridgeAuthority,
                            solanaNotary
                        )
                    )
                }
            }
        }
    }
}
