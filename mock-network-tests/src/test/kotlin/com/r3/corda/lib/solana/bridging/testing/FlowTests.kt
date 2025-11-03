package com.r3.corda.lib.solana.bridging.testing

import com.lmax.solana4j.api.PublicKey
import com.r3.corda.lib.solana.bridging.token.states.BridgedFungibleTokenProxy
import com.r3.corda.lib.solana.bridging.token.testing.QuerySimpleTokensFlow
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrThrow
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.utilities.tokenAmountsByToken
import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

abstract class FlowsTest {
    abstract val msftDescriptor: Descriptor
    abstract val aaplDescriptor: Descriptor

    abstract fun StartedMockNode.issue(tokenDescriptor: Descriptor, amount: Long, notaryName: CordaX500Name): TokenType

    private lateinit var network: MockNetwork
    private lateinit var alice: StartedMockNode
    private lateinit var bridgeAuthority: StartedMockNode

    private lateinit var solanaNotary: StartedMockNode
    private lateinit var generalNotary: StartedMockNode
    private lateinit var solanaNotaryParty: Party
    private lateinit var notaryParty: Party

    companion object {
        const val TOKEN_DECIMALS = 3

        // Whole token amounts
        private const val ISSUING_QUANTITY = 2000L
        private const val MOVE_QUANTITY = 100L

        private val aliceIdentity = TestIdentity(ALICE_NAME)
        private val bridgeAuthorityIdentity = TestIdentity(CordaX500Name("Bridge Authority", "New York", "US"))
        private val solanaNotaryName = CordaX500Name("Solana Notary Service", "London", "GB")
        private val generalNotaryName = CordaX500Name("Notary Service", "Zurich", "CH")
    }

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

    @BeforeEach
    fun setup() {
        startTestValidator()
        startCordaNetwork()
    }

    @AfterEach
    fun tearDown() {
        stopCordaNetwork()
        stopTestValidator()
    }

    private fun startTestValidator() {
        testValidator = SolanaTestValidator()
        solanaNotaryKeyFile = randomKeypairFile(generalDir)
        solanaNotaryKey = Signer.fromFile(solanaNotaryKeyFile)
        mintAuthority = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
        testValidator.start()
        testValidator.defaultNotaryProgramSetup(solanaNotaryKey.account)
        testValidator.fundAccount(10, mintAuthority)

        val accountOwner = Signer.random()

        testValidator.fundAccount(10, accountOwner)

        tokenMint = testValidator.createToken(mintAuthority, decimals = TOKEN_DECIMALS.toByte())
        aliceTokenAccount = testValidator.createTokenAccount(accountOwner, tokenMint)
    }

    fun stopTestValidator() {
        if (::testValidator.isInitialized) {
            testValidator.close()
        }
    }

