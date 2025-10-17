package com.r3.corda.lib.solana.bridging.token.flows

import net.corda.core.identity.CordaX500Name
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.solana.sdk.instruction.Pubkey

@Suppress("ClassSignature")
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
            try {
                @Suppress("UNCHECKED_CAST")
                (cfg.get("participants") as? Map<String, String>)
                    ?.map { (k, v) -> CordaX500Name.parse(k) to Pubkey.fromBase58(v) }
                    ?.toMap()
                    ?: emptyMap()
            } catch (_: Exception) {
                emptyMap() // TODO here and other occurrences, for now ignore misconfiguration ...
                // ... as the service is used by Notary in the mock network
            }
        mints =
            try {
                @Suppress("UNCHECKED_CAST")
                (cfg.get("mints") as? Map<String, String>)
                    ?.map { (k, v) -> k to Pubkey.fromBase58(v) }
                    ?.toMap()
                    ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        mintAuthorities =
            try {
                @Suppress("UNCHECKED_CAST")
                (cfg.get("mintAuthorities") as? Map<String, String>)
                    ?.map { (k, v) -> k to Pubkey.fromBase58(v) }
                    ?.toMap()
                    ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
    }
}
