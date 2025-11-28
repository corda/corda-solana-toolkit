package com.r3.corda.lib.solana.bridging.token.test

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.programs.AssociatedTokenProgram
import com.r3.corda.lib.solana.bridging.token.flows.toPublicKey
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.solana.notary.common.Signer
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import net.corda.testing.solana.SolanaTestValidator
import java.math.BigDecimal

data class CordaNodeAndSolanaAccounts private constructor(
    val node: StartedMockNode,
    val party: Party,
    val signer: Signer,
    val mintToAta: Map<PublicKey, PublicKey>,
    val mintToExpectedCordaBalance: MutableMap<PublicKey, BigDecimal>,
    val mintToExpectedSolanaBalance: MutableMap<PublicKey, BigDecimal>,
) {
    companion object {
        fun createAndInitialise(
            network: MockNetwork,
            cordaName: CordaX500Name,
            mints: List<PublicKey>,
            testValidator: SolanaTestValidator,
        ): CordaNodeAndSolanaAccounts {
            val signer = Signer.random()
            testValidator.fundAccount(10, signer)
            val node = network.createPartyNode(cordaName)
            return CordaNodeAndSolanaAccounts(
                signer = signer,
                mintToAta = mints.associateWith { mint ->
                    AssociatedTokenProgram
                        .deriveAddress(signer.account, Token2022.PROGRAM_ID.toPublicKey(), mint)
                        .address()
                },
                node = node,
                party = node.info.legalIdentities.first(),
                mintToExpectedCordaBalance = mints.associate { it to BigDecimal.ZERO }.toMutableMap(),
                mintToExpectedSolanaBalance = mints.associate { it to BigDecimal.ZERO }.toMutableMap(),
            )
        }
    }

    fun setExpectedCordaBalance(mint: PublicKey, quantity: BigDecimal) {
        mintToExpectedCordaBalance[mint] = quantity
    }

    fun setExpectedSolanaBalance(mint: PublicKey, quantity: BigDecimal) {
        mintToExpectedSolanaBalance[mint] = quantity
    }

    fun redeemExpectedBalance(mint: PublicKey, quantity: BigDecimal) {
        mintToExpectedSolanaBalance[mint] = mintToExpectedSolanaBalance.getValue(mint).subtract(quantity)
        mintToExpectedCordaBalance[mint] = mintToExpectedCordaBalance.getValue(mint).add(quantity)
    }

    fun bridgeExpectedBalance(mint: PublicKey, quantity: BigDecimal) {
        mintToExpectedSolanaBalance[mint] = mintToExpectedSolanaBalance.getValue(mint).add(quantity)
        mintToExpectedCordaBalance[mint] = mintToExpectedCordaBalance.getValue(mint).subtract(quantity)
    }
}
