package com.r3.corda.lib.solana.bridging.token.test

import com.r3.corda.lib.solana.core.FileSigner
import com.r3.corda.lib.solana.testing.SolanaTestValidator
import net.corda.core.identity.Party
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.Signer
import java.nio.file.Path

data class BridgeAuthorityInfo(
    val node: StartedMockNode,
    val party: Party,
    val mintWallet: FileSigner,
    val redemptionWallets: Map<Party, Signer>,
    private val redemptionTokenAccounts: Map<Party, List<AssociatedTokenAccountInfo>>,
) {
    companion object {
        @Suppress("LongMethod")
        fun createAndInitialise(
            network: MockNetwork,
            identity: TestIdentity,
            keyDir: Path,
            parties: List<CordaNodeAndSolanaAccounts>,
            tokenDescriptorToMint: Map<TokenTypeDescriptor, PublicKey>,
            mintAuthority: PublicKey,
            testValidator: SolanaTestValidator,
            redemptionCheckIntervalSeconds: Int,
        ): BridgeAuthorityInfo {
            val bridgingContractsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.contracts")
            val bridgingFlowsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.flows")
            val redemptionWallets = parties.associateBy(
                { it.party },
                {
                    val signer = FileSigner.random(keyDir)
                    testValidator.accounts().airdropSol(signer.publicKey(), 10)
                    signer
                }
            )
            val mintWallet = FileSigner.random(keyDir)
            testValidator.accounts().airdropSol(mintWallet.publicKey(), 10)
            val baConfig = mapOf(
                "participants" to parties.associate { it.party.name.toString() to it.signer.publicKey().toBase58() },
                "redemptionWalletAccountToHolder" to redemptionWallets
                    .map { it.value.publicKey().toBase58() to it.key.name.toString() }
                    .toMap(),
                "mintsWithAuthorities" to tokenDescriptorToMint
                    .map {
                        it.key.tokenTypeIdentifier to mapOf(
                            "tokenMint" to it.value.toBase58(),
                            "mintAuthority" to mintAuthority.toBase58(),
                        )
                    }.toMap(),
                "solanaNotaryName" to solanaNotaryName.toString(),
                "generalNotaryName" to generalNotaryName.toString(),
                "solanaRpcUrl" to "${testValidator.rpcUrl()}",
                "solanaWebsocketUrl" to "${testValidator.websocketUrl()}",
                "bridgeAuthorityWalletFile" to mintWallet.file.toString(),
                "redemptionCheckIntervalSeconds" to redemptionCheckIntervalSeconds,
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
                mintWallet = mintWallet,
                redemptionWallets = redemptionWallets,
                redemptionTokenAccounts = parties.associate { info ->
                    info.party to tokenDescriptorToMint.map { (_, mint) ->
                        AssociatedTokenAccountInfo(
                            mint = mint,
                            tokenAccount = testValidator.tokens().createTokenAccount(
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
