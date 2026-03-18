package com.r3.corda.lib.solana.bridging.token.test.driver

import com.r3.corda.lib.solana.bridging.token.flows.tokenProgramId
import com.r3.corda.lib.solana.bridging.token.test.TokenTypeDescriptor
import com.r3.corda.lib.solana.bridging.token.test.assertAtaAccount
import com.r3.corda.lib.solana.bridging.token.test.getTokenBalance
import com.r3.corda.lib.solana.bridging.token.test.toRawAmount
import com.r3.corda.lib.solana.bridging.token.testing.QuerySimpleTokensFlow
import com.r3.corda.lib.solana.core.FileSigner
import com.r3.corda.lib.solana.core.SolanaUtils
import com.r3.corda.lib.solana.core.tokens.TokenProgram.TOKEN_2022
import com.r3.corda.lib.solana.testing.SolanaTestValidator
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrThrow
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.utilities.tokenAmountCriteria
import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.Amount
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.concurrent.map
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.NetworkParameters
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.seconds
import net.corda.solana.notary.testing.Notary
import net.corda.solana.notary.testing.SolanaNotaryExtension
import net.corda.testing.common.internal.eventually
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.DUMMY_BANK_A_NAME
import net.corda.testing.driver.DriverDSL
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.NodeParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import software.sava.core.accounts.PublicKey
import software.sava.core.accounts.Signer
import software.sava.core.accounts.SolanaAccounts
import software.sava.solana.programs.token.AssociatedTokenProgram
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

@ExtendWith(SolanaNotaryExtension::class)
abstract class DriverTest {
    companion object {
        const val CORDA_TOKEN_DECIMALS = 3
        const val SOLANA_TOKEN_DECIMALS = 4
        private val ISSUING_QUANTITY = BigDecimal("2000.000")
        private val BRIDGE_QUANTITY = BigDecimal("10.250")
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

        private val solanaNotaryName = CordaX500Name("Solana Notary Service", "London", "GB")
        private val generalNotaryName = CordaX500Name("Notary Service", "Zurich", "CH")
        private val bridgeAuthority = CordaParticipant(CordaX500Name("Bridge Authority", "New York", "US"))
        private val alice = SolanaParticipant(ALICE_NAME)
        private val bob = SolanaParticipant(BOB_NAME)
        private val issuer = CordaParticipant(DUMMY_BANK_A_NAME)

        @TempDir
        lateinit var notaryCustodiedKeysDir: Path

        private lateinit var validator: SolanaTestValidator
        private lateinit var bridgeAuthorityWallet: FileSigner
        private lateinit var redemptionWalletForAlice: FileSigner
        private lateinit var redemptionWalletForBob: FileSigner
        private lateinit var mintAuthoritySigner: FileSigner

        @JvmStatic
        @BeforeAll
        fun init(validator: SolanaTestValidator) {
            this.validator = validator
            mintAuthoritySigner = FileSigner.random(notaryCustodiedKeysDir)
            bridgeAuthorityWallet = FileSigner.random(notaryCustodiedKeysDir)
            redemptionWalletForAlice = FileSigner.random(notaryCustodiedKeysDir)
            redemptionWalletForBob = FileSigner.random(notaryCustodiedKeysDir)

            setOf(
                mintAuthoritySigner,
                bridgeAuthorityWallet,
                alice.wallet,
                bob.wallet,
                redemptionWalletForAlice,
                redemptionWalletForBob,
            ).forEach { account ->
                validator.accounts().airdropSol(account.publicKey(), 10)
            }
        }
    }

    private lateinit var msftTokenMint: PublicKey
    private lateinit var appleTokenMint: PublicKey

