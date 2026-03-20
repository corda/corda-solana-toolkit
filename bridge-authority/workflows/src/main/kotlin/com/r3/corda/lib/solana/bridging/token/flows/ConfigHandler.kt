package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.core.FileSigner
import net.corda.core.cordapp.CordappConfig
import net.corda.core.cordapp.CordappConfigException
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.AppServiceHub
import net.corda.core.solana.Pubkey
import software.sava.core.accounts.Signer
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID
import kotlin.io.path.Path

class ConfigHandler(appServiceHub: AppServiceHub) {
    companion object {
        private val DEFAULT_REDEMPTION_CHECK_INTERVAL = Duration.ofSeconds(10)
    }

    val participants: Map<CordaX500Name, Pubkey>
    val lockingIdentity: Party
    val solanaNotary: Party
    val generalNotaryName: Party
    val bridgeAuthority: PartyAndCertificate
    val solanaWebsocketUrl: URI
    val solanaRpcUrl: URI
    val tokens: Map<String, Pubkey>
    val redemptionWalletAccountToHolder: Map<Pubkey, CordaX500Name>
    val bridgeAuthoritySigner: Signer
    val redemptionCheckInterval: Duration

    init {
        val config = appServiceHub.getAppContext().config
        participants = config.getMap("participants", CordaX500Name::parse, Pubkey::fromBase58)
        tokens = config.getMap("tokens", { it }, Pubkey::fromBase58)
        redemptionWalletAccountToHolder = config.getMap(
            "redemptionWalletAccountToHolder",
            Pubkey::fromBase58,
            CordaX500Name::parse,
        )
        bridgeAuthority = appServiceHub.myInfo.legalIdentitiesAndCerts.first()
        lockingIdentity = getLockingIdentity(appServiceHub)
        solanaNotary = getNotary("solanaNotaryName", config, appServiceHub)
        generalNotaryName = getNotary("generalNotaryName", config, appServiceHub)
        solanaWebsocketUrl = URI.create(config.getString("solanaWebsocketUrl"))
        solanaRpcUrl = URI.create(config.getString("solanaRpcUrl"))
        bridgeAuthoritySigner = FileSigner.read(Path(config.getString("bridgeAuthorityWalletFile")))
        redemptionCheckInterval = if (config.exists("redemptionCheckIntervalSeconds")) {
            Duration.ofSeconds(config.getLong("redemptionCheckIntervalSeconds"))
        } else {
            DEFAULT_REDEMPTION_CHECK_INTERVAL
        }
    }

    private fun getLockingIdentity(appServiceHub: AppServiceHub): Party {
        val lockingIdentityId = getGlobalLockingIdentityId()
        val lockingIdentityPublicKey = appServiceHub
            .identityService
            .publicKeysForExternalId(lockingIdentityId)
            .singleOrNull()
        val identity = if (lockingIdentityPublicKey == null) {
            // Generate a new key pair and self-signed certificate for the locking identity
            appServiceHub
                .keyManagementService
                .freshKeyAndCert(bridgeAuthority, revocationEnabled = false, externalId = lockingIdentityId)
        } else {
            // Reuse the existing key pair and certificate for the locking identity
            checkNotNull(appServiceHub.identityService.certificateFromKey(lockingIdentityPublicKey)) {
                "Could not find certificate for key $lockingIdentityPublicKey"
            }
        }
        return identity.party
    }

    // Returns the unique, deterministic ID for the bridge authority's locking identity.
    private fun getGlobalLockingIdentityId(): UUID {
        // Halving the 256-bit hash to fit into a UUID is not an issue, since the probability of a collision
        // against a random UUID in the database is still effectively zero.
        val buffer = ByteBuffer.wrap(SecureHash.sha256("BRIDGE_AUTHORITY_LOCKING_IDENTITY").bytes)
        return UUID(buffer.getLong(), buffer.getLong())
    }

    private fun getNotary(notaryNameConfig: String, config: CordappConfig, appServiceHub: AppServiceHub): Party {
        val notaryName = try {
            CordaX500Name.parse(config.getString(notaryNameConfig))
        } catch (_: CordappConfigException) {
            error("Could not find configuration entry '$notaryNameConfig'")
        }
        return requireNotNull(appServiceHub.networkMapCache.getNotary(notaryName)) {
            "Cound not find notary '$notaryName' in the network parameters"
        }
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
