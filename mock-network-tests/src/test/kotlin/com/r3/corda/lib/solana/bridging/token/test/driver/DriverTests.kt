package com.r3.corda.lib.solana.bridging.token.test.driver

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.programs.AssociatedTokenProgram
import com.r3.corda.lib.solana.bridging.token.flows.toPublicKey
import com.r3.corda.lib.solana.bridging.token.test.FlowTests.Companion.APPL_TICKER
import com.r3.corda.lib.solana.bridging.token.test.FlowTests.Companion.MSFT_TICKER
import com.r3.corda.lib.solana.bridging.token.test.SimpleDescriptor
import com.r3.corda.lib.solana.bridging.token.test.TokenTypeDescriptor
import com.r3.corda.lib.solana.bridging.token.test.assertAtaAccount
import com.r3.corda.lib.solana.bridging.token.test.getAccountInfo
import com.r3.corda.lib.solana.bridging.token.test.getSolanaTokenBalance
import com.r3.corda.lib.solana.bridging.token.test.toRawAmount
import com.r3.corda.lib.solana.bridging.token.test.transfer
import com.r3.corda.lib.solana.bridging.token.testing.IssueSimpleTokenFlow
import com.r3.corda.lib.solana.bridging.token.testing.QuerySimpleTokensFlow
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrThrow
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.NetworkParameters
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.seconds
import net.corda.solana.notary.common.Signer
import net.corda.solana.sdk.Token2022
import net.corda.testing.common.internal.eventually
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.driver.DriverDSL
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.NodeParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import net.corda.testing.solana.SolanaTestValidator
import net.corda.testing.solana.randomKeypairFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import java.lang.IllegalStateException
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.collections.contains

class Participant(val name: CordaX500Name) {
    val wallet: Signer = Signer.random()
    val walletAccount: PublicKey
        get() = wallet.account
    val walletAccountAsString: String
        get() = walletAccount.base58()
    lateinit var node: NodeHandle

    fun startNode(ext: DriverDSL, vararg additionalCordapps: TestCordapp) {
        node = ext
            .startNode(
                NodeParameters(providedName = name, rpcUsers = listOf(user))
                    .withAdditionalCordapps(additionalCordapps.toSet())
            ).getOrThrow()
    }

    val identity: Party
        get() = node.nodeInfo.legalIdentities.first()
    val nameAsString: String
        get() = name.toString()

    inner class StockAccounts {
        lateinit var tokenDescriptor: TokenTypeDescriptor
        lateinit var cordaTokenType: TokenType
        val cordaTokenIdentifier: String
            get() = cordaTokenType.tokenIdentifier
        lateinit var tokenAccount: PublicKey

        fun deriveTokenAccountNameFrom(tokenMint: PublicKey) {
            tokenMintAccount = tokenMint
            tokenAccount = AssociatedTokenProgram
                .deriveAddress(
                    walletAccount,
                    Token2022.PROGRAM_ID.toPublicKey(),
                    tokenMint
                ).address()
        }

        lateinit var redemptionTokenAccount: PublicKey
        lateinit var tokenMintAccount: PublicKey
    }

    val microsoft = StockAccounts()
    val apple = StockAccounts()

    val user = User("user1", "test", permissions = setOf("ALL"))
}

class DriverTests {
    companion object {
        const val TOKEN_DECIMALS = 3
        private val ISSUING_QUANTITY = BigDecimal("2000.000")
        private val MOVE_QUANTITY = BigDecimal("10.250")
        private val networkParameters = NetworkParameters(
            minimumPlatformVersion = 4,
            notaries = emptyList(),
            maxMessageSize = 10485760,
            maxTransactionSize = 10485760,
            modifiedTime = Instant.now(),
            epoch = 1,
            whitelistedContractImplementations = emptyMap(),
            eventHorizon = Duration.ofDays(30),
            packageOwnership = emptyMap(),
        )
    }

    private val alice: Participant = Participant(ALICE_NAME)
    private val bob: Participant = Participant(BOB_NAME)
    private val bridgeAuthority: Participant = Participant(CordaX500Name("Bridge Authority", "New York", "US"))

    private val msftDescriptor: TokenTypeDescriptor = SimpleDescriptor(MSFT_TICKER)
    private val appleDescriptor: TokenTypeDescriptor = SimpleDescriptor(APPL_TICKER)
    private lateinit var mintAuthoritySigner: Signer
    private lateinit var bridgeAuthorityRedemptionSignerForAlice: Signer
    private lateinit var bridgeAuthorityRedemptionSignerForBob: Signer
    private lateinit var bridgeAuthorityWalletFile: Path
    private lateinit var bridgeAuthorityRedemptionForBobWalletFile: Path
    private lateinit var msftTokenMint: PublicKey
    private lateinit var appleTokenMint: PublicKey
    private val solanaNotaryName = CordaX500Name("Solana Notary Service", "London", "GB")
    private val generalNotaryName = CordaX500Name("Notary Service", "Zurich", "CH")