    private fun startCordaNetwork() {
        val bridgingContractsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.contracts")
        val bridgingFlowsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.flows")
        val baConfig = mapOf(
            "participants" to mapOf(aliceIdentity.name.toString() to aliceTokenAccount.base58()),
            "mints" to mapOf(msftDescriptor.tokenTypeIdentifier to tokenMint.base58()),
            "mintAuthorities" to mapOf(msftDescriptor.tokenTypeIdentifier to mintAuthority.account.base58()),
            "lockingIdentityLabel" to UUID.randomUUID().toString(),
            "solanaNotaryName" to solanaNotaryName.toString(),
        )
        network = MockNetwork(
            MockNetworkParameters(
                cordappsForAllNodes = listOf(
                    TestCordapp.findCordapp("com.r3.corda.lib.tokens.contracts"),
                    TestCordapp.findCordapp("com.r3.corda.lib.tokens.workflows"),
                    TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.testing"),
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
        bridgeAuthority = network.createNode(
            MockNodeParameters(
                legalName = bridgeAuthorityIdentity.name,
                additionalCordapps = listOf(bridgingFlowsCordapp.withConfig(baConfig), bridgingContractsCordapp),
            ),
        )
    }

    fun stopCordaNetwork() {
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

    open fun bridgeTest() {
        val aliceIdentity = alice.info.legalIdentities.first()
        val bridgeAuthorityIdentity = bridgeAuthority.info.legalIdentities.first()

        val msftTokenType = alice.issue(msftDescriptor, ISSUING_QUANTITY, generalNotaryName)
        val aaplTokenType = bridgeAuthority.issue(aaplDescriptor, ISSUING_QUANTITY, generalNotaryName)

        assertEquals(0, getSolanaTokenBalance(aliceTokenAccount), "Nothing on Solana")

        alice
            .startFlow(
                MoveFungibleTokens(
                    Amount.fromDecimal(MOVE_QUANTITY.toBigDecimal(), msftTokenType),
                    bridgeAuthorityIdentity,
                )
            ).get()

        assertEquals(
            ISSUING_QUANTITY - MOVE_QUANTITY,
            alice.myTokenBalance(aliceIdentity, msftTokenType),
            "Alice transferred some of MSFT shares",
        )

        assertEquals(
            MOVE_QUANTITY,
            bridgeAuthority.myTokenBalance(aliceIdentity, msftTokenType),
            "Bridge Authority received MSFT shares",
        )

        // We need to wait for the vault listener to process the newly received token
        Thread.sleep(5000)

        assertEquals(
            0,
            bridgeAuthority.myTokenBalance(aliceIdentity, msftTokenType),
            "Bridge Authority has no longer MSFT shares, they are under Locking Identity"
        )

        val msftFungibleToken = bridgeAuthority
            .getAllFungibleTokens(aliceIdentity, msftTokenType)
            .singleOrNull()
        assertNotNull(msftFungibleToken, "There should be single MSFT fungible token in Bridge Authority vault")
        assertTrue(
            msftFungibleToken.holder !in setOf(aliceIdentity, bridgeAuthorityIdentity),
            "Bridge Authority moved MSFT under Lock Identity (CI) ownership as neither BA nor Alice holds the token",
        ) // Locking Identity is Confidential Identity, and we don't know its identity upfront,
        // so indirect check to by proving no knows participant owns the token
        assertEquals(
            MOVE_QUANTITY,
            msftFungibleToken.amount.toDecimal().longValueExact(),
            "Lock Identity received expected number of MSFT shares",
        )

        val token: StateAndRef<FungibleToken>? = bridgeAuthority.queryStates<FungibleToken>().firstOrNull {
            it.state.data.amount.token.tokenType == msftTokenType
        }
        assertNotNull(token)
        val tokenProxyState = bridgeAuthority.queryStates<BridgedFungibleTokenProxy>().firstOrNull()
        assertNotNull(tokenProxyState, "There should be BridgedFungibleTokenProxy state")

        assertEquals(
            MOVE_QUANTITY,
            getSolanaTokenBalance(aliceTokenAccount),
            "Solana token amount equals Corda bridged amount",
        )

        assertEquals(
            ISSUING_QUANTITY,
            bridgeAuthority.myTokenBalance(bridgeAuthorityIdentity, aaplTokenType),
            "Apple shares balance on Bridge Authority remained unchanged",
        )
    }

    private inline fun <reified T : ContractState> StartedMockNode.queryStates(): List<StateAndRef<T>> {
        return services.vaultService.queryBy(T::class.java).states
    }

    protected fun StartedMockNode.myTokenBalance(issuer: Party, tokenType: TokenType): Long {
        val myIdentity = this.services.myInfo.legalIdentities.first()
        val fungibleTokens = getAllFungibleTokens(issuer, tokenType).filter { it.holder == myIdentity }
        return if (fungibleTokens.isEmpty()) {
            0
        } else {
            fungibleTokens
                .sumTokenStatesOrThrow()
                .toDecimal()
                .longValueExact()
        }
    }

    private fun StartedMockNode.getAllFungibleTokens(issuer: Party, stock: TokenType): List<FungibleToken> {
        val fungibleToken = this
            .startFlow(QuerySimpleTokensFlow(issuer, stock))
            .get()
            .first()
            .state.data.tokenType
        return this.services.vaultService
            .tokenAmountsByToken(fungibleToken)
            .states
            .map { it.state.data }
    }

    private fun getSolanaTokenBalance(publicKey: PublicKey): Long {
        return testValidator
            .client
            .getTokenAccountBalance(publicKey.base58(), RpcParams())
            .checkResponse("getTokenAccountBalance")!!
            .uiAmountString
            .toBigDecimal()
            .longValueExact()
    }
}

interface Descriptor {
    val ticker: String
    val fractionDigits: Int
    val tokenTypeIdentifier: String
}
