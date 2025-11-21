import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.programs.AssociatedTokenProgram
import com.r3.corda.lib.solana.bridging.token.flows.toPublicKey
import com.r3.corda.lib.solana.bridging.token.test.FlowTests.Companion.MSFT_TICKER
import com.r3.corda.lib.solana.bridging.token.test.FlowTests.Companion.TOKEN_DECIMALS
import com.r3.corda.lib.solana.bridging.token.test.SimpleDescriptor
import com.r3.corda.lib.solana.bridging.token.test.TokenTypeDescriptor
import com.r3.corda.lib.solana.bridging.token.testing.IssueSimpleTokenFlow
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.selection.tokenAmountWithIssuerCriteria
import com.r3.corda.lib.tokens.workflows.utilities.rowsToAmount
import com.r3.corda.lib.tokens.workflows.utilities.sumTokenCriteria
import net.corda.core.contracts.Amount
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startFlow
import net.corda.core.node.NetworkParameters
import net.corda.core.utilities.getOrThrow
import net.corda.solana.notary.common.Signer
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.TestIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import net.corda.testing.solana.SolanaTestValidator
import net.corda.testing.solana.randomKeypairFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.IllegalStateException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

class DriverTests {
    private val msftDescriptor: TokenTypeDescriptor = SimpleDescriptor(MSFT_TICKER)
    private lateinit var mintAuthoritySigner: Signer
    private lateinit var bridgeAuthoritySigner: Signer
    private lateinit var bridgeAuthorityWalletFile: Path
    private val aliceIdentity = TestIdentity(ALICE_NAME)
    private val bridgeAuthorityIdentity = TestIdentity(CordaX500Name("Bridge Authority", "New York", "US"))
    private lateinit var aliceSigner: Signer
    private lateinit var aliceBridgeTokenAccount: PublicKey
    private lateinit var aliceRedemptionTokenAccount: PublicKey
    private lateinit var tokenMint: PublicKey
    private val solanaNotaryName = CordaX500Name("Solana Notary Service", "London", "GB")
    private val generalNotaryName = CordaX500Name("Notary Service", "Zurich", "CH")
    val bridgingContractsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.contracts")

    private val solanaNotaryIdentity = TestIdentity(CordaX500Name("Solana Notary Service", "London", "GB"))
    private val generalNotaryIdentity = TestIdentity(CordaX500Name("Notary Service", "Zurich", "CH"))
    private lateinit var solanaNotaryKeyFile: Path
    private lateinit var testValidator: SolanaTestValidator
    private lateinit var solanaNotaryKey: Signer

    @TempDir
    lateinit var custodiedKeysDir: Path

    @TempDir
    lateinit var generalDir: Path

    val bridgingFlowsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.flows")

    private fun startTestValidator() {
        testValidator = SolanaTestValidator()
        solanaNotaryKeyFile = randomKeypairFile(generalDir)
        solanaNotaryKey = Signer.fromFile(solanaNotaryKeyFile)
        mintAuthoritySigner = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
        bridgeAuthorityWalletFile = randomKeypairFile(custodiedKeysDir)
        bridgeAuthoritySigner = Signer.fromFile(bridgeAuthorityWalletFile)
        try {
            testValidator.start()
        } catch (e: IllegalStateException) {
            if (e.message == "Another solana-test-validator instance is already running") {
                // for these tests error is fine, tests create random new accounts
            } else {
                throw e
            }
        }
        testValidator.defaultNotaryProgramSetup(solanaNotaryKey.account)
        testValidator.fundAccount(10, mintAuthoritySigner)
        testValidator.fundAccount(10, bridgeAuthoritySigner)

        aliceSigner = Signer.random()
        testValidator.fundAccount(10, aliceSigner)

        tokenMint = testValidator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        aliceBridgeTokenAccount = AssociatedTokenProgram
            .deriveAddress(
                aliceSigner.account,
                Token2022.PROGRAM_ID.toPublicKey(),
                tokenMint
            ).address()
        aliceRedemptionTokenAccount = testValidator.createTokenAccount(bridgeAuthoritySigner, tokenMint)
    }