    private lateinit var solanaNotaryKeyFile: Path
    private val validator = SolanaTestValidator()
    private lateinit var solanaNotaryKey: Signer

    @TempDir
    lateinit var custodiedKeysDir: Path

    @TempDir
    lateinit var generalDir: Path

    val cordappsForAllNodes = listOf(
        TestCordapp.findCordapp("com.r3.corda.lib.tokens.contracts"),
        TestCordapp.findCordapp("com.r3.corda.lib.tokens.workflows"),
        TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.testing"),
    )
    val bridgingContractsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.contracts")
    val bridgingFlowsCordapp: TestCordapp by lazy {
        TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.flows").withConfig(
            mapOf(
                "participants" to mapOf(
                    alice.nameAsString to alice.walletAccountAsString,
                    bob.nameAsString to bob.walletAccountAsString
                ),
                "redemptionWalletAccountToHolder" to mapOf(
                    bridgeAuthorityRedemptionSignerForAlice.account.base58() to alice.nameAsString,
                    bridgeAuthorityRedemptionSignerForBob.account.base58() to bob.nameAsString,
                ),
                "mintsWithAuthorities" to mapOf(
                    msftDescriptor.tokenTypeIdentifier to
                        mapOf(
                            "tokenMint" to msftTokenMint.base58(),
                            "mintAuthority" to mintAuthoritySigner.account.base58()
                        ),
                    appleDescriptor.tokenTypeIdentifier to
                        mapOf(
                            "tokenMint" to appleTokenMint.base58(),
                            "mintAuthority" to mintAuthoritySigner.account.base58()
                        )
                ),
                "lockingIdentityLabel" to UUID.randomUUID().toString(),
                "solanaNotaryName" to solanaNotaryName.toString(),
                "generalNotaryName" to generalNotaryName.toString(),
                "solanaWsUrl" to SolanaTestValidator.WS_URL,
                "solanaRpcUrl" to SolanaTestValidator.RPC_URL,
                "bridgeAuthorityWalletFile" to bridgeAuthorityWalletFile.toString(),
            )
        )
    }

    val solanaNotaryConfig: Map<String, Any> by lazy {
        mapOf<String, Any>(
            "notary" to mapOf(
                "validating" to false,
                // "serviceLegalName" doesn't work with Driver, because it needs a distributed key that is not created
                "solana" to mapOf(
                    "rpcUrl" to SolanaTestValidator.RPC_URL,
                    "notaryKeypairFile" to "$solanaNotaryKeyFile",
                    "custodiedKeysDir" to "$custodiedKeysDir",
                    "programWhitelist" to listOf(Token2022.PROGRAM_ID.toPublicKey().base58()),
                )
            )
        )
    }

    @BeforeEach
    fun startTestValidator() {
        solanaNotaryKeyFile = randomKeypairFile(generalDir)
        solanaNotaryKey = Signer.fromFile(solanaNotaryKeyFile)
        mintAuthoritySigner = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
        bridgeAuthorityWalletFile = randomKeypairFile(custodiedKeysDir)
        bridgeAuthorityRedemptionForBobWalletFile = randomKeypairFile(custodiedKeysDir)
        bridgeAuthorityRedemptionSignerForAlice = Signer.fromFile(bridgeAuthorityWalletFile)
        bridgeAuthorityRedemptionSignerForBob = Signer.fromFile(bridgeAuthorityRedemptionForBobWalletFile)
        try {
            validator.start()
        } catch (e: IllegalStateException) {
            if (e.message == "Another solana-test-validator instance is already running") {
                // for these tests error is fine, tests create random new accounts
            } else {
                throw e
            }
        }
        validator.defaultNotaryProgramSetup(solanaNotaryKey.account)
        setOf(
            mintAuthoritySigner,
            alice.wallet,
            bob.wallet,
            bridgeAuthorityRedemptionSignerForAlice,
            bridgeAuthorityRedemptionSignerForBob,
        ).forEach {
            validator.fundAccount(10, it)
        }

        msftTokenMint = validator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        setOf(alice.microsoft, bob.microsoft).forEach {
            it.tokenDescriptor = msftDescriptor
            it.deriveTokenAccountNameFrom(msftTokenMint)
        }

        alice.microsoft.redemptionTokenAccount =
            validator.createTokenAccount(bridgeAuthorityRedemptionSignerForAlice, msftTokenMint)
        bob.microsoft.redemptionTokenAccount =
            validator.createTokenAccount(bridgeAuthorityRedemptionSignerForBob, msftTokenMint)

        appleTokenMint = validator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        setOf(alice.apple, bob.apple).forEach {
            it.tokenDescriptor = appleDescriptor
            it.deriveTokenAccountNameFrom(appleTokenMint)
        }
        alice.apple.redemptionTokenAccount = validator.createTokenAccount(
            bridgeAuthorityRedemptionSignerForAlice,
            appleTokenMint
        )
        bob.apple.redemptionTokenAccount = validator.createTokenAccount(
            bridgeAuthorityRedemptionSignerForBob,
            appleTokenMint
        )
        setOf(
            alice.microsoft.redemptionTokenAccount,
            bob.microsoft.redemptionTokenAccount,
            alice.apple.redemptionTokenAccount,
            bob.apple.redemptionTokenAccount
        ).forEach { account ->
            validator.fundAccount(10, account)
        }
    }

    @AfterEach
    fun stopTestValidator() {
        validator.close()
    }

    @Test
    fun driverBridgeAndRedemptionTest() {
        driver(
            DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = false,
                cordappsForAllNodes = cordappsForAllNodes,
                notarySpecs = listOf(
                    NotarySpec(generalNotaryName, validating = false, startInProcess = false),
                    NotarySpec(solanaNotaryName, solanaNotaryConfig, startInProcess = false),
                ),
                networkParameters = networkParameters,
            )
        ) {
            bridgeAuthority.startNode(this, bridgingFlowsCordapp, bridgingContractsCordapp)
            alice.startNode(this)
            bob.startNode(this)

            for (participant in setOf(alice, bob)) {
                for (stocks in setOf(participant.microsoft, participant.apple)) {
                    val cordaTokenType = participant.issue(stocks.tokenDescriptor, ISSUING_QUANTITY, generalNotaryName)
                    stocks.cordaTokenType = cordaTokenType
                    assertNull(
                        validator.getAccountInfo(stocks.tokenAccount),
                        "${participant.name} ATA should not be created yet",
                    )
                }
            }
            bridge(alice, alice.microsoft, MOVE_QUANTITY)
            // Bob bridges and redeems exactly the same amount in one go
            bridge(bob, bob.microsoft, MOVE_QUANTITY)
            bridge(alice, alice.apple, MOVE_QUANTITY)
            redeem(alice, alice.microsoft, MOVE_QUANTITY)
            redeem(bob, bob.microsoft, MOVE_QUANTITY)
            // Alice redeems a smaller quantity of APPLE to leave a change for CI
            redeem(alice, alice.apple, MOVE_QUANTITY.minus(BigDecimal.ONE))
        }
    }

    private fun bridge(participant: Participant, stockAccounts: Participant.StockAccounts, quantity: BigDecimal) {
        participant.move(bridgeAuthority.identity, quantity, stockAccounts.cordaTokenType)

        val issuingBankParty =
            participant.identity // intentionally Alice or Bob is self issuing to avoid more nodes is the test
        eventually(duration = 60.seconds, waitBetween = 1.seconds) {
            assertEquals(
                BigDecimal.ZERO,
                bridgeAuthority.myTokenBalance(issuingBankParty, stockAccounts.cordaTokenType),
                "Bridge Authority has no longer ${stockAccounts.cordaTokenIdentifier} shares," +
                    " they are under Locking Identity"
            )
        }
        val fungibleToken = bridgeAuthority
            .getAllFungibleTokens(issuingBankParty, stockAccounts.cordaTokenType)
            .singleOrNull()
        assertNotNull(
            fungibleToken,
            "There should be single ${stockAccounts.cordaTokenIdentifier}" +
                "fungible token in Bridge Authority vault"
        )
        assertTrue(
            fungibleToken.holder !in setOf(participant.identity, bridgeAuthority.identity),
            "Bridge Authority moved ${stockAccounts.cordaTokenIdentifier} under Lock Identity (CI) " +
                "ownership as neither BA nor Alice holds the token",
        ) // We don't know Confidential Identity upfront, so indirect check who doesn't have the token
        assertEquals(
            quantity,
            fungibleToken.amount.toDecimal(),
            "Lock Identity received expected number of ${stockAccounts.cordaTokenIdentifier} shares",
        )
        val token: StateAndRef<FungibleToken>? = bridgeAuthority.node.queryStates<FungibleToken>().firstOrNull {
            it.state.data.amount.token.tokenType == stockAccounts.cordaTokenType
        }
        assertNotNull(token)
        val accountInfo = validator.getAccountInfo(stockAccounts.tokenAccount)
        assertAtaAccount(accountInfo, stockAccounts.tokenMintAccount, participant.walletAccount)

        // SPL Token RPC returns decimal strings with trailing zeros trimmed,
        // BigDecimal.equals is scale-sensitive (1.0 != 1.00), so we compare numeric value instead.
        eventually(duration = 60.seconds, waitBetween = 1.seconds) {
            assertThat(validator.getSolanaTokenBalance(stockAccounts.tokenAccount))
                .describedAs("Solana token amount numerically equals Corda bridged amount")
                .isEqualByComparingTo(quantity)
        }
    }

    private fun redeem(participant: Participant, stockAccounts: Participant.StockAccounts, quantity: BigDecimal) {
        val issuingBankParty = participant.identity
        // Simulate redemption transfer for participant's account on Solana
        validator.transfer(
            participant.wallet,
            stockAccounts.tokenAccount,
            stockAccounts.redemptionTokenAccount,
            quantity.toRawAmount(TOKEN_DECIMALS)
        )
        val expectedLockedAmount = MOVE_QUANTITY - quantity
        // We need to wait for the websocket listener to process the newly received event
        eventually(duration = 60.seconds, waitBetween = 1.seconds) {
            assertEquals(
                ISSUING_QUANTITY - expectedLockedAmount,
                participant.myTokenBalance(issuingBankParty, stockAccounts.cordaTokenType),
                "${participant.name} received redeemed ${stockAccounts.cordaTokenIdentifier} shares back",
            )
        }
        val fungibleTokens =
            bridgeAuthority.getAllFungibleTokens(issuingBankParty, stockAccounts.cordaTokenType).filter {
                it.holder !in listOf(alice.identity, bob.identity, bridgeAuthority.identity) // CI holds tokens
            }
        if (expectedLockedAmount.toRawAmount(TOKEN_DECIMALS) == BigDecimal.ZERO.toRawAmount(TOKEN_DECIMALS)) {
            assertTrue(
                fungibleTokens.isEmpty(),
                "No ${stockAccounts.cordaTokenIdentifier} shares left in Bridge Authority vault"
            )
        } else {
            assertTrue(
                fungibleTokens.isNotEmpty(),
                "Expected some ${stockAccounts.cordaTokenIdentifier} tokens locked, but none were found"
            )
            val lockedAmount = fungibleTokens.sumTokenStatesOrThrow().toDecimal()
            assertTrue(
                lockedAmount == expectedLockedAmount,
                "Expected $expectedLockedAmount ${stockAccounts.cordaTokenType} tokens locked, but was $lockedAmount"
            )
        }
    }

    fun Participant.issue(
        tokenDescriptor: TokenTypeDescriptor,
        amount: BigDecimal,
        notaryName: CordaX500Name,
    ): TokenType {
        val tokenType = TokenType(tokenDescriptor.ticker, tokenDescriptor.fractionDigits)
        node.rpc
            .startFlow(
                ::IssueSimpleTokenFlow,
                tokenType,
                amount,
                notaryName,
            ).returnValue
            .getOrThrow()
        assertEquals(amount, myTokenBalance(identity, tokenType))
        return tokenType
    }

    private fun Participant.getAllFungibleTokens(issuer: Party, stock: TokenType): List<FungibleToken> {
        val fungibleTokenType = node.rpc
            .startFlow(::QuerySimpleTokensFlow, issuer, stock)
            .returnValue
            .getOrThrow()
            .firstOrNull() ?: return emptyList()
        return node.rpc
            .vaultQueryBy<FungibleToken>()
            .states
            .map { it.state.data }
            .filter {
                it.amount.token == fungibleTokenType.state.data.issuedTokenType ||
                    it.amount.token == fungibleTokenType.state.data.tokenType
            }
    }

    fun Participant.myTokenBalance(issuer: Party, tokenType: TokenType): BigDecimal {
        val fungibleTokens = getAllFungibleTokens(issuer, tokenType).filter { it.holder == identity }
        return if (fungibleTokens.isEmpty()) {
            BigDecimal.ZERO
        } else {
            fungibleTokens
                .sumTokenStatesOrThrow()
                .toDecimal()
        }
    }

    private fun Participant.move(toParty: Party, quantity: BigDecimal, tokenType: TokenType) =
        assertNotNull(
            node.rpc
                .startFlow(
                    ::MoveFungibleTokens,
                    Amount.fromDecimal(quantity, tokenType),
                    toParty,
                ).returnValue
                .getOrThrow()
        )

    private inline fun <reified T : ContractState> NodeHandle.queryStates(): List<StateAndRef<T>> {
        return rpc
            .vaultQueryBy<T>()
            .states
    }
}
