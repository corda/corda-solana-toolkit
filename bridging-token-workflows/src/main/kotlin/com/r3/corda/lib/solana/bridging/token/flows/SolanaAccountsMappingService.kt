package com.r3.corda.lib.solana.bridging.token.flows

import net.corda.core.identity.CordaX500Name
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.solana.sdk.instruction.Pubkey
import java.lang.IllegalStateException

@Suppress("UNCHECKED_CAST")
@CordaService
class SolanaAccountsMappingService(
    appServiceHub: AppServiceHub,
) : SingletonSerializeAsToken() {
    var participants: Map<CordaX500Name, Pubkey>
    var mints: Map<String, Pubkey>
    var mintAuthorities: Map<String, Pubkey>

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
}
