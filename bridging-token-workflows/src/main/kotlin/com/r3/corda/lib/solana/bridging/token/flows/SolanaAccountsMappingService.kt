package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenPointer
import net.corda.core.contracts.StateAndRef
import net.corda.core.cordapp.CordappConfig
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.solana.sdk.instruction.Pubkey
import java.lang.IllegalStateException
import kotlin.collections.get

@CordaService
class SolanaAccountsMappingService(
    appServiceHub: AppServiceHub,
) : SingletonSerializeAsToken() {
    private var participants: Map<CordaX500Name, Pubkey>
    private var mints: Map<String, Pubkey>
    private var mintAuthorities: Map<String, Pubkey>

    @Suppress("UNCHECKED_CAST")
    private fun CordappConfig.getUnchecked(configName: String) = this.get(configName) as? Map<String, String>

    init {
        val cfg = appServiceHub.getAppContext().config
        participants =
            (cfg.getUnchecked("participants"))
                ?.map { (k, v) -> CordaX500Name.parse(k) to Pubkey.fromBase58(v) }
                ?.toMap() ?: throw IllegalStateException("Missing participants configuration")

        mints =
            (cfg.getUnchecked("mints"))
                ?.map { (k, v) -> k to Pubkey.fromBase58(v) }
                ?.toMap() ?: throw IllegalStateException("Missing mints configuration")

        mintAuthorities =
            (cfg.getUnchecked("mintAuthorities"))
                ?.map { (k, v) -> k to Pubkey.fromBase58(v) }
                ?.toMap()
                ?: throw IllegalStateException("Missing mintAuthorities configuration")
    }

    fun getBridgingCoordinates(token: StateAndRef<FungibleToken>, originalOwner: AbstractParty): BridgingCoordinates {
        val cordaTokenId =
            when (val tokenType = token.state.data.amount.token.tokenType) {
                // TODO ENT-14343 while testing StockCordapp
                //  check if tokenType.tokenIdentifier can replace TokenPointer<*>
                is TokenPointer<*> -> tokenType.pointer.pointer.id.toString()
                else -> tokenType.tokenIdentifier
            }

        val destination = checkNotNull(participants[originalOwner.nameOrNull()]) {
            "No Solana account mapping found for previous owner ${originalOwner.nameOrNull()}"
        }
        val mint = checkNotNull(mints[cordaTokenId]) {
            "No mint mapping found for token type id $cordaTokenId"
        }
        val mintAuthority = checkNotNull(mintAuthorities[cordaTokenId]) {
            "No mint authority mapping found for token type id $cordaTokenId"
        }
        return BridgingCoordinates(mint, mintAuthority, destination)
    }
}
