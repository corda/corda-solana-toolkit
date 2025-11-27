package com.r3.corda.lib.solana.bridging.token.test

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.programs.AssociatedTokenProgram
import com.r3.corda.lib.solana.bridging.token.flows.toPublicKey
import net.corda.core.identity.Party
import net.corda.solana.notary.common.Signer
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import net.corda.testing.solana.SolanaTestValidator

data class CordaUserBridgingAccounts(
    val node: StartedMockNode,
    val party: Party,
    val signer: Signer,
    val mintToAta: Map<PublicKey, PublicKey>,
) {
    companion object {
        fun generate(
            network: MockNetwork,
            identity: TestIdentity,
            mints: List<PublicKey>,
            testValidator: SolanaTestValidator,
        ): CordaUserBridgingAccounts {
            val signer = Signer.random()
            testValidator.fundAccount(10, signer)
            val node = network.createPartyNode(identity.name)
            return CordaUserBridgingAccounts(
                signer = signer,
                mintToAta = mints.associateWith { mint ->
                    AssociatedTokenProgram
                        .deriveAddress(signer.account, Token2022.PROGRAM_ID.toPublicKey(), mint)
                        .address()
                },
                node = node,
                party = node.info.legalIdentities.first()
            )
        }
    }
}
