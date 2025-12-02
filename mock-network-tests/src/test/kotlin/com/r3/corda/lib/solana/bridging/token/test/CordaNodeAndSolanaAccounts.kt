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

data class CordaNodeAndSolanaAccounts(
    val node: StartedMockNode,
    val party: Party,
    val signer: Signer,
    val mintToAta: Map<PublicKey, PublicKey>,
    val expectedCordaBalance: MutableMap<PublicKey, BigDecimal>,
    val expectedSolanaBalance: MutableMap<PublicKey, BigDecimal>,
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
                expectedCordaBalance = mints.associateWith { BigDecimal.ZERO }.toMutableMap(),
                expectedSolanaBalance = mints.associateWith { BigDecimal.ZERO }.toMutableMap(),
            )
        }
    }

    fun setExpectedCordaBalance(mint: PublicKey, quantity: BigDecimal) {
        expectedCordaBalance[mint] = quantity
    }

    fun setExpectedSolanaBalance(mint: PublicKey, quantity: BigDecimal) {
        expectedSolanaBalance[mint] = quantity
    }

    fun redeemExpectedBalance(mint: PublicKey, quantity: BigDecimal) {
        val currentSolanaBalance = expectedSolanaBalance.getValue(mint)
        check(currentSolanaBalance >= quantity) {
            "Insufficient expected Solana balance for mint $mint: current $currentSolanaBalance, required $quantity"
        }
        expectedSolanaBalance[mint] = currentSolanaBalance.subtract(quantity)
        expectedCordaBalance[mint] = expectedCordaBalance.getValue(mint).add(quantity)
    }

    fun bridgeExpectedBalance(mint: PublicKey, quantity: BigDecimal) {
        val currentCordaBalance = expectedCordaBalance.getValue(mint)
        check(currentCordaBalance >= quantity) {
            "Insufficient expected Corda balance for mint $mint: current $currentCordaBalance, required $quantity"
        }
        expectedSolanaBalance[mint] = expectedSolanaBalance.getValue(mint).add(quantity)
        expectedCordaBalance[mint] = currentCordaBalance.subtract(quantity)
    }
}
