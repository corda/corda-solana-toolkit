package com.r3.corda.lib.solana.bridging.token.test.driver

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.client.api.AccountInfo
import com.lmax.solana4j.encoding.SolanaEncoding
import com.lmax.solana4j.programs.AssociatedTokenProgram
import com.lmax.solana4j.programs.Token2022Program
import com.r3.corda.lib.solana.bridging.token.flows.toPublicKey
import com.r3.corda.lib.solana.bridging.token.flows.tokenProgramId
import com.r3.corda.lib.solana.bridging.token.test.FlowTests.Companion.APPL_TICKER
import com.r3.corda.lib.solana.bridging.token.test.FlowTests.Companion.MSFT_TICKER
import com.r3.corda.lib.solana.bridging.token.test.FlowTests.Companion.TOKEN_DECIMALS
import com.r3.corda.lib.solana.bridging.token.test.SimpleDescriptor
import com.r3.corda.lib.solana.bridging.token.test.TokenTypeDescriptor
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
import net.corda.solana.notary.common.rpc.DefaultRpcParams
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.notary.common.rpc.sendAndConfirm
import net.corda.solana.sdk.internal.Token2022
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
import java.util.Base64
import java.util.UUID
import kotlin.collections.contains

class Participant(val name: CordaX500Name) {
    val wallet: Signer = Signer.random()
    val walletAccount: PublicKey
        get() = wallet.account
    val walletAccountAsString : String
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

