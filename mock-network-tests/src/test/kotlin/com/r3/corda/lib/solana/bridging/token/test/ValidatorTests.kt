package com.r3.corda.lib.solana.bridging.token.test

import com.r3.corda.lib.solana.bridging.token.flows.tokenProgramId
import com.r3.corda.lib.solana.core.FileSigner
import com.r3.corda.lib.solana.core.TokenProgram.TOKEN_2022
import com.r3.corda.lib.solana.testing.SolanaTestValidator
import com.r3.corda.lib.solana.testing.SolanaTestValidatorExtension
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrThrow
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import net.corda.core.contracts.Amount
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.seconds
import net.corda.solana.notary.client.CordaNotary.PROGRAM_ID
import net.corda.solana.notary.common.SolanaClient
import net.corda.solana.notary.test.NotaryEnvironment
import net.corda.solana.notary.test.TestingSupport
import net.corda.testing.common.internal.eventually
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.DUMMY_BANK_A_NAME
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.Signer
import software.sava.core.accounts.token.TokenAccount
import software.sava.rpc.json.http.client.SolanaRpcClient
import software.sava.rpc.json.http.response.AccountInfo
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

val solanaNotaryName = CordaX500Name("Solana Notary Service", "London", "GB")
val generalNotaryName = CordaX500Name("Notary Service", "Zurich", "CH")

@ExtendWith(SolanaTestValidatorExtension::class)
abstract class ValidatorTests {
    abstract val msftDescriptor: TokenTypeDescriptor
    abstract val aaplDescriptor: TokenTypeDescriptor

    abstract fun StartedMockNode.issue(
        tokenDescriptor: TokenTypeDescriptor,
        amount: BigDecimal,
        notaryName: CordaX500Name,
    ): TokenType

    private lateinit var network: MockNetwork
    protected lateinit var issuingBank: StartedMockNode
    private lateinit var issuingBankParty: Party

    private lateinit var solanaNotary: StartedMockNode
    private lateinit var generalNotary: StartedMockNode
    private lateinit var solanaNotaryParty: Party
    private lateinit var notaryParty: Party

    companion object {
        const val TOKEN_DECIMALS = 3
        const val MSFT_TICKER = "MSFT"
        const val APPL_TICKER = "AAPL"

        // Whole token amounts
        @JvmStatic
        protected val ISSUING_QUANTITY = BigDecimal("2000.000")

        @JvmStatic
        protected val MOVE_QUANTITY_1 = BigDecimal("10.250")

        @JvmStatic
        protected val MOVE_QUANTITY_2 = BigDecimal("10.200")

        @JvmStatic
        protected val MOVE_QUANTITY_3 = BigDecimal("3.025")

        @JvmStatic
        protected val MOVE_TOTAL_QUANTITY = MOVE_QUANTITY_1 + MOVE_QUANTITY_2

        private val issuingBankIdentity = TestIdentity(DUMMY_BANK_A_NAME)
        private val bridgeAuthorityIdentity = TestIdentity(CordaX500Name("Bridge Authority", "New York", "US"))

        private lateinit var solanaNotarySigner: FileSigner
        private lateinit var validator: SolanaTestValidator

        @JvmStatic
        @BeforeAll
        fun configureTestValidator(builder: SolanaTestValidator.Builder) {
            // TODO solana-notary repo is currently using its own copy of SolanaTestValidator and so we have to copy
            //  the code in TestingSupport
            val programFile = Files.createTempFile("corda_notary", ".so")
            TestingSupport::class.java.getResourceAsStream("/net/corda/solana/notary/program/corda_notary.so")!!.use {
                Files.copy(it, programFile, REPLACE_EXISTING)
            }
            builder.bpfProgram(PROGRAM_ID, programFile)
        }

        @BeforeAll
        @JvmStatic
        fun initialise(validator: SolanaTestValidator, @TempDir tempDir: Path) {
            solanaNotarySigner = FileSigner.random(tempDir)
            // TODO solana-notary repo is currently using its own copy of SolanaClient
            val oldClient = SolanaClient(validator.rpcUrl(), validator.websocketUrl())
            oldClient.start()
            with(NotaryEnvironment(oldClient)) {
                initializeProgram()
                createNewCordaNetwork()
                addCordaNotary(solanaNotarySigner.publicKey())
            }
            validator.accounts().airdropSol(solanaNotarySigner.publicKey(), 10)
            this.validator = validator
        }
    }

    @TempDir
    lateinit var custodiedKeysDir: Path

    private lateinit var mintAuthoritySigner: Signer
    protected lateinit var msftTokenMint: PublicKey
    protected lateinit var aaplTokenMint: PublicKey

    protected lateinit var bridgeAuthority: BridgeAuthorityInfo
    protected lateinit var alice: CordaNodeAndSolanaAccounts
    protected lateinit var bob: CordaNodeAndSolanaAccounts

