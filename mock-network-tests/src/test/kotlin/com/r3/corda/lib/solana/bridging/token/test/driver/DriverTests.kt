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
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

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

    private val validator = SolanaTestValidator()
    private val solanaNotaryName = CordaX500Name("Solana Notary Service", "London", "GB")
    private val generalNotaryName = CordaX500Name("Notary Service", "Zurich", "CH")
    private val bridgeAuthority: Participant = Participant(CordaX500Name("Bridge Authority", "New York", "US"))
    private val alice: Participant = Participant(ALICE_NAME)
    private val bob: Participant = Participant(BOB_NAME)
    private val msftDescriptor: TokenTypeDescriptor = SimpleDescriptor(MSFT_TICKER)
    private val appleDescriptor: TokenTypeDescriptor = SimpleDescriptor(APPL_TICKER)
    private lateinit var mintAuthoritySigner: Signer
    private lateinit var redemptionWalletForAlice: Signer
    private lateinit var redemptionWalletForBob: Signer
    private lateinit var bridgeAuthorityWalletFile: Path
    private lateinit var bridgeAuthorityWallet: Signer
    private lateinit var msftTokenMint: PublicKey
    private lateinit var appleTokenMint: PublicKey
    private lateinit var solanaNotaryKeyFile: Path
    private lateinit var solanaNotaryKey: Signer
    private lateinit var aliceMicrosoft: ParticipantAndStock
    private lateinit var aliceApple: ParticipantAndStock
    private lateinit var bobMicrosoft: ParticipantAndStock
    private lateinit var bobApple: ParticipantAndStock

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
                    alice.identityAsString to alice.walletAccountAsString,
                    bob.identityAsString to bob.walletAccountAsString
                ),
                "redemptionWalletAccountToHolder" to mapOf(
                    redemptionWalletForAlice.account.base58() to alice.identityAsString,
                    redemptionWalletForBob.account.base58() to bob.identityAsString,
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
        bridgeAuthorityWallet = Signer.fromFile(bridgeAuthorityWalletFile)
        redemptionWalletForAlice = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
        redemptionWalletForBob = Signer.fromFile(randomKeypairFile(custodiedKeysDir))

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
            bridgeAuthorityWallet,
            alice.wallet,
            bob.wallet,
            redemptionWalletForAlice,
            redemptionWalletForBob,
        ).forEach { account ->
            validator.fundAccount(10, account)
        }

        msftTokenMint = validator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        appleTokenMint = validator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        val create = fun(
            participant: Participant,
            stockDescription: TokenTypeDescriptor,
            tokenMint: PublicKey,
            redemptionWallet: Signer,
        ): ParticipantAndStock {
            val redemptionTokenAccount = validator.createTokenAccount(redemptionWallet, tokenMint)
            validator.fundAccount(10, redemptionTokenAccount)
            return ParticipantAndStock(participant, stockDescription, tokenMint, redemptionTokenAccount)
        }
        aliceMicrosoft = create(alice, msftDescriptor, msftTokenMint, redemptionWalletForAlice)
        aliceApple = create(alice, appleDescriptor, appleTokenMint, redemptionWalletForAlice)
        bobMicrosoft = create(bob, msftDescriptor, msftTokenMint, redemptionWalletForBob)
        bobApple = create(bob, appleDescriptor, appleTokenMint, redemptionWalletForBob)
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
                startNodesInProcess = true,
                cordappsForAllNodes = cordappsForAllNodes,
                notarySpecs = listOf(
                    NotarySpec(generalNotaryName, validating = false, startInProcess = true),
                    NotarySpec(solanaNotaryName, solanaNotaryConfig, startInProcess = true),
                ),
                networkParameters = networkParameters,
            )
        ) {
            bridgeAuthority.startNode(this, bridgingFlowsCordapp, bridgingContractsCordapp)
            alice.startNode(this)
            bob.startNode(this)

            setOf(aliceMicrosoft, aliceApple, bobMicrosoft, bobApple).forEach { stock ->
                val cordaTokenType = stock.participant.issue(stock.tokenDescriptor, ISSUING_QUANTITY, generalNotaryName)
                stock.cordaTokenType = cordaTokenType
                assertNull(
                    validator.getAccountInfo(stock.tokenAccount),
                    "${stock.participant.identityAsString} ATA should not be created yet",
                )
            }
            bridge(aliceMicrosoft, MOVE_QUANTITY)
            // Bob bridges and redeems exactly the same amount in one go
            bridge(bobMicrosoft, MOVE_QUANTITY)
            bridge(aliceApple, MOVE_QUANTITY)
            redeem(aliceMicrosoft, MOVE_QUANTITY)
            redeem(bobMicrosoft, MOVE_QUANTITY)
            // Alice redeems a smaller quantity of APPLE to leave a change for CI
            redeem(aliceApple, MOVE_QUANTITY.minus(BigDecimal.ONE))
        }
    }

    private fun bridge(stockAccounts: ParticipantAndStock, quantity: BigDecimal) {
        val participant = stockAccounts.participant
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

    private fun redeem(stockAccounts: ParticipantAndStock, quantity: BigDecimal) {
        val participant = stockAccounts.participant
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
                "${participant.identityAsString} received redeemed ${stockAccounts.cordaTokenIdentifier} shares back",
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

class Participant(private val name: CordaX500Name) {
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
    val identityAsString: String
        get() = name.toString()

    val user = User("user1", "test", permissions = setOf("ALL"))
}

class ParticipantAndStock(
    val participant: Participant,
    val tokenDescriptor: TokenTypeDescriptor,
    val tokenMintAccount: PublicKey,
    val redemptionTokenAccount: PublicKey,
) {
    lateinit var cordaTokenType: TokenType
    val cordaTokenIdentifier: String
        get() = cordaTokenType.tokenIdentifier
    val tokenAccount: PublicKey
        get() = AssociatedTokenProgram
            .deriveAddress(
                participant.walletAccount,
                Token2022.PROGRAM_ID.toPublicKey(),
                tokenMintAccount
            ).address()
}