    inner class StockAccounts( ) {
        lateinit var tokenDescriptor: TokenTypeDescriptor
        lateinit var cordaIssuedTokenType: TokenType
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
    private lateinit var testValidator: SolanaTestValidator
    private lateinit var solanaNotaryKey: Signer

    @TempDir lateinit var custodiedKeysDir: Path

    @TempDir lateinit var generalDir: Path

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
                    bridgeAuthorityRedemptionSignerForAlice.account.base58() to alice.nameAsString ,
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
                // "serviceLegalName" doesn't work with Driver, because it needs distributed key which is not created here
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
        testValidator = SolanaTestValidator()
        solanaNotaryKeyFile = randomKeypairFile(generalDir)
        solanaNotaryKey = Signer.fromFile(solanaNotaryKeyFile)
        mintAuthoritySigner = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
        bridgeAuthorityWalletFile = randomKeypairFile(custodiedKeysDir)
        bridgeAuthorityRedemptionForBobWalletFile = randomKeypairFile(custodiedKeysDir)
        bridgeAuthorityRedemptionSignerForAlice = Signer.fromFile(bridgeAuthorityWalletFile)
        bridgeAuthorityRedemptionSignerForBob = Signer.fromFile(bridgeAuthorityRedemptionForBobWalletFile)
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
        for (wallet in setOf(mintAuthoritySigner, alice.wallet, bob.wallet,
            bridgeAuthorityRedemptionSignerForAlice, bridgeAuthorityRedemptionSignerForBob)) {
            testValidator.fundAccount(10, wallet)
        }

        msftTokenMint = testValidator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        for (stockAccounts in setOf(alice.microsoft, bob.microsoft)) {
            stockAccounts.tokenDescriptor = msftDescriptor
            stockAccounts.deriveTokenAccountNameFrom(msftTokenMint)
        }

        alice.microsoft.redemptionTokenAccount = testValidator.createTokenAccount(bridgeAuthorityRedemptionSignerForAlice, msftTokenMint)
        bob.microsoft.redemptionTokenAccount = testValidator.createTokenAccount(bridgeAuthorityRedemptionSignerForBob, msftTokenMint)

        appleTokenMint = testValidator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        for (stockAccounts in setOf(alice.apple, bob.apple)) {
            stockAccounts.tokenDescriptor = appleDescriptor
            stockAccounts.deriveTokenAccountNameFrom(appleTokenMint)
        }
        alice.apple.redemptionTokenAccount = testValidator.createTokenAccount(bridgeAuthorityRedemptionSignerForAlice, appleTokenMint)
        bob.apple.redemptionTokenAccount = testValidator.createTokenAccount(bridgeAuthorityRedemptionSignerForBob, appleTokenMint)

        for (account in setOf(alice.microsoft.redemptionTokenAccount, bob.microsoft.redemptionTokenAccount,
            alice.apple.redemptionTokenAccount, bob.apple.redemptionTokenAccount)) {
            testValidator.fundAccount(10, account)
        }
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
            bridgeLeg(alice, alice.microsoft, MOVE_QUANTITY)
            bridgeLeg(bob, bob.microsoft, MOVE_QUANTITY)
            bridgeLeg(alice, alice.apple, MOVE_QUANTITY)
            redemptionLeg(alice, alice.microsoft, MOVE_QUANTITY)
            redemptionLeg(bob, bob.microsoft, MOVE_QUANTITY)
            redemptionLeg(alice, alice.apple, MOVE_QUANTITY.minus(BigDecimal.ONE))
        }
    }

    private fun bridgeLeg(participant: Participant, stockAccounts: Participant.StockAccounts, bridgeQuantity: BigDecimal) {

        val cordaTokenType = participant.issue(stockAccounts.tokenDescriptor, ISSUING_QUANTITY, generalNotaryName)
        stockAccounts.cordaIssuedTokenType = cordaTokenType
        assertNull(getAccountInfo(stockAccounts.tokenAccount), "${participant.name} ATA should not be created yet")

        participant.move(bridgeAuthority.identity, bridgeQuantity, cordaTokenType)

        val issuingBankParty = participant.identity // intentionally Alice or Bob is self issuing to avoid more nodes is the test
        eventually(duration = 60.seconds, waitBetween = 1.seconds) {
            assertEquals(
                BigDecimal.ZERO,
                bridgeAuthority.myTokenBalance(issuingBankParty, cordaTokenType),
                "Bridge Authority has no longer ${cordaTokenType.tokenIdentifier} shares, they are under Locking Identity"
            )
        }
        val fungibleToken = bridgeAuthority
            .getAllFungibleTokens(issuingBankParty, cordaTokenType)
            .singleOrNull()
        assertNotNull(fungibleToken, "There should be single ${cordaTokenType.tokenIdentifier} fungible token in Bridge Authority vault")
        assertTrue(
            fungibleToken.holder !in setOf(participant.identity, bridgeAuthority.identity),
            "Bridge Authority moved ${cordaTokenType.tokenIdentifier} under Lock Identity (CI) " +
                "ownership as neither BA nor Alice holds the token",
        ) // We don't know Confidential Identity upfront, so indirect check who doesn't have the token
        assertEquals(
            bridgeQuantity,
            fungibleToken.amount.toDecimal(),
            "Lock Identity received expected number of ${cordaTokenType.tokenIdentifier} shares",
        )
        val token: StateAndRef<FungibleToken>? = bridgeAuthority.node.queryStates<FungibleToken>().firstOrNull {
            it.state.data.amount.token.tokenType == cordaTokenType
        }
        assertNotNull(token)
        val accountInfo = getAccountInfo(stockAccounts.tokenAccount)
        assertAtaAccount(accountInfo, stockAccounts.tokenMintAccount, participant.walletAccount)

        // SPL Token RPC returns decimal strings with trailing zeros trimmed,
        // BigDecimal.equals is scale-sensitive (1.0 != 1.00), so we compare numeric value instead.
        eventually(duration = 60.seconds, waitBetween = 1.seconds) {
            assertThat(getSolanaTokenBalance(stockAccounts.tokenAccount))
                .describedAs("Solana token amount numerically equals Corda bridged amount")
                .isEqualByComparingTo(bridgeQuantity)
        }
    }

    private fun redemptionLeg(participant: Participant, stockAccounts: Participant.StockAccounts, redeemQuantity: BigDecimal) {
        val issuingBankParty = participant.identity
        // Simulate redemption transfer for participant's account on Solana
        transfer(
            participant.wallet,
            stockAccounts.tokenAccount,
            stockAccounts.redemptionTokenAccount,
            redeemQuantity.toRawAmount()
        )
        val expectedLockedAmount = MOVE_QUANTITY - redeemQuantity
        // We need to wait for the websocket listener to process the newly received event
        eventually(duration = 60.seconds, waitBetween = 1.seconds) {
            assertEquals(
                ISSUING_QUANTITY - expectedLockedAmount,
                participant.myTokenBalance(issuingBankParty, stockAccounts.cordaIssuedTokenType),
                "${participant.name} received redeemed ${stockAccounts.cordaIssuedTokenType.tokenIdentifier} shares back",
            )
        }
        val fungibleTokens = bridgeAuthority.getAllFungibleTokens(issuingBankParty, stockAccounts.cordaIssuedTokenType).filter {
            it.holder !in listOf(alice.identity, bob.identity, bridgeAuthority.identity) // Locking identity holds tokens
        }
        if (expectedLockedAmount.toRawAmount() == BigDecimal.ZERO.toRawAmount()) {
            assertTrue(
                fungibleTokens.isEmpty(),
                "No ${stockAccounts.cordaIssuedTokenType.tokenIdentifier} shares left in Bridge Authority vault"
            )
        } else {
            assertTrue(
                fungibleTokens.isNotEmpty(),
                "Expected some ${stockAccounts.cordaIssuedTokenType.tokenIdentifier} tokens locked, but none were found"
            )
            val lockedAmount = fungibleTokens.sumTokenStatesOrThrow().toDecimal()
            assertTrue(
                lockedAmount == expectedLockedAmount,
                "Expected $expectedLockedAmount ${stockAccounts.cordaIssuedTokenType} tokens locked, but was $lockedAmount"
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
        val fungibleTokenType1 = node.rpc
            .startFlow(::QuerySimpleTokensFlow, issuer, stock)
            .returnValue
            .getOrThrow()
            .firstOrNull()
        val fungibleTokenType = fungibleTokenType1 ?: return emptyList()
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

    private fun getAccountInfo(publicKey: PublicKey): AccountInfo? {
        return testValidator
            .client
            .getAccountInfo(publicKey.base58(), testValidator.rpcParams)
            .checkResponse("getAccountInfo")
    }

    private fun assertAtaAccount(
        accountInfo: AccountInfo?,
        expectedMintAccount: PublicKey,
        expectedOwnerAccount: PublicKey,
    ) {
        assertNotNull(accountInfo, "Account not found")
        assertEquals(accountInfo.owner, tokenProgramId.base58(), "ATA programId should match Token 2022")
        val encodedInfo = accountInfo.data?.accountInfoEncoded
        assertNotNull(encodedInfo, "Account data missing")
        assertTrue(encodedInfo.size >= 2, "Missing encoded account data")
        val binaryData = Base64.getDecoder().decode(encodedInfo.first())
        val mintAccount = SolanaEncoding.account(binaryData.copyOfRange(0, 32))
        assertEquals(mintAccount, expectedMintAccount, "ATA mint account mismatch")
        val ownerAccount = SolanaEncoding.account(binaryData.copyOfRange(32, 64))
        assertEquals(ownerAccount, expectedOwnerAccount, "ATA owner account mismatch")
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

    private fun getSolanaTokenBalance(publicKey: PublicKey): BigDecimal {
        return testValidator
            .client
            .getTokenAccountBalance(publicKey.base58(), testValidator.rpcParams)
            .checkResponse("getTokenAccountBalance")!!
            .uiAmountString
            .toBigDecimal()
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
            DefaultRpcParams()
        )
    }

    private fun BigDecimal.toRawAmount(): Long {
        return (this * BigDecimal(10L).pow(TOKEN_DECIMALS)).longValueExact()
    }
}