    @BeforeEach
    fun setup() {
        createAccounts()
        startCordaNetwork()
        alice = CordaNodeAndSolanaAccounts.createAndInitialise(
            network,
            ALICE_NAME,
            listOf(msftTokenMint, aaplTokenMint),
            validator
        )
        bob = CordaNodeAndSolanaAccounts.createAndInitialise(
            network,
            BOB_NAME,
            listOf(msftTokenMint, aaplTokenMint),
            validator
        )
        bridgeAuthority = BridgeAuthorityInfo.createAndInitialise(
            network,
            bridgeAuthorityIdentity,
            custodiedKeysDir,
            listOf(alice, bob),
            mapOf(
                msftDescriptor to msftTokenMint,
                aaplDescriptor to aaplTokenMint,
            ),
            mintAuthoritySigner.publicKey(),
            validator,
        )
    }

    @AfterEach
    fun tearDown() {
        stopCordaNetwork()
    }

    private fun createAccounts() {
        mintAuthoritySigner = FileSigner.random(custodiedKeysDir)
        validator.accounts().airdropSol(mintAuthoritySigner.publicKey(), 10)

        msftTokenMint = validator.tokens().createToken(mintAuthoritySigner, TOKEN_2022, decimals = TOKEN_DECIMALS)
        aaplTokenMint = validator.tokens().createToken(mintAuthoritySigner, TOKEN_2022, decimals = TOKEN_DECIMALS)
    }

    private fun startCordaNetwork() {
        network = MockNetwork(
            MockNetworkParameters(
                cordappsForAllNodes = listOf(
                    TestCordapp.findCordapp("com.r3.corda.lib.tokens.contracts"),
                    TestCordapp.findCordapp("com.r3.corda.lib.tokens.workflows"),
                    TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.testing"),
                ),
                notarySpecs = listOf(
                    MockNetworkNotarySpec(generalNotaryName, notaryConfig = createNotaryConfig()),
                    MockNetworkNotarySpec(solanaNotaryName, notaryConfig = createSolanaNotaryConfig()),
                ),
                networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
                threadPerNode = true,
            ),
        )
        issuingBank = network.createPartyNode(issuingBankIdentity.name)
        issuingBankParty = issuingBank.info.legalIdentities.first()
        solanaNotary = network.notaryNodes[1]
        generalNotary = network.notaryNodes[0]
        solanaNotaryParty = solanaNotary.info.legalIdentities[0]
        notaryParty = generalNotary.info.legalIdentities[0]
    }

    fun stopCordaNetwork() {
        if (::network.isInitialized) {
            network.stopNodes()
        }
    }

    private fun createSolanaNotaryConfig(): String =
        """
        validating = false
        solana {
            rpcUrl = "${validator.rpcUrl()}"
            websocketUrl = "${validator.websocketUrl()}"
            notaryKeypairFile = "${solanaNotarySigner.file}"
            custodiedKeysDir = "$custodiedKeysDir"
        }
        """.trimIndent()

    private fun createNotaryConfig(): String =
        """
        validating = false
        """.trimIndent()

    protected fun move(fromParty: StartedMockNode, toParty: Party, quantity: BigDecimal, tokenType: TokenType) =
        fromParty.startFlow(
            MoveFungibleTokens(
                Amount.fromDecimal(quantity, tokenType),
                toParty,
            )
        )

    protected fun ensureLockedAmount(tokenType: TokenType, expectedLockedAmount: BigDecimal) {
        eventually(10.seconds) {
            val lockedFungibleTokens = bridgeAuthority.node.getAllFungibleTokens(issuingBankParty, tokenType).filter {
                it.holder !in listOf(alice.party, bob.party, bridgeAuthority.party) // Locking identity holds tokens
            }
            if (expectedLockedAmount == BigDecimal.ZERO) {
                assertTrue(
                    lockedFungibleTokens.isEmpty(),
                    "Expected no ${tokenType.tokenIdentifier} tokens locked, but some were found"
                )
            } else {
                assertTrue(
                    lockedFungibleTokens.isNotEmpty(),
                    "Expected some ${tokenType.tokenIdentifier} tokens locked, but none were found"
                )
                val lockedAmount = lockedFungibleTokens.sumTokenStatesOrThrow().toDecimal()
                assertEquals(
                    expectedLockedAmount,
                    lockedAmount,
                    "Expected $expectedLockedAmount ${tokenType.tokenIdentifier} tokens locked, but was $lockedAmount"
                )
            }
        }
    }

