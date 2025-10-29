package com.r3.corda.lib.solana.bridging.token.flows

import com.lmax.solana4j.api.PublicKey
import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.utilities.tokenBalance
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.solana.aggregator.common.RpcParams
import net.corda.solana.aggregator.common.Signer
import net.corda.solana.aggregator.common.checkResponse
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import net.corda.testing.solana.SolanaTestValidator
import net.corda.testing.solana.randomKeypairFile
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class FlowTests {
    private lateinit var network: MockNetwork
    private lateinit var alice: StartedMockNode
    private lateinit var bridgingAuthority: StartedMockNode

    private lateinit var solanaNotary: StartedMockNode
    private lateinit var generalNotary: StartedMockNode
    private lateinit var solanaNotaryParty: Party
    private lateinit var notaryParty: Party

    companion object {
        private val aliceIdentity = TestIdentity(ALICE_NAME)
        private val bridgingAuthorityIdentity = TestIdentity(CordaX500Name("Bridging Authority", "New York", "US"))
        private val solanaNotaryName = CordaX500Name("Solana Notary Service", "London", "GB")
        private val generalNotaryName = CordaX500Name("Notary Service", "Zurich", "CH")
        private const val TOKEN_MSFT = "MSFT"
        private const val TOKEN_APPL = "AAPL"
        private const val ISSUING_QUANTITY = 2000L
        private const val MOVE_QUANTITY = 100L

        @TempDir
        lateinit var generalDir: Path

        @TempDir
        lateinit var custodiedKeysDir: Path

        private lateinit var solanaNotaryKeyFile: Path
        private lateinit var solanaNotaryKey: Signer
        private lateinit var mintAuthority: Signer
        private lateinit var testValidator: SolanaTestValidator

        private lateinit var tokenMint: PublicKey
        private lateinit var aliceTokenAccount: PublicKey

        @BeforeAll
        @JvmStatic
        fun startTestValidator() {
            testValidator = SolanaTestValidator()
            solanaNotaryKeyFile = randomKeypairFile(generalDir)
            solanaNotaryKey = Signer.fromFile(solanaNotaryKeyFile)
            mintAuthority = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
            testValidator.start()
            testValidator.defaultNotaryProgramSetup(solanaNotaryKey.account)
            testValidator.fundAccount(10, mintAuthority)

            val accountOwner = Signer.random()

            testValidator.fundAccount(10, accountOwner)

            tokenMint = testValidator.createToken(mintAuthority)
            aliceTokenAccount = testValidator.createTokenAccount(accountOwner, tokenMint)
        }

        @AfterAll
        @JvmStatic
        fun stopTestValidator() {
            if (::testValidator.isInitialized) {
                testValidator.close()
            }
        }
    }

    @BeforeEach
    fun setup() {
        val bridgingContractsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.contracts")
        val bridgingFlowsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.flows")
        val baConfig = mapOf(
            "participants" to mapOf(aliceIdentity.name.toString() to aliceTokenAccount.base58()),
            "mints" to mapOf(TOKEN_MSFT to tokenMint.base58()),
            "mintAuthorities" to mapOf(TOKEN_MSFT to mintAuthority.account.base58()),
            "holdingIdentityLabel" to UUID.randomUUID().toString(),
            "solanaNotaryName" to solanaNotaryName.toString(),
        )
        network = MockNetwork(
            MockNetworkParameters(
                cordappsForAllNodes = listOf(
                    TestCordapp.findCordapp("com.r3.corda.lib.tokens.contracts"),
                    TestCordapp.findCordapp("com.r3.corda.lib.tokens.workflows"),
                ),
                notarySpecs = listOf(
                    MockNetworkNotarySpec(
                        generalNotaryName,
                        notaryConfig = createNotaryConfig(),
                    ),
                    MockNetworkNotarySpec(
                        solanaNotaryName,
                        notaryConfig = createSolanaNotaryConfig(),
                    ),
                ),
                networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
                threadPerNode = true,
            ),
        )

        alice = network.createPartyNode(aliceIdentity.name)
        solanaNotary = network.notaryNodes[1]
        generalNotary = network.notaryNodes[0]
        solanaNotaryParty = solanaNotary.info.legalIdentities[0]
        notaryParty = generalNotary.info.legalIdentities[0]
        bridgingAuthority = network.createNode(
            MockNodeParameters(
                legalName = bridgingAuthorityIdentity.name,
                additionalCordapps = listOf(bridgingFlowsCordapp.withConfig(baConfig), bridgingContractsCordapp),
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        network.stopNodes()
    }

    private fun createSolanaNotaryConfig(): String =
        """
        validating = false
        notaryLegalIdentity = "$solanaNotaryName"
        solana {
            rpcUrl = "${SolanaTestValidator.RPC_URL}"
            notaryKeypairFile = "$solanaNotaryKeyFile"
            custodiedKeysDir = "$custodiedKeysDir"
            programWhitelist = ["${Token2022.PROGRAM_ID}"]
        }
        """.trimIndent()

    private fun createNotaryConfig(): String =
        """
        validating = false
        notaryLegalIdentity = "$generalNotaryName"
        """.trimIndent()

    @Test
    @Suppress("LongMethod")
    fun bridgeTest() {
        val aliceIdentity = alice.info.legalIdentities.first()
        val bridgingAuthorityIdentity = bridgingAuthority.info.legalIdentities.first()

        // Issue 1st Stock on Alice node
        val msft = TokenType(TOKEN_MSFT, 0)
        alice
            .startFlow(IssueSimpleTokenFlow(msft, ISSUING_QUANTITY, generalNotaryName))
            .get()

        // Issue 2nd Stock on Bridge Authority node to verify it remains unaffected
        val appl = TokenType(TOKEN_APPL, 0)
        bridgingAuthority
            .startFlow(IssueSimpleTokenFlow(appl, ISSUING_QUANTITY, generalNotaryName))
            .get()

        // First stock to be bridged - moving from Alice to Bridge Authority
        var msftShares = alice
            .startFlow(QuerySimpleTokensFlow(aliceIdentity, msft))
            .get()
            .first()
            .state.data.tokenType
        val (msftSharesStartCordaQuantity) = alice.services.vaultService.tokenBalance(msftShares)
        assertEquals(ISSUING_QUANTITY, msftSharesStartCordaQuantity)

        val startSolanaBalance = testValidator
            .client
            .getTokenAccountBalance(aliceTokenAccount.base58(), RpcParams())
            .checkResponse("getTokenAccountBalance")
        assertEquals("0", startSolanaBalance!!.amount)

        // Second stock on Bridging Authority - to verify it remains unaffected
        var applShares = bridgingAuthority
            .startFlow(QuerySimpleTokensFlow(bridgingAuthorityIdentity, appl))
            .get()
            .first()
            .state.data.tokenType
        val (applStockStartCordaQuantity) = bridgingAuthority.services.vaultService.tokenBalance(applShares)
        assertEquals(ISSUING_QUANTITY, applStockStartCordaQuantity)

        alice
            .startFlow(MoveFungibleTokens(Amount(MOVE_QUANTITY, msft), bridgingAuthorityIdentity))
            .get()

        val (endCordaQuantity) = alice.services.vaultService.tokenBalance(msftShares)
        assertEquals(
            ISSUING_QUANTITY - MOVE_QUANTITY,
            endCordaQuantity,
            "Alice has transferred some of here MSFT shares",
        )

        msftShares = bridgingAuthority
            .startFlow(QuerySimpleTokensFlow(aliceIdentity, msft))
            .get()
            .first()
            .state.data.tokenType
        val (msftSharesCordaQuantity) = bridgingAuthority.services.vaultService.tokenBalance(msftShares)
        assertEquals(
            MOVE_QUANTITY,
            msftSharesCordaQuantity,
            "MSFT shares have been moved to Bridging Authority",
        )

        // We need to wait for the vault listener to process the newly received token
        Thread.sleep(5000)

        msftShares = bridgingAuthority
            .startFlow(QuerySimpleTokensFlow(aliceIdentity, msft))
            .get()
            .first()
            .state.data.tokenType
        val (finalCordaQuantity) = bridgingAuthority.services.vaultService.tokenBalance(msftShares)
        assertEquals(MOVE_QUANTITY, finalCordaQuantity)
        // TODO the stock should belongs to CI not BA, so this should be assertEquals(0, finalCordaQuantity)

        val token: StateAndRef<FungibleToken>? =
            bridgingAuthority.services.vaultService.queryBy(FungibleToken::class.java).states.firstOrNull {
                it.state.data.amount.token.tokenType == msftShares
            }
        assertNotNull(token)
        val bridgingState: StateAndRef<BridgedFungibleTokenProxy>? =
            bridgingAuthority
                .services.vaultService
                .queryBy(BridgedFungibleTokenProxy::class.java)
                .states
                .firstOrNull()
        assertNotNull(bridgingState)

        val finalSolanaBalance = testValidator
            .client
            .getTokenAccountBalance(aliceTokenAccount.base58(), RpcParams())
            .checkResponse("getTokenAccountBalance")

        assertNotNull(finalSolanaBalance)
        assertEquals(MOVE_QUANTITY.toString(), finalSolanaBalance.amount)

        // Second stock balance remains unchanged/unaffected
        applShares = bridgingAuthority
            .startFlow(QuerySimpleTokensFlow(bridgingAuthorityIdentity, appl))
            .get()
            .first()
            .state.data.tokenType
        val appleSharesFinalCordaQuantity = bridgingAuthority
            .services.vaultService
            .tokenBalance(applShares)
            .quantity
        assertEquals(applStockStartCordaQuantity, appleSharesFinalCordaQuantity)
    }
}