    // SUTs:
    abstract val msftDescriptor: TokenTypeDescriptor
    abstract val appleDescriptor: TokenTypeDescriptor
    private lateinit var aliceMicrosoft: ParticipantAndStock
    private lateinit var aliceApple: ParticipantAndStock
    private lateinit var bobMicrosoft: ParticipantAndStock
    // no bobApple

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
                    alice.nameAsString to alice.walletAccount.toBase58(),
                    bob.nameAsString to bob.walletAccount.toBase58()
                ),
                "redemptionWalletAccountToHolder" to mapOf(
                    redemptionWalletForAlice.publicKey().toBase58() to alice.nameAsString,
                    redemptionWalletForBob.publicKey().toBase58() to bob.nameAsString,
                ),
                "mintsWithAuthorities" to mapOf(
                    msftDescriptor.tokenTypeIdentifier to
                        mapOf(
                            "tokenMint" to msftTokenMint.toBase58(),
                            "mintAuthority" to mintAuthoritySigner.publicKey().toBase58()
                        ),
                    appleDescriptor.tokenTypeIdentifier to
                        mapOf(
                            "tokenMint" to appleTokenMint.toBase58(),
                            "mintAuthority" to mintAuthoritySigner.publicKey().toBase58()
                        )
                ),
                "solanaNotaryName" to solanaNotaryName.toString(),
                "generalNotaryName" to generalNotaryName.toString(),
                "solanaRpcUrl" to "${validator.rpcUrl()}",
                "solanaWebsocketUrl" to "${validator.websocketUrl()}",
                "bridgeAuthorityWalletFile" to bridgeAuthorityWallet.file.toString(),
            )
        )
    }

    private fun solanaNotaryConfig(notaryKey: FileSigner): Map<String, Any> {
        return mapOf(
            "notary" to mapOf(
                "validating" to false,
                // "serviceLegalName" doesn't work with Driver, because it needs a distributed key that is not created
                "solana" to mapOf(
                    "rpcUrl" to "${validator.rpcUrl()}",
                    "websocketUrl" to "${validator.websocketUrl()}",
                    "notaryKeypairFile" to "${notaryKey.file}",
                    "custodiedKeysDir" to "$notaryCustodiedKeysDir",
                )
            )
        )
    }

    @BeforeEach
    fun issueCordaTokens() {
        msftTokenMint =
            validator.tokens().createToken(mintAuthoritySigner, TOKEN_2022, decimals = SOLANA_TOKEN_DECIMALS)
        appleTokenMint =
            validator.tokens().createToken(mintAuthoritySigner, TOKEN_2022, decimals = SOLANA_TOKEN_DECIMALS)
        val assembleParticipantWithStock = fun(
            participant: SolanaParticipant,
            tokenDescriptor: TokenTypeDescriptor,
            tokenMintAccount: PublicKey,
            redemptionWallet: Signer,
        ): ParticipantAndStock {
            val redemptionTokenAccount = validator.tokens().createTokenAccount(redemptionWallet, tokenMintAccount)
            validator.accounts().airdropSol(redemptionTokenAccount, 10)
            return ParticipantAndStock(tokenDescriptor.ticker, participant, tokenMintAccount, redemptionTokenAccount)
        }
        aliceMicrosoft = assembleParticipantWithStock(alice, msftDescriptor, msftTokenMint, redemptionWalletForAlice)
        aliceApple = assembleParticipantWithStock(alice, appleDescriptor, appleTokenMint, redemptionWalletForAlice)
        bobMicrosoft = assembleParticipantWithStock(bob, msftDescriptor, msftTokenMint, redemptionWalletForBob)
    }

    @Suppress("LongMethod")
    @Test
    fun `driver bridge and redemption test`(@Notary notaryKey: FileSigner) {
        driver(
            DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = false,
                cordappsForAllNodes = cordappsForAllNodes,
                notarySpecs = listOf(
                    NotarySpec(generalNotaryName, validating = false, startInProcess = false),
                    NotarySpec(solanaNotaryName, solanaNotaryConfig(notaryKey), startInProcess = false),
                ),
                networkParameters = networkParameters,
            )
        ) {
            // setup continuation:
            setOf(issuer, alice, bob).map { startNode(it) }.transpose().get()
            startNode(bridgeAuthority, setOf(bridgingFlowsCordapp, bridgingContractsCordapp)).get()

            // setup continuation; issue tokens and then move them to Alice and Bob:
            issuer.issue(msftDescriptor, ISSUING_QUANTITY * BigDecimal(2), generalNotaryName).also { tokenType ->
                aliceMicrosoft.receive(issuer, tokenType, ISSUING_QUANTITY)
                bobMicrosoft.receive(issuer, tokenType, ISSUING_QUANTITY)
            }
            issuer.issue(appleDescriptor, ISSUING_QUANTITY, generalNotaryName).also { tokenType ->
                aliceApple.receive(issuer, tokenType, ISSUING_QUANTITY)
            }

            setOf(aliceMicrosoft, aliceApple, bobMicrosoft).forEach { participantAndStock ->
                eventually(duration = 10.seconds, waitBetween = 1.seconds) {
                    assertEquals(
                        ISSUING_QUANTITY,
                        participantAndStock.tokenBalance(issuer.identity),
                        "${participantAndStock.participant.identity} received redeemed " +
                            "${participantAndStock.stockName} shares back",
                    )
                }
                assertNull(
                    validator.accounts().getAccountInfo(participantAndStock.tokenAccount),
                    "${participantAndStock.participant.nameAsString} ATA should not be created yet",
                )
            }

            // actual test:
            // Alice bridges Microsoft stock once, and then she redeems once the same amount
            bridgeTest(aliceMicrosoft, BRIDGE_QUANTITY, expectedSolanaBalance = BRIDGE_QUANTITY)

            // Bob bridges Microsoft stock twice and then he will redeem all in one go
            val firstBridgeQuantity = BigDecimal.ONE
            val secondBridgeQuantity = BRIDGE_QUANTITY - BigDecimal.ONE
            bridgeTest(bobMicrosoft, firstBridgeQuantity, expectedSolanaBalance = BigDecimal.ONE)
            bridgeTest(bobMicrosoft, secondBridgeQuantity, expectedSolanaBalance = BRIDGE_QUANTITY)

            bridgeTest(aliceApple, BRIDGE_QUANTITY, expectedSolanaBalance = BRIDGE_QUANTITY)

            redeemTest(
                aliceMicrosoft,
                quantity = BRIDGE_QUANTITY,
                expectedCordaBalance = ISSUING_QUANTITY,
                expectedSolanaRedemptionBalance = BigDecimal.ZERO
            )

            redeemTest(
                bobMicrosoft,
                quantity = BRIDGE_QUANTITY,
                expectedCordaBalance = ISSUING_QUANTITY,
                expectedSolanaRedemptionBalance = BigDecimal.ZERO
            )

            var expectedCordaBalance = ISSUING_QUANTITY - BRIDGE_QUANTITY + BigDecimal("1.0000")
            redeemTest(
                aliceApple,
                quantity = BigDecimal("1.0000"),
                expectedCordaBalance = expectedCordaBalance,
                expectedSolanaRedemptionBalance = BigDecimal.ZERO
            )

            expectedCordaBalance += BigDecimal("1.0000")
            redeemTest(
                aliceApple,
                quantity = BigDecimal("1.0001"),
                expectedCordaBalance = expectedCordaBalance,
                // fractions below what Corda token has are left on Solana
                expectedSolanaRedemptionBalance = BigDecimal("0.0001")
            )

            expectedCordaBalance += (BigDecimal("0.01"))
            redeemTest(
                aliceApple,
                quantity = BigDecimal("0.0099"),
                expectedCordaBalance = expectedCordaBalance,
                // new redeem amount added up to what was left and all will be redeemed
                expectedSolanaRedemptionBalance = BigDecimal.ZERO
            )
        }
    }

    private fun bridgeTest(
        participantAndStock: ParticipantAndStock,
        quantity: BigDecimal,
        expectedSolanaBalance: BigDecimal = quantity,
    ) {
        participantAndStock.move(bridgeAuthority.identity, quantity)
        eventually(duration = 10.seconds, waitBetween = 1.seconds) {
            assertEquals(
                BigDecimal.ZERO,
                bridgeAuthority.myTokenBalance(issuer.identity, participantAndStock.cordaTokenType),
                "Bridge Authority has no longer ${participantAndStock.stockName} shares," +
                    " they are under Locking Identity"
            )
        }
        val fungibleTokens = bridgeAuthority
            .getAllFungibleTokens(issuer.identity, participantAndStock.cordaTokenType)
        assertThat(fungibleTokens)
            .describedAs(
                "There should be at least one ${participantAndStock.stockName}" +
                    " fungible token in Bridge Authority vault"
            )
            .isNotEmpty

        val holder = fungibleTokens.map { it.holder }.toSet().singleOrNull()
        requireNotNull(holder) { "Selected fungible tokens should have the same holder" }
        assertThat(holder)
            .describedAs("Fungible token holder should be Locking Identity.")
            .isNotIn(participantAndStock.participant.identity, bridgeAuthority.identity)

        val accountInfo = validator.accounts().getAccountInfo(participantAndStock.tokenAccount)
        assertAtaAccount(
            accountInfo,
            participantAndStock.tokenMintAccount,
            participantAndStock.participant.walletAccount,
        )
        // SPL Token RPC returns decimal strings with trailing zeros trimmed,
        // BigDecimal.equals is scale-sensitive (1.0 != 1.00), so we compare numeric value instead.
        eventually(duration = 10.seconds, waitBetween = 1.seconds) {
            assertThat(validator.client().getTokenBalance(participantAndStock.tokenAccount))
                .describedAs("Solana token amount numerically equals Corda bridged amount")
                .isEqualByComparingTo(expectedSolanaBalance)
        }
    }

    private fun redeemTest(
        participantAndStock: ParticipantAndStock,
        quantity: BigDecimal,
        expectedCordaBalance: BigDecimal,
        expectedSolanaRedemptionBalance: BigDecimal,
    ) {
        val participant = participantAndStock.participant
        // Simulate redemption transfer for participant's account on Solana
        validator.tokens().transfer(
            participant.wallet,
            participantAndStock.tokenAccount,
            participantAndStock.redemptionTokenAccount,
            quantity.toRawAmount(SOLANA_TOKEN_DECIMALS)
        )
        // We need to wait for the websocket listener to process the newly received event
        eventually(duration = 10.seconds) {
            assertThat(participantAndStock.tokenBalance(issuer.identity))
                .describedAs(
                    "${participantAndStock.participant.nameAsString} received redeemed " +
                        "${participantAndStock.stockName} shares back"
                ).isEqualByComparingTo(expectedCordaBalance)
        }
        eventually(duration = 10.seconds) {
            assertThat(validator.client().getTokenBalance(participantAndStock.redemptionTokenAccount))
                .describedAs(
                    "Redemption token account has $expectedSolanaRedemptionBalance instead $quantity after transfer" +
                        " - party ${participantAndStock.participant.nameAsString}"
                ).isEqualByComparingTo(expectedSolanaRedemptionBalance)
        }
    }

    // issuance method could be defined inside [ParticipantAndStock] class however it needs to be abstract method,
    // because it creates either Corda Simple Token or Corda Evolvable Token,
    // and is issued by another participant "issuer" that is no use in the actual test
    abstract fun CordaParticipant.issue(
        tokenTypeDescriptor: TokenTypeDescriptor,
        amount: BigDecimal,
        notaryName: CordaX500Name,
    ): TokenType

    private fun DriverDSL.startNode(
        participant: CordaParticipant,
        additionalCordapps: Set<TestCordapp> = emptySet()
    ): CordaFuture<*> {
        val user = User("user1", "test", permissions = setOf("ALL"))
        return startNode(
            NodeParameters(providedName = participant.name, rpcUsers = listOf(user))
                .withAdditionalCordapps(additionalCordapps)
        ).map {
            participant.node = it
        }
    }
}

