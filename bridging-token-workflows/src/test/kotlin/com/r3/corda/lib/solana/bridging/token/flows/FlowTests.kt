package com.r3.corda.lib.solana.bridging.token.flows

import com.lmax.solana4j.api.PublicKey
import com.r3.corda.lib.solana.bridging.token.states.BridgedAssetState
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.utilities.tokenBalance
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.solana.aggregator.common.RpcParams
import net.corda.solana.aggregator.common.Signer
import net.corda.solana.aggregator.common.checkResponse
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import net.corda.testing.solana.SolanaTestValidator
import net.corda.testing.solana.randomKeypairFile
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.Calendar
import java.util.Date
import java.util.UUID
import java.util.concurrent.ExecutionException

class FlowTests {
    private var network: MockNetwork? = null
    private var company: StartedMockNode? = null
    private var observer: StartedMockNode? = null
    private var shareholder: StartedMockNode? = null
    private var bank: StartedMockNode? = null
    private var bridgingAuthority: StartedMockNode? = null
    private var exDate: Date? = null
    private var payDate: Date? = null

    private var solanaNotary: StartedMockNode? = null
    private var notary: StartedMockNode? = null
    private var solanaNotaryParty: Party? = null
    private var notaryParty: Party? = null

    val companyIdentity = TestIdentity(CordaX500Name("Company", "TestVillage", "US"))
    val shareholderIdentity = TestIdentity(CordaX500Name("Shareholder", "TestVillage", "US"))
    val bankIdentity = TestIdentity(CordaX500Name("Bank", "Rulerland", "US"))
    val observerIdentity = TestIdentity(CordaX500Name("Observer", "Rulerland", "US"))
    val bridgingAuthorityIdentity = TestIdentity(CordaX500Name("Bridging Authority", "New York", "US"))

    companion object {
        const val TOKEN_IDENTIFIER = "SIMPLE"
        const val ISSUING_QUANTITY = 2000L
        val solanaNotaryName = CordaX500Name("Solana Notary Service", "London", "GB")
        val notaryName = CordaX500Name("Notary Service", "Zurich", "CH")

        @ClassRule
        @JvmField
        val generalDir = TemporaryFolder()

        @ClassRule
        @JvmField
        val custodiedKeysDir = TemporaryFolder()

        private lateinit var solanaNotaryKeyFile: Path
        private lateinit var solanaNotaryKey: Signer
        private lateinit var mintAuthority: Signer
        private val tokenAccountOwner = Signer.random()
        private lateinit var testValidator: SolanaTestValidator

        private lateinit var tokenMint: PublicKey
        private lateinit var tokenAccount: PublicKey

        @BeforeClass
        @JvmStatic
        fun startTestValidator() {
            testValidator = SolanaTestValidator()
            solanaNotaryKeyFile = generalDir.randomKeypairFile()
            solanaNotaryKey = Signer.fromFile(solanaNotaryKeyFile)
            mintAuthority = Signer.fromFile(custodiedKeysDir.randomKeypairFile())
            testValidator.start()
            testValidator.defaultNotaryProgramSetup(solanaNotaryKey.account)
            testValidator.fundAccount(10, mintAuthority)
            testValidator.fundAccount(10, tokenAccountOwner)

            val accountOwner = Signer.random()

            testValidator.fundAccount(10, accountOwner)

            tokenMint = testValidator.createToken(mintAuthority)
            tokenAccount = testValidator.createTokenAccount(accountOwner, tokenMint)
        }

        @AfterClass
        @JvmStatic
        fun stopTestValidator() {
            if (::testValidator.isInitialized) {
                testValidator.close()
            }
        }
    }