    protected fun bridgeAndCheck(
        stakeholderInfo: CordaNodeAndSolanaAccounts,
        tokenType: TokenType,
        mint: PublicKey,
        moveQuantity: BigDecimal,
    ) {
        stakeholderInfo.bridgeExpectedBalance(mint, moveQuantity)
        move(stakeholderInfo.node, bridgeAuthority.party, moveQuantity, tokenType).get()
        val party = stakeholderInfo.node.party()
        eventually(duration = 5.seconds) {
            assertEquals(
                stakeholderInfo.expectedCordaBalance[mint],
                stakeholderInfo.node.myTokenBalance(issuingBankParty, tokenType),
                "${party.name} transferred some of ${tokenType.tokenIdentifier} shares",
            )
        }
        // We need to wait for the vault listener to process the newly received token
        eventually(duration = 10.seconds) {
            assertEquals(
                BigDecimal.ZERO,
                bridgeAuthority.node.myTokenBalance(issuingBankParty, tokenType),
                "Bridge Authority has no longer ${tokenType.tokenIdentifier} shares, they are under Locking Identity",
            )
        }
        val fungibleTokens = bridgeAuthority.node.getAllFungibleTokens(issuingBankParty, tokenType)
        assertTrue(fungibleTokens.isNotEmpty()) {
            "There should be at least one ${tokenType.tokenIdentifier} fungible token in Bridge Authority vault"
        }
        val holder = fungibleTokens.map { it.holder }.toSet().singleOrNull()
        requireNotNull(holder) { "Selected fungible tokens should have the same holder" }
        assertTrue(
            holder !in listOf(stakeholderInfo.party, bridgeAuthority.party),
            "Fungible token holder should be Locking Identity, but was $holder"
        )
        val tokenAccount = requireNotNull(stakeholderInfo.mintToAta[mint]) { "Token account must not be null" }
        val accountInfo = validator.client().call(SolanaRpcClient::getAccountInfo, tokenAccount, TokenAccount.FACTORY)
        assertAtaAccount(accountInfo, mint, stakeholderInfo.signer.publicKey())
        ensureSolanaTokenAccountBalance(stakeholderInfo, tokenType, mint)
    }

    protected fun ensureSolanaTokenAccountBalance(
        stakeholderInfo: CordaNodeAndSolanaAccounts,
        tokenType: TokenType,
        mint: PublicKey,
    ) {
        val expectedSolanaBalanceAfter = stakeholderInfo.expectedSolanaBalance[mint]
        val tokenAccount = requireNotNull(stakeholderInfo.mintToAta[mint]) { "Token account must not be null" }
        eventually(duration = 10.seconds) {
            val balance = try {
                validator.getSolanaTokenBalance(tokenAccount)
            } catch (_: Exception) {
                null
            }
            assertThat(balance)
                .describedAs("Solana ${tokenType.tokenIdentifier} token amount equals to $expectedSolanaBalanceAfter")
                .isEqualByComparingTo(expectedSolanaBalanceAfter)
        }
    }

    protected fun redeemAndCheck(
        stakeholderInfo: CordaNodeAndSolanaAccounts,
        mint: PublicKey,
        tokenType: TokenType,
        moveQuantity: BigDecimal,
        ensureCorrectNodeBalance: Boolean = true,
    ) {
        stakeholderInfo.redeemExpectedBalance(mint, moveQuantity)
        val fromTokenAccount = requireNotNull(stakeholderInfo.mintToAta[mint]) {
            "Source token account must not be null"
        }
        val toTokenAccount = bridgeAuthority.redemptionTokenAccountForPartyAndMint(stakeholderInfo.party, mint)
        validator.tokens().transfer(
            stakeholderInfo.signer,
            fromTokenAccount,
            toTokenAccount,
            moveQuantity.toRawAmount()
        )
        val party = stakeholderInfo.node.party()
        eventually(duration = 10.seconds) {
            val balance = validator.getSolanaTokenBalance(toTokenAccount)
            assertEquals(
                0,
                balance.compareTo(moveQuantity),
            ) { "Redemption token account has $balance instead $moveQuantity after transfer - party ${party.name}" }
        }
        if (ensureCorrectNodeBalance) {
            eventually(duration = 10.seconds) {
                assertEquals(
                    stakeholderInfo.expectedCordaBalance[mint],
                    stakeholderInfo.node.myTokenBalance(issuingBankParty, tokenType),
                    "${party.name} received redeemed ${tokenType.tokenIdentifier} shares back",
                )
            }
        }
    }

    private fun StartedMockNode.party() = this.info.legalIdentities.first()

    fun BigDecimal.toRawAmount(): Long = this.toRawAmount(TOKEN_DECIMALS)
}

fun BigDecimal.toRawAmount(decimals: Int): Long {
    return (this * BigDecimal(10L).pow(decimals)).longValueExact()
}

fun SolanaTestValidator.getSolanaTokenBalance(publicKey: PublicKey): BigDecimal {
    return client().call(SolanaRpcClient::getTokenAccountBalance, publicKey).toDecimal()
}

fun assertAtaAccount(
    accountInfo: AccountInfo<TokenAccount>,
    expectedMintAccount: PublicKey,
    expectedOwnerAccount: PublicKey,
) {
    assertEquals(accountInfo.owner, tokenProgramId, "ATA programId should match Token 2022")
    assertThat(accountInfo.data().mint).isEqualTo(expectedMintAccount)
    assertThat(accountInfo.data().owner).isEqualTo(expectedOwnerAccount)
}

interface TokenTypeDescriptor {
    val ticker: String
    val fractionDigits: Int
    val tokenTypeIdentifier: String
}