/**
 * Holds and accumulates Corda data for a participant used for creation of Cordapp config,
 * and for interaction with Corda node. Encapsulates RPC operation with Corda node.
 * Field [node] need to be initialized later after a Corda node is started. Field [identity] is derived from [node].
 */
open class CordaParticipant(val name: CordaX500Name) {
    // value known before node start, value used to create Bridge Authority cordapp configuration file
    val nameAsString: String
        get() = name.toString()

    // values only known after node start
    lateinit var node: NodeHandle
    val identity: Party
        get() = node.nodeInfo.legalIdentities.first()

    fun myTokenBalance(issuer: Party, tokenType: TokenType): BigDecimal {
        val fungibleTokens = getAllFungibleTokens(issuer, tokenType).filter { it.holder == identity }
        return if (fungibleTokens.isEmpty()) {
            BigDecimal.ZERO
        } else {
            fungibleTokens
                .sumTokenStatesOrThrow()
                .toDecimal()
        }
    }

    fun getAllFungibleTokens(issuer: Party, stock: TokenType): List<FungibleToken> {
        val fungibleTokenType = node.rpc
            .startFlow(::QuerySimpleTokensFlow, issuer, stock)
            .returnValue
            .getOrThrow()
            .firstOrNull() ?: return emptyList()
        return node.rpc
            .vaultQueryBy<FungibleToken>(tokenAmountCriteria(fungibleTokenType.state.data.tokenType))
            .states
            .map { it.state.data }
    }
}

