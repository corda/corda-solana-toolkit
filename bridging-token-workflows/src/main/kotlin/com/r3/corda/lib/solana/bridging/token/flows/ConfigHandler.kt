package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenPointer
import net.corda.core.contracts.StateAndRef
import net.corda.core.cordapp.CordappConfig
import net.corda.core.cordapp.CordappConfigException
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.AppServiceHub
import net.corda.solana.sdk.instruction.Pubkey
import java.util.*

class ConfigHandler(appServiceHub: AppServiceHub) {
    private val participants: Map<CordaX500Name, Pubkey>
    private val tokenIdToMintAccount: Map<String, Pubkey>
    private val mintAccountToTokenId: Map<Pubkey, String>
    private val mintAuthorities: Map<String, Pubkey>
    val lockingIdentity: Party
    val solanaNotary: Party
    val bridgeAuthority: PartyAndCertificate
    val solanaWsUrl: String
    val solanaRpcUrl: String
    val bridgeRedemptionAddress: Pubkey
    val redemptionHolders: Map<Pubkey, CordaX500Name>

    init {
        val config = appServiceHub.getAppContext().config
        participants = config.getMap("participants", CordaX500Name::parse, Pubkey::fromBase58)
        tokenIdToMintAccount = config.getMap("mints", { it }, Pubkey::fromBase58)
        mintAccountToTokenId = tokenIdToMintAccount.entries.associate { (k, v) -> v to k }
        mintAuthorities = config.getMap("mintAuthorities", { it }, Pubkey::fromBase58)
        redemptionHolders = config.getMap("redemptionHolders", Pubkey::fromBase58, CordaX500Name::parse)
        bridgeAuthority = appServiceHub.myInfo.legalIdentitiesAndCerts.first()
        lockingIdentity = getLockingIdentity(config, appServiceHub)
        solanaNotary = getSolanaNotary(config, appServiceHub)
        solanaWsUrl = config.getString("solanaWsUrl")
        solanaRpcUrl = config.getString("solanaRpcUrl")
        bridgeRedemptionAddress = Pubkey.fromBase58(config.getString("bridgeRedemptionAddress"))
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

    fun getTokenIdentifierByMint(mint: Pubkey) = mintAccountToTokenId[mint]

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
        val mint = checkNotNull(tokenIdToMintAccount[tokenTypeId]) {
            "No mint mapping found for token type id $tokenTypeId"
        }
        val mintAuthority = checkNotNull(mintAuthorities[tokenTypeId]) {
            "No mint authority mapping found for token type id $tokenTypeId"
        }
        val mintDestination = checkNotNull(participants[originalHolder.nameOrNull()]) {
            "No Solana account mapping found for previous owner ${originalHolder.nameOrNull()}"
        }
        return BridgingCoordinates(
            mint,
            mintAuthority,
            mintDestination
        )
    }

    fun getRedemptionCoordinates(
        tokenTypeId: String,
    ): RedemptionCoordinates {
        val mint = checkNotNull(tokenIdToMintAccount[tokenTypeId]) {
            "No mint mapping found for token type id $tokenTypeId"
        }
        return RedemptionCoordinates(mint, bridgeRedemptionAddress)
    }
}