    @Before
    fun setup() {
        val bridgingContractsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.contracts")
        val bridgingFlowsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.flows")
        val baConfig =
            mapOf(
                "participants" to mapOf(companyIdentity.name.toString() to tokenAccount.base58()),
                "mints" to mapOf(TOKEN_IDENTIFIER to tokenMint.base58()),
                "mintAuthorities" to mapOf(TOKEN_IDENTIFIER to mintAuthority.account.base58()),
                "holdingIdentityLabel" to UUID.randomUUID().toString(),
                "solanaNotaryName" to solanaNotaryName.toString(),
            )
        network =
            MockNetwork(
                MockNetworkParameters(
                    cordappsForAllNodes =
                    listOf(
                        // TODO verify if true - test cordapp (e.g. IssueSimpleTokenFlow) is in test module ...
                        // .. so it seems is available by default to all nodes
                        TestCordapp.findCordapp("com.r3.corda.lib.tokens.contracts"),
                        TestCordapp.findCordapp("com.r3.corda.lib.tokens.workflows"),
                    ),
                    notarySpecs =
                    listOf(
                        MockNetworkNotarySpec(
                            notaryName,
                            notaryConfig = createNotaryConfig(),
                        ),
                        MockNetworkNotarySpec(
                            solanaNotaryName,
                            notaryConfig = createSolanaNotaryConfig(),
                        ),
                    ),
                    // TODO start separately notary to provide specific set of cordapps without bridging ones
                    networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
                    threadPerNode = true,
                ),
            )

        company = network!!.createPartyNode(companyIdentity.name)
        observer = network!!.createPartyNode(observerIdentity.name)
        shareholder = network!!.createPartyNode(shareholderIdentity.name)
        bank = network!!.createPartyNode(bankIdentity.name)
        solanaNotary = network!!.notaryNodes[1]
        notary = network!!.notaryNodes[0]
        solanaNotaryParty = solanaNotary!!.info.legalIdentities[0]
        notaryParty = notary!!.info.legalIdentities[0]
        bridgingAuthority =
            network!!.createNode(
                MockNodeParameters(
                    legalName = bridgingAuthorityIdentity.name,
                    additionalCordapps = listOf(bridgingFlowsCordapp.withConfig(baConfig), bridgingContractsCordapp),
                ),
            )

        // Set execution date as tomorrow
        val c = Calendar.getInstance()
        c.add(Calendar.DATE, 1)
        exDate = c.time

        // Set pay date as the day after tomorrow
        c.add(Calendar.DATE, 1)
        payDate = c.time
        network!!.startNodes()
    }

    @After
    fun tearDown() {
        network!!.stopNodes()
    }

    private fun createSolanaNotaryConfig(): String =
        """
        validating = false
        notaryLegalIdentity = "$solanaNotaryName"
        solana {
            rpcUrl = "${SolanaTestValidator.RPC_URL}"
            notaryKeypairFile = "$solanaNotaryKeyFile"
            custodiedKeysDir = "${custodiedKeysDir.root.toPath()}"
            programWhitelist = ["${Token2022.PROGRAM_ID}"]
        }
        """.trimIndent()

    private fun createNotaryConfig(): String =
        """
        validating = false
        notaryLegalIdentity = "$notaryName"
        """.trimIndent()