/**
 * Holds and accumulates Corda and Solana data for a participant used for creation of Cordapp config,
 * and for interaction with Corda node. Encapsulates RPC operation with Corda node.
 * Field [node] need to be initialized later after a Corda node is started. Field [identity] is derived from [node].
 */
class SolanaParticipant(name: CordaX500Name) : CordaParticipant(name) {
    // values known before node start, values used to create Bridge Authority cordapp configuration file
    val wallet: Signer = SolanaUtils.randomSigner()
    val walletAccount: PublicKey
        get() = wallet.publicKey()
}

/**
 * Holds and accumulates Corda and Solana data for a single Corda Token and respective Solana Token accounts,
 * and reference to a participant.
 * Fields are used for creation of Cordapp config file and for interaction with Corda node or Solana.
 * Field [cordaTokenType] need to be initialized later after a Corda token is created.
 * Field [cordaTokenIdentifier] is derived from [cordaTokenType].
 */
class ParticipantAndStock(
    val stockName: String,
    val participant: SolanaParticipant,
    val tokenMintAccount: PublicKey,
    val redemptionTokenAccount: PublicKey,
) {
    val tokenAccount: PublicKey
        get() = AssociatedTokenProgram
            .findATA(
                SolanaAccounts.MAIN_NET,
                participant.walletAccount,
                tokenProgramId,
                tokenMintAccount
            ).publicKey()

    // values known after node start and Corda token type is issued
    lateinit var cordaTokenType: TokenType
    val cordaTokenIdentifier: String
        get() = cordaTokenType.tokenIdentifier

    fun receive(source: CordaParticipant, tokenType: TokenType, quantity: BigDecimal) {
        cordaTokenType = tokenType
        source.node.rpc.startFlow(
            ::MoveFungibleTokens,
            Amount.fromDecimal(quantity, cordaTokenType),
            participant.identity
        )
    }

    fun move(destination: Party, quantity: BigDecimal) =
        assertNotNull( // assertion here is a setup check
            participant.node.rpc
                .startFlow(
                    ::MoveFungibleTokens,
                    Amount.fromDecimal(quantity, cordaTokenType),
                    destination,
                ).returnValue
                .getOrThrow()
        ) {
            "Token move flow failed for identity=${participant.nameAsString} and token=$cordaTokenType"
        }

    fun tokenBalance(issuer: Party): BigDecimal = participant.myTokenBalance(issuer, cordaTokenType)
}
