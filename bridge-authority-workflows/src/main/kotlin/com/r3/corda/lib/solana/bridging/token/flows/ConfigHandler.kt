package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.solana.core.FileSigner
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenPointer
import net.corda.core.contracts.StateAndRef
import net.corda.core.cordapp.CordappConfig
import net.corda.core.cordapp.CordappConfigException
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.AppServiceHub
import net.corda.core.solana.Pubkey
import software.sava.core.accounts.Signer
import java.time.Duration
import java.util.UUID
import kotlin.io.path.Path

class ConfigHandler(appServiceHub: AppServiceHub) {
    companion object {
        private val DEFAULT_REDEMPTION_CHECK_INTERVAL = Duration.ofSeconds(10)
    }

    private val participants: Map<CordaX500Name, Pubkey>
    private val mintsWithAuthorities: Map<String, MintWithAuthority>
    private val mintAccountToTokenId: Map<Pubkey, String>
    val lockingIdentity: Party
    val solanaNotary: Party
    val generalNotaryName: Party
    val bridgeAuthority: PartyAndCertificate
    val solanaWsUrl: String
    val solanaRpcUrl: String
    val redemptionWalletAccountToHolder: Map<Pubkey, CordaX500Name>
    val bridgeAuthoritySigner: Signer
    val redemptionCheckInterval: Duration

    init {
        val config = appServiceHub.getAppContext().config
        participants = config.getMap("participants", CordaX500Name::parse, Pubkey::fromBase58)
        mintsWithAuthorities = config.getMapOfObjects(
            "mintsWithAuthorities",
            { it },
            ::toMintWithAuthority,
        )
        mintAccountToTokenId = mintsWithAuthorities.entries.associate { (k, v) -> v.tokenMint to k }
        redemptionWalletAccountToHolder = config.getMap(
            "redemptionWalletAccountToHolder",
            Pubkey::fromBase58,
            CordaX500Name::parse,
        )
        bridgeAuthority = appServiceHub.myInfo.legalIdentitiesAndCerts.first()
        lockingIdentity = getLockingIdentity(config, appServiceHub)
        solanaNotary = getNotary("solanaNotaryName", config, appServiceHub)
        generalNotaryName = getNotary("generalNotaryName", config, appServiceHub)
        solanaWsUrl = config.getString("solanaWsUrl")
        solanaRpcUrl = config.getString("solanaRpcUrl")
        bridgeAuthoritySigner = FileSigner.read(Path(config.getString("bridgeAuthorityWalletFile")))
        redemptionCheckInterval = if (config.exists("redemptionCheckIntervalSeconds")) {
            Duration.ofSeconds(config.getLong("redemptionCheckIntervalSeconds"))
        } else {
            DEFAULT_REDEMPTION_CHECK_INTERVAL
        }
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

    fun getTokenIdentifierByMint(mint: Pubkey) = mintAccountToTokenId[mint]

    private fun toMintWithAuthority(data: Map<String, String>): MintWithAuthority {
        val mint = Pubkey.fromBase58(
            checkNotNull(data[MintWithAuthority::tokenMint.name]) {
                "${MintWithAuthority::tokenMint.name} is missing in mintWithAuthority config"
            }
        )
        val authority = Pubkey.fromBase58(
            checkNotNull(data[MintWithAuthority::mintAuthority.name]) {
                "${MintWithAuthority::mintAuthority.name} is missing in mintWithAuthority config"
            }
        )
        return MintWithAuthority(mint, authority)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <K, V> CordappConfig.getMapOfObjects(
        configName: String,
        transformKey: (String) -> K,
        transformValue: (Map<String, String>) -> V,
    ): Map<K, V> {
        return (get(configName) as Map<String, Map<String, String>>)
            .map { (key, value) -> transformKey(key) to transformValue(value) }
            .toMap()
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

    fun getBridgingCoordinates(
        token: StateAndRef<FungibleToken>,
        originalHolder: Party,
    ): BridgingCoordinates {
        val tokenTypeId = when (val tokenType = token.state.data.amount.token.tokenType) {
            // TODO ENT-14343 while testing StockCordapp
            //  check if tokenType.tokenIdentifier can replace TokenPointer<*>
            is TokenPointer<*> -> tokenType.pointer.pointer.id.toString()
            else -> tokenType.tokenIdentifier
        }
        val mintWithAuthority = checkNotNull(mintsWithAuthorities[tokenTypeId]) {
            "No mint with authority mapping found for token type id $tokenTypeId"
        }
        val mintWalletAccount = checkNotNull(participants[originalHolder.nameOrNull()]) {
            "No Solana account mapping found for Corda original holder ${originalHolder.nameOrNull()}"
        }
        return BridgingCoordinates(
            mintWithAuthority.tokenMint,
            mintWithAuthority.mintAuthority,
            mintWalletAccount
        )
    }

    fun getRedemptionCoordinates(
        tokenTypeId: String,
        redemptionWalletAccount: Pubkey,
        redemptionTokenAccount: Pubkey,
    ): RedemptionCoordinates {
        val mintWithAuthority = checkNotNull(mintsWithAuthorities[tokenTypeId]) {
            "No mint with authority mapping found for token type id $tokenTypeId"
        }
        return RedemptionCoordinates(
            mintWithAuthority.tokenMint,
            redemptionWalletAccount,
            redemptionTokenAccount,
            tokenTypeId
        )
    }
}

private data class MintWithAuthority(val tokenMint: Pubkey, val mintAuthority: Pubkey)