    @AfterEach
    fun stopTestValidator() {
        if (::testValidator.isInitialized) {
            testValidator.close()
        }
    }

    @Suppress("LongMethod")
    @Test
    fun test() {
        startTestValidator()

        val baConfig = mapOf(
            "participants" to mapOf(aliceIdentity.name.toString() to aliceSigner.account.base58()),
            "redemptionHolders" to mapOf(aliceRedemptionTokenAccount.base58() to aliceIdentity.name.toString()),
            "bridgeRedemptionAddress" to bridgeAuthoritySigner.account.base58(),
            "mints" to mapOf(msftDescriptor.tokenTypeIdentifier to tokenMint.base58()),
            "mintAuthorities" to mapOf(msftDescriptor.tokenTypeIdentifier to mintAuthoritySigner.account.base58()),
            "lockingIdentityLabel" to UUID.randomUUID().toString(),
            "solanaNotaryName" to solanaNotaryName.toString(),
            "generalNotaryName" to generalNotaryName.toString(),
            "solanaWsUrl" to SolanaTestValidator.WS_URL,
            "solanaRpcUrl" to SolanaTestValidator.RPC_URL,
            "bridgeAuthorityWalletFile" to bridgeAuthorityWalletFile.toString(),
        )
        driver(
            DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = true,
                cordappsForAllNodes = listOf(
                    TestCordapp.findCordapp("com.r3.corda.lib.tokens.contracts"),
                    TestCordapp.findCordapp("com.r3.corda.lib.tokens.workflows"),
                    TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.testing"),
                ),
                notarySpecs = listOf(
                    NotarySpec(generalNotaryIdentity.name, validating = false),
                    NotarySpec(
                        solanaNotaryIdentity.name,
                        customConfig = mapOf<String, Any>(
                            "notary.validating" to false,
                            "notary.notaryLegalIdentity" to "$solanaNotaryName",
                            "notary.solana.rpcUrl" to "$SolanaTestValidator.RPC_URL",
                            "notary.solana.notaryKeypairFile" to "$solanaNotaryKeyFile",
                            "notary.solana.custodiedKeysDir" to "$custodiedKeysDir",
                            "notary.solana.programWhitelist" to "[\"${Token2022.PROGRAM_ID}\"]",
                        ),
                        validating = false,
                    ),
                ),
                networkParameters = NetworkParameters(
                    minimumPlatformVersion = 4,
                    notaries = emptyList(),
                    maxMessageSize = 10485760,
                    maxTransactionSize = 10485760,
                    modifiedTime = Instant.now(),
                    epoch = 1,
                    whitelistedContractImplementations = emptyMap(),
                    eventHorizon = Duration.ofDays(30),
                    packageOwnership = emptyMap(),
                    recoveryMaximumBackupInterval = null,
                    confidentialIdentityMinimumBackupInterval = null,
                ),
            )
        ) {
            val user = User("user1", "test", permissions = setOf("ALL"))

            val node = startNode(
                providedName = aliceIdentity.name,
                rpcUsers = listOf(user)
            ).getOrThrow()

            val bridgeAuthorityNode = startNode(
                NodeParameters(
                    providedName = bridgeAuthorityIdentity.name,
                    rpcUsers = listOf(user),
                ).withAdditionalCordapps(
                    setOf(bridgingFlowsCordapp.withConfig(baConfig), bridgingContractsCordapp)
                )
            ).getOrThrow()

            val issuer = node.nodeInfo.legalIdentities.first()

            val tokenType = TokenType("MSFT_TICKER", 3)
            node.rpc
                .startFlow(
                    ::IssueSimpleTokenFlow,
                    tokenType,
                    100.toBigDecimal(),
                    generalNotaryName,
                ).returnValue
                .getOrThrow()

            val criteria = tokenAmountWithIssuerCriteria(tokenType, issuer).and(sumTokenCriteria())

            val page = node.rpc.vaultQueryByCriteria(criteria, FungibleToken::class.java)
            val balanceBefore: Amount<TokenType> = rowsToAmount(tokenType, page)
            assertEquals(100000, balanceBefore.quantity)
        }
    }
}
