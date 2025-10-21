package com.r3.corda.lib.solana.bridging.token.flows

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenPointer
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.solana.sdk.instruction.Pubkey
import java.lang.IllegalStateException
import kotlin.collections.get

@Suppress("UNCHECKED_CAST")
@CordaService
class SolanaAccountsMappingService(
    appServiceHub: AppServiceHub,
) : SingletonSerializeAsToken() {
    private var participants: Map<CordaX500Name, Pubkey>
    private var mints: Map<String, Pubkey>
    private var mintAuthorities: Map<String, Pubkey>

    init {
        val cfg = appServiceHub.getAppContext().config
        participants =
            (cfg.get("participants") as? Map<String, String>)
                ?.map { (k, v) -> CordaX500Name.parse(k) to Pubkey.fromBase58(v) }
                ?.toMap() ?: throw IllegalStateException("Missing participants configuration")

        mints =
            (cfg.get("mints") as? Map<String, String>)
                ?.map { (k, v) -> k to Pubkey.fromBase58(v) }
                ?.toMap() ?: throw IllegalStateException("Missing mints configuration")

        mintAuthorities =
            (cfg.get("mintAuthorities") as? Map<String, String>)
                ?.map { (k, v) -> k to Pubkey.fromBase58(v) }
                ?.toMap()
                ?: throw IllegalStateException("Missing mintAuthorities configuration")
    }

    fun getBridgingCoordinates(token: StateAndRef<FungibleToken>, originalOwner: AbstractParty): BridgingCoordinates {
        val cordaTokenId =
            when (val tokenType = token.state.data.amount.token.tokenType) {
                // TODO while testing StockCordapp check if tokenType.tokenIdentifier can replace TokenPointer<*>
                is TokenPointer<*> ->
                    tokenType.pointer.pointer.id
                        .toString()

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
        val coordinates = BridgingCoordinates(originalOwner, cordaTokenId, mint, mintAuthority, destination)
        return coordinates
    }
}
