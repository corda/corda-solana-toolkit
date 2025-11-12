package com.r3.corda.lib.solana.bridging.token.test

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.programs.Token2022Program
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
import net.corda.solana.aggregator.common.sendAndConfirm
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.DUMMY_BANK_A_NAME
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import net.corda.testing.solana.SolanaTestValidator
import net.corda.testing.solana.randomKeypairFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.*

abstract class FlowsTest {
    abstract val msftDescriptor: TokenTypeDescriptor
    abstract val aaplDescriptor: TokenTypeDescriptor

    abstract fun StartedMockNode.issue(
        tokenDescriptor: TokenTypeDescriptor,
        amount: BigDecimal,
        notaryName: CordaX500Name,
    ): TokenType

    private lateinit var network: MockNetwork
    private lateinit var issuingBank: StartedMockNode
    private lateinit var alice: StartedMockNode
    private lateinit var bridgeAuthority: StartedMockNode
    private lateinit var aliceParty: Party
    private lateinit var issuingBankParty: Party
    private lateinit var bridgeAuthorityParty: Party

    private lateinit var solanaNotary: StartedMockNode
    private lateinit var generalNotary: StartedMockNode
    private lateinit var solanaNotaryParty: Party
    private lateinit var notaryParty: Party

    companion object {
        const val TOKEN_DECIMALS = 3
        const val MSFT_TICKER = "MSFT"
        const val APPL_TICKER = "AAPL"

        // Whole token amounts
        private val ISSUING_QUANTITY = BigDecimal("2000.000")
        private val MOVE_QUANTITY = BigDecimal("10.250")

        private val aliceIdentity = TestIdentity(ALICE_NAME)
        private val issuingBankIdentity = TestIdentity(DUMMY_BANK_A_NAME)
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
    private lateinit var mintAuthoritySigner: Signer
    private lateinit var bridgeAuthoritySigner: Signer
    private lateinit var testValidator: SolanaTestValidator

    private lateinit var tokenMint: PublicKey
    private lateinit var aliceSigner: Signer
    private lateinit var aliceBridgeTokenAccoount: PublicKey
    private lateinit var aliceRedemptionTokenAccount: PublicKey

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
        mintAuthoritySigner = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
        bridgeAuthoritySigner = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
        testValidator.start()
        testValidator.defaultNotaryProgramSetup(solanaNotaryKey.account)
        testValidator.fundAccount(10, mintAuthoritySigner)
        testValidator.fundAccount(10, bridgeAuthoritySigner)

        aliceSigner = Signer.random()
        testValidator.fundAccount(10, aliceSigner)

        tokenMint = testValidator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        aliceBridgeTokenAccoount = testValidator.createTokenAccount(aliceSigner, tokenMint)
        aliceRedemptionTokenAccount = testValidator.createTokenAccount(bridgeAuthoritySigner, tokenMint)
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
            "participants" to mapOf(aliceIdentity.name.toString() to aliceBridgeTokenAccoount.base58()),
            "redemptionHolders" to mapOf(aliceRedemptionTokenAccount.base58() to aliceIdentity.name.toString()),
            "bridgeRedemptionWallet" to bridgeAuthoritySigner.account.base58(),
            "mints" to mapOf(msftDescriptor.tokenTypeIdentifier to tokenMint.base58()),
            "mintAuthorities" to mapOf(msftDescriptor.tokenTypeIdentifier to mintAuthoritySigner.account.base58()),
            "lockingIdentityLabel" to UUID.randomUUID().toString(),
            "solanaNotaryName" to solanaNotaryName.toString(),
            "solanaWsUrl" to SolanaTestValidator.WS_URL,
            "solanaRpcUrl" to SolanaTestValidator.RPC_URL,
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
        aliceParty = alice.info.legalIdentities.first()
        issuingBank = network.createPartyNode(issuingBankIdentity.name)
        issuingBankParty = issuingBank.info.legalIdentities.first()
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
        bridgeAuthorityParty = bridgeAuthority.info.legalIdentities.first()
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

    private fun move(fromParty: StartedMockNode, toParty: Party, quantity: BigDecimal, tokenType: TokenType) = fromParty
        .startFlow(
            MoveFungibleTokens(
                Amount.fromDecimal(quantity, tokenType),
                toParty,
            )
        )

    @Test
    fun bridgeTest() {
        val msftTokenType = issuingBank.issue(msftDescriptor, ISSUING_QUANTITY, generalNotaryName)
        val aaplTokenType = issuingBank.issue(aaplDescriptor, ISSUING_QUANTITY, generalNotaryName)

        assertEquals(BigDecimal.ZERO, getSolanaTokenBalance(aliceBridgeTokenAccoount), "Nothing on Solana")

        move(issuingBank, aliceParty, ISSUING_QUANTITY, msftTokenType).get()
        move(issuingBank, aliceParty, ISSUING_QUANTITY, aaplTokenType).get()
        move(alice, bridgeAuthorityParty, MOVE_QUANTITY, msftTokenType).get()

        assertEquals(
            ISSUING_QUANTITY - MOVE_QUANTITY,
            alice.myTokenBalance(issuingBankParty, msftTokenType),
            "Alice transferred some of MSFT shares",
        )

        assertEquals(
            MOVE_QUANTITY,
            bridgeAuthority.myTokenBalance(issuingBankParty, msftTokenType),
            "Bridge Authority received MSFT shares",
        )

        // We need to wait for the vault listener to process the newly received token
        Thread.sleep(5000)

        assertEquals(
            BigDecimal.ZERO,
            bridgeAuthority.myTokenBalance(issuingBankParty, msftTokenType),
            "Bridge Authority has no longer MSFT shares, they are under Locking Identity"
        )

        val msftFungibleToken = bridgeAuthority
            .getAllFungibleTokens(issuingBankParty, msftTokenType)
            .singleOrNull()
        assertNotNull(msftFungibleToken, "There should be single MSFT fungible token in Bridge Authority vault")
        assertTrue(
            msftFungibleToken.holder !in setOf(aliceParty, bridgeAuthorityParty),
            "Bridge Authority moved MSFT under Lock Identity (CI) ownership as neither BA nor Alice holds the token",
        ) // Locking Identity is Confidential Identity, and we don't know its identity upfront,
        // so indirect check to by proving no knows participant owns the token
        assertEquals(
            MOVE_QUANTITY,
            msftFungibleToken.amount.toDecimal(),
            "Lock Identity received expected number of MSFT shares",
        )

        val token: StateAndRef<FungibleToken>? = bridgeAuthority.queryStates<FungibleToken>().firstOrNull {
            it.state.data.amount.token.tokenType == msftTokenType
        }
        assertNotNull(token)

        // SPL Token RPC returns decimal strings with trailing zeros trimmed,
        // BigDecimal.equals is scale-sensitive (1.0 != 1.00), so we compare numeric value instead.
        assertThat(getSolanaTokenBalance(aliceBridgeTokenAccoount))
            .describedAs("Solana token amount numerically equals Corda bridged amount")
            .isEqualByComparingTo(MOVE_QUANTITY)

        // Simulate redemption transfer for Alice account on Solana
        transfer(
            aliceSigner,
            aliceBridgeTokenAccoount,
            aliceRedemptionTokenAccount,
            MOVE_QUANTITY.toRawAmount()
        )
        // We need to wait for the websocket listener to process the newly received event
        Thread.sleep(5000)

        assertEquals(
            ISSUING_QUANTITY,
            alice.myTokenBalance(issuingBankParty, msftTokenType),
            "Alice received redeemed MSFT shares back",
        )
        val msftFungibleTokens = bridgeAuthority.getAllFungibleTokens(issuingBankParty, msftTokenType)
        assertTrue(msftFungibleTokens.isEmpty(), "No  MSFT shares left in Bridge Authority vault")
    }

    private inline fun <reified T : ContractState> StartedMockNode.queryStates(): List<StateAndRef<T>> {
        return services.vaultService.queryBy(T::class.java).states
    }

    protected fun StartedMockNode.myTokenBalance(issuer: Party, tokenType: TokenType): BigDecimal {
        val myIdentity = this.services.myInfo.legalIdentities.first()
        val fungibleTokens = getAllFungibleTokens(issuer, tokenType).filter { it.holder == myIdentity }
        return if (fungibleTokens.isEmpty()) {
            BigDecimal.ZERO
        } else {
            fungibleTokens
                .sumTokenStatesOrThrow()
                .toDecimal()
        }
    }

    private fun StartedMockNode.getAllFungibleTokens(issuer: Party, stock: TokenType): List<FungibleToken> {
        val fungibleTokenType = this
            .startFlow(QuerySimpleTokensFlow(issuer, stock))
            .get()
            .firstOrNull() ?: return emptyList()
        return this.services.vaultService
            .tokenAmountsByToken(fungibleTokenType.state.data.tokenType)
            .states
            .map { it.state.data }
    }

    private fun transfer(fromOwner: Signer, fromTokenAccount: PublicKey, toTokenAccount: PublicKey, amount: Long) {
        testValidator.client.sendAndConfirm(
            { txBuilder ->
                Token2022Program.factory(txBuilder).transfer(
                    fromTokenAccount,
                    toTokenAccount,
                    fromOwner.account,
                    amount,
                    emptyList()
                )
            },
            fromOwner,
            emptyList(),
            RpcParams()
        )
    }

    private fun BigDecimal.toRawAmount(): Long {
        return (this * BigDecimal(1000L)).toLong()
    }

    private fun getSolanaTokenBalance(publicKey: PublicKey): BigDecimal {
        return testValidator
            .client
            .getTokenAccountBalance(publicKey.base58(), RpcParams())
            .checkResponse("getTokenAccountBalance")!!
            .uiAmountString
            .toBigDecimal()
    }
}

interface TokenTypeDescriptor {
    val ticker: String
    val fractionDigits: Int
    val tokenTypeIdentifier: String
}
