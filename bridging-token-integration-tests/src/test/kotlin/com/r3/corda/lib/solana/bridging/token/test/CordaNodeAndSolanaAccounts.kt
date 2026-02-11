package com.r3.corda.lib.solana.bridging.token.test

import com.r3.corda.lib.solana.bridging.token.flows.tokenProgramId
import com.r3.corda.lib.solana.core.SolanaUtils
import com.r3.corda.lib.solana.testing.SolanaTestValidator
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.Signer
import software.sava.core.accounts.SolanaAccounts
import software.sava.solana.programs.token.AssociatedTokenProgram
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
            val signer = SolanaUtils.randomSigner()
            testValidator.accounts().airdropSol(signer.publicKey(), 10)
            val node = network.createPartyNode(cordaName)
            return CordaNodeAndSolanaAccounts(
                signer = signer,
                mintToAta = mints.associateWith { mint ->
                    AssociatedTokenProgram
                        .findATA(SolanaAccounts.MAIN_NET, signer.publicKey(), tokenProgramId, mint)
                        .publicKey()
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
