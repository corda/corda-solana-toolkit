package com.r3.corda.lib.solana.bridging.token.test

import com.lmax.solana4j.api.PublicKey
import net.corda.core.identity.Party
import net.corda.solana.notary.common.Signer
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import net.corda.testing.solana.randomKeypairFile
import java.nio.file.Path
import java.util.*

data class BridgeAuthorityInfo(
    val node: StartedMockNode,
    val party: Party,
    val mintWalletFile: Path,
    val mintWallet: Signer,
    val redemptionWallets: Map<Party, Signer>,
    private val redemptionTokenAccounts: Map<Party, List<AssociatedTokenAccountInfo>>,
) {
    companion object {
        fun createAndInitialise(
            network: MockNetwork,
            identity: TestIdentity,
            keyDir: Path,
            parties: List<CordaNodeAndSolanaAccounts>,
            tokenDescriptorToMint: Map<TokenTypeDescriptor, PublicKey>,
            mintAuthority: PublicKey,
            testValidator: SolanaTestValidator,
        ): BridgeAuthorityInfo {
            val bridgingContractsCordapp =
                TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.contracts")
            val bridgingFlowsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.flows")
            val redemptionWallets = parties.associate { it.party to Signer.fromFile(randomKeypairFile(keyDir)) }
            redemptionWallets.values.forEach {
                testValidator.fundAccount(10, it)
            }
            val mintWalletFile = randomKeypairFile(keyDir)
            val mintWallet = Signer.fromFile(mintWalletFile)
            testValidator.fundAccount(10, mintWallet)
            val baConfig = mapOf(
                "participants" to parties.associate { it.party.name.toString() to it.signer.account.base58() },
                "redemptionWalletAccountToHolder" to redemptionWallets
                    .map {
                        it.value.account.base58() to it.key.name.toString()
                    }.toMap(),
                "mintsWithAuthorities" to tokenDescriptorToMint
                    .map {
                        it.key.tokenTypeIdentifier to mapOf(
                            "tokenMint" to it.value.base58(),
                            "mintAuthority" to mintAuthority.base58(),
                        )
                    }.toMap(),
                "lockingIdentityLabel" to UUID.randomUUID().toString(),
                "solanaNotaryName" to solanaNotaryName.toString(),
                "generalNotaryName" to generalNotaryName.toString(),
                "solanaWsUrl" to testValidator.wsUrl,
                "solanaRpcUrl" to testValidator.rpcUrl,
                "bridgeAuthorityWalletFile" to mintWalletFile.toString(),
                // Set to very height value interval to effectively disable redemption in tests in order
                // to validate "core" real time processing and Sava listeners
                "redemptionCheckIntervalSeconds" to 300, // 5 minutes
            )
            val node = network.createNode(
                MockNodeParameters(
                    legalName = identity.name,
                    additionalCordapps = listOf(
                        bridgingFlowsCordapp.withConfig(baConfig),
                        bridgingContractsCordapp
                    ),
                ),
            )
            return BridgeAuthorityInfo(
                node = node,
                party = node.info.legalIdentities.first(),
                mintWalletFile = mintWalletFile,
                mintWallet = mintWallet,
                redemptionWallets = redemptionWallets,
                redemptionTokenAccounts = parties.associate { info ->
                    info.party to tokenDescriptorToMint.map { (_, mint) ->
                        AssociatedTokenAccountInfo(
                            mint = mint,
                            tokenAccount = testValidator.createTokenAccount(
                                redemptionWallets[info.party]!!,
                                mint
                            )
                        )
                    }
                }
            )
        }
    }

    fun redemptionTokenAccountForPartyAndMint(
        party: Party,
        mint: PublicKey,
    ): PublicKey {
        val ataInfos = requireNotNull(redemptionTokenAccounts[party]) {
            "No redemption token accounts found for party ${party.name}"
        }
        return requireNotNull(ataInfos.firstOrNull { it.mint == mint }?.tokenAccount) {
            "No redemption token account found for party ${party.name} and mint $mint"
        }
    }
}