    @Test
    @Suppress("LongMethod")
    @Throws(ExecutionException::class, InterruptedException::class)
    fun bridgeTest() {
        // Issue 1st Stock on Company node
        val simple = TokenType("SIMPLE", 0)
        var future =
            company!!.startFlow<SignedTransaction?>(
                IssueSimpleTokenFlow(
                    simple,
                    ISSUING_QUANTITY,
                    notaryName,
                ),
            )
        future.get()
        // Issue 2nd Stock on Bridge Authority node to verify it remains unaffected
        val simple2 = TokenType("SIMPLE_2", 0)
        future =
            bridgingAuthority!!.startFlow(
                IssueSimpleTokenFlow(
                    simple2,
                    ISSUING_QUANTITY,
                    notaryName,
                ),
            )
        future.get()
        // First stock to be bridged - moving from Company to Bridge Authority
        var stockStatePointer =
            company!!
                .startFlow(
                    QuerySimpleTokensFlow(
                        company!!.info.legalIdentities.first(),
                        simple,
                    ),
                ).get()
                .first()
                .state.data.tokenType
        val (startCordaQuantity) = company!!.services.vaultService.tokenBalance(stockStatePointer)
        Assert.assertEquals(ISSUING_QUANTITY, startCordaQuantity)

        val startSolanaBalance =
            testValidator.client
                .getTokenAccountBalance(tokenAccount.base58(), RpcParams())
                .checkResponse("getTokenAccountBalance")
        Assert.assertEquals("0", startSolanaBalance!!.amount)

        // Second stock on Bridging Authority - to verify it remains unaffected
        var stock2StatePointer =
            bridgingAuthority!!
                .startFlow(
                    QuerySimpleTokensFlow(
                        bridgingAuthority!!.info.legalIdentities.first(),
                        simple2,
                    ),
                ).get()
                .first()
                .state.data.tokenType
        var (start2CordaQuantity) = bridgingAuthority!!.services.vaultService.tokenBalance(stock2StatePointer)
        Assert.assertEquals(ISSUING_QUANTITY, start2CordaQuantity)

        future =
            company!!.startFlow(
                MoveFungibleTokens(
                    Amount(100, simple),
                    bridgingAuthority!!.info.legalIdentities[0],
                ),
            )
        future.get()

        // Company has no longer the amount of stocks
        val (endCordaQuantity) = company!!.services.vaultService.tokenBalance(stockStatePointer)
        Assert.assertEquals(1900L, endCordaQuantity)

        // Bridging Authority received the amount of stocks
        stockStatePointer =
            bridgingAuthority!!
                .startFlow(
                    QuerySimpleTokensFlow(
                        company!!.info.legalIdentities.first(),
                        simple,
                    ),
                ).get()
                .first()
                .state.data.tokenType
        val (startBridgingAuthorityCordaQuantity) =
            bridgingAuthority!!.services.vaultService.tokenBalance(
                stockStatePointer,
            )
        Assert.assertEquals(100L, startBridgingAuthorityCordaQuantity)

        // We need to wait for the vault listener to process the newly received token
        Thread.sleep(10000)

        stockStatePointer =
            bridgingAuthority!!
                .startFlow(
                    QuerySimpleTokensFlow(
                        company!!.info.legalIdentities.first(),
                        simple,
                    ),
                ).get()
                .first()
                .state.data.tokenType
        val (finalCordaQuantity) = bridgingAuthority!!.services.vaultService.tokenBalance(stockStatePointer)
        Assert.assertEquals(
            100,
            finalCordaQuantity,
        )

        val token: StateAndRef<FungibleToken>? =
            bridgingAuthority!!.services.vaultService.queryBy(FungibleToken::class.java).states.firstOrNull {
                it.state.data.amount.token.tokenType == stockStatePointer
            }
        Assert.assertNotNull(token)
        val bridgingState: StateAndRef<BridgedAssetState>? =
            bridgingAuthority!!
                .services.vaultService
                .queryBy(BridgedAssetState::class.java)
                .states
                .firstOrNull()
        Assert.assertNotNull(bridgingState)

        val finalSolanaBalance =
            testValidator.client
                .getTokenAccountBalance(tokenAccount.base58(), RpcParams())
                .checkResponse("getTokenAccountBalance")

        Assert.assertEquals("100", finalSolanaBalance!!.amount)

        // Second stock balance remains unchanged /unaffected
        stock2StatePointer =
            bridgingAuthority!!
                .startFlow(
                    QuerySimpleTokensFlow(
                        bridgingAuthority!!.info.legalIdentities.first(),
                        simple2,
                    ),
                ).get()
                .first()
                .state.data.tokenType
        start2CordaQuantity =
            bridgingAuthority!!
                .services.vaultService
                .tokenBalance(stock2StatePointer)
                .quantity
        Assert.assertEquals(ISSUING_QUANTITY, start2CordaQuantity)
    }
}
