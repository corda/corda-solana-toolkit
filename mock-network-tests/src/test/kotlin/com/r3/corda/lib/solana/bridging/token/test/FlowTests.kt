package com.r3.corda.lib.solana.bridging.token.test

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.client.api.AccountInfo
import com.lmax.solana4j.encoding.SolanaEncoding
import com.lmax.solana4j.programs.Token2022Program
import com.r3.corda.lib.solana.bridging.token.flows.tokenProgramId
import com.r3.corda.lib.solana.bridging.token.testing.QuerySimpleTokensFlow
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrThrow
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.utilities.tokenAmountsByToken
import net.corda.core.contracts.Amount
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.seconds
import net.corda.solana.notary.common.Signer
import net.corda.solana.notary.common.rpc.DefaultRpcParams
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.notary.common.rpc.sendAndConfirm
import net.corda.solana.sdk.internal.Token2022
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
import java.util.*

val solanaNotaryName = CordaX500Name("Solana Notary Service", "London", "GB")
val generalNotaryName = CordaX500Name("Notary Service", "Zurich", "CH")

abstract class FlowTests {
    abstract val msftDescriptor: TokenTypeDescriptor
    abstract val aaplDescriptor: TokenTypeDescriptor

    abstract fun StartedMockNode.issue(
        tokenDescriptor: TokenTypeDescriptor,
        amount: BigDecimal,
        notaryName: CordaX500Name,
    ): TokenType

    private lateinit var network: MockNetwork
    private lateinit var issuingBank: StartedMockNode
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
        private val ISSUING_QUANTITY = BigDecimal("2000.000")
        private val MOVE_QUANTITY = BigDecimal("10.250")

        private val aliceIdentity = TestIdentity(ALICE_NAME)
        private val bobIdentity = TestIdentity(BOB_NAME)
        private val issuingBankIdentity = TestIdentity(DUMMY_BANK_A_NAME)
        private val bridgeAuthorityIdentity = TestIdentity(CordaX500Name("Bridge Authority", "New York", "US"))
    }

    @TempDir
    lateinit var generalDir: Path

    @TempDir
    lateinit var custodiedKeysDir: Path

    private lateinit var testValidator: SolanaTestValidator

    private lateinit var solanaNotaryKeyFile: Path
    private lateinit var solanaNotaryKey: Signer
    private lateinit var mintAuthoritySigner: Signer
    private lateinit var msftTokenMint: PublicKey
    private lateinit var aaplTokenMint: PublicKey

    private lateinit var bridgeAuthority: BridgeAuthorityInfo
    private lateinit var alice: CordaUserBridgingAccounts
    private lateinit var bob: CordaUserBridgingAccounts

    @BeforeEach
    fun setup() {
        startTestValidator()
        startCordaNetwork()
        alice = CordaUserBridgingAccounts.generate(
            network,
            aliceIdentity,
            listOf(msftTokenMint, aaplTokenMint),
            testValidator
        )
        bob = CordaUserBridgingAccounts.generate(
            network,
            bobIdentity,
            listOf(msftTokenMint, aaplTokenMint),
            testValidator
        )
        bridgeAuthority = BridgeAuthorityInfo.generate(
            network,
            bridgeAuthorityIdentity,
            custodiedKeysDir,
            listOf(alice, bob),
            mapOf(
                msftDescriptor to msftTokenMint,
                aaplDescriptor to aaplTokenMint,
            ),
            mintAuthoritySigner.account,
            testValidator,
        )
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

        msftTokenMint = testValidator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        aaplTokenMint = testValidator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
    }

    fun stopTestValidator() {
        if (::testValidator.isInitialized) {
            testValidator.close()
        }
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
            rpcUrl = "${SolanaTestValidator.RPC_URL}"
            notaryKeypairFile = "$solanaNotaryKeyFile"
            custodiedKeysDir = "$custodiedKeysDir"
            programWhitelist = ["${Token2022.PROGRAM_ID}"]
        }
        """.trimIndent()

    private fun createNotaryConfig(): String =
        """
        validating = false
        """.trimIndent()

    private fun move(fromParty: StartedMockNode, toParty: Party, quantity: BigDecimal, tokenType: TokenType) = fromParty
        .startFlow(
            MoveFungibleTokens(
                Amount.fromDecimal(quantity, tokenType),
                toParty,
            )
        )

    @Test
    fun e2eBridgeAndRedemption() {
        val msftTokenType = issuingBank.issue(msftDescriptor, ISSUING_QUANTITY * BigDecimal(2), generalNotaryName)
        val aaplTokenType = issuingBank.issue(aaplDescriptor, ISSUING_QUANTITY * BigDecimal(2), generalNotaryName)

        assertNull(getAccountInfo(alice.mintToAta[msftTokenMint]), "Alice MSFT ATA should not be created yet")
        assertNull(getAccountInfo(alice.mintToAta[aaplTokenMint]), "Alice AAPL ATA should not be created yet")
        assertNull(getAccountInfo(bob.mintToAta[msftTokenMint]), "Bob MSFT ATA should not be created yet")
        assertNull(getAccountInfo(bob.mintToAta[aaplTokenMint]), "Bob AAPL ATA should not be created yet")

        move(issuingBank, alice.party, ISSUING_QUANTITY, msftTokenType).get()
        move(issuingBank, alice.party, ISSUING_QUANTITY, aaplTokenType).get()
        move(issuingBank, bob.party, ISSUING_QUANTITY, msftTokenType).get()
        move(issuingBank, bob.party, ISSUING_QUANTITY, aaplTokenType).get()

        // Bridge phase
        bridgeAndCheck(alice, msftTokenType, msftTokenMint)
        bridgeAndCheck(alice, aaplTokenType, aaplTokenMint)
        bridgeAndCheck(bob, msftTokenType, msftTokenMint)
        bridgeAndCheck(bob, aaplTokenType, aaplTokenMint)

        ensureLockedAmount(msftTokenType, MOVE_QUANTITY * BigDecimal(2))
        ensureLockedAmount(aaplTokenType, MOVE_QUANTITY * BigDecimal(2))

        // Redemption phase
        redeemAndCheck(alice, msftTokenMint, msftTokenType)
        ensureLockedAmount(msftTokenType, MOVE_QUANTITY)
        redeemAndCheck(alice, aaplTokenMint, aaplTokenType)
        ensureLockedAmount(aaplTokenType, MOVE_QUANTITY)
        redeemAndCheck(bob, msftTokenMint, msftTokenType)
        ensureLockedAmount(msftTokenType, BigDecimal.ZERO)
        redeemAndCheck(bob, aaplTokenMint, aaplTokenType)
        ensureLockedAmount(aaplTokenType, BigDecimal.ZERO)
    }

    private fun ensureLockedAmount(tokenType: TokenType, expectedLockedAmount: BigDecimal) {
        eventually(5.seconds) {
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
                assertTrue(
                    lockedAmount == expectedLockedAmount,
                    "Expected $expectedLockedAmount ${tokenType.tokenIdentifier} tokens locked, but was $lockedAmount"
                )
            }
        }
    }

    private fun bridgeAndCheck(stakeholderInfo: CordaUserBridgingAccounts, tokenType: TokenType, mint: PublicKey) {
        val tokenAccount = requireNotNull(stakeholderInfo.mintToAta[mint]) { "Token account must not be null" }
        move(stakeholderInfo.node, bridgeAuthority.party, MOVE_QUANTITY, tokenType).get()
        val party = stakeholderInfo.node.party()
        assertEquals(
            ISSUING_QUANTITY - MOVE_QUANTITY,
            stakeholderInfo.node.myTokenBalance(issuingBankParty, tokenType),
            "${party.name} transferred some of ${tokenType.tokenIdentifier} shares",
        )
        // We need to wait for the vault listener to process the newly received token
        eventually(duration = 10.seconds) {
            assertEquals(
                BigDecimal.ZERO,
                bridgeAuthority.node.myTokenBalance(issuingBankParty, tokenType),
                "Bridge Authority has no longer ${tokenType.tokenIdentifier} shares, they are under Locking Identity",
            )
        }
        val fungibleTokens = bridgeAuthority.node.getAllFungibleTokens(issuingBankParty, tokenType)
        assert(fungibleTokens.isNotEmpty()) {
            "There should be at least one ${tokenType.tokenIdentifier} fungible token in Bridge Authority vault"
        }
        val accountInfo = getAccountInfo(tokenAccount)
        assertAtaAccount(accountInfo, mint, stakeholderInfo.signer.account)
        // SPL Token RPC returns decimal strings with trailing zeros trimmed,
        // BigDecimal.equals is scale-sensitive (1.0 != 1.00), so we compare numeric value instead.
        eventually(duration = 10.seconds) {
            assertThat(getSolanaTokenBalance(tokenAccount))
                .describedAs("Solana ${tokenType.tokenIdentifier} token amount numerically equals Corda bridged amount")
                .isEqualByComparingTo(MOVE_QUANTITY)
        }
    }

    private fun redeemAndCheck(stakeholderInfo: CordaUserBridgingAccounts, mint: PublicKey, tokenType: TokenType) {
        val fromTokenAccount = requireNotNull(stakeholderInfo.mintToAta[mint]) {
            "Source token account must not be null"
        }
        val toTokenAccount = bridgeAuthority.redemptionTokenAccountForPartyAndMint(stakeholderInfo.party, mint)
        transfer(stakeholderInfo.signer, fromTokenAccount, toTokenAccount, MOVE_QUANTITY.toRawAmount())
        val party = stakeholderInfo.node.party()
        val balance = getSolanaTokenBalance(toTokenAccount)
        eventually(duration = 10.seconds) {
            assert(
                balance.compareTo(MOVE_QUANTITY) == 0,
            ) { "Redemption token account has $balance instead $MOVE_QUANTITY after transfer - party ${party.name}" }
        }
        eventually(duration = 10.seconds) {
            assertEquals(
                ISSUING_QUANTITY,
                stakeholderInfo.node.myTokenBalance(issuingBankParty, tokenType),
                "${party.name} received redeemed ${tokenType.tokenIdentifier} shares back",
            )
        }
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
        val error = testValidator.client
            .sendAndConfirm(
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
            ).metadata.err
        assertNull(error, "Token transfer failed with error: $error")
    }

    private fun BigDecimal.toRawAmount(): Long {
        return (this * BigDecimal(10L).pow(TOKEN_DECIMALS)).longValueExact()
    }

    private fun StartedMockNode.party() = this.info.legalIdentities.first()

    private fun getSolanaTokenBalance(publicKey: PublicKey): BigDecimal {
        return testValidator
            .client
            .getTokenAccountBalance(publicKey.base58(), testValidator.rpcParams)
            .checkResponse("getTokenAccountBalance")!!
            .uiAmountString
            .toBigDecimal()
    }

    private fun getAccountInfo(publicKey: PublicKey?): AccountInfo? {
        requireNotNull(publicKey) { "PublicKey must not be null" }
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
}

interface TokenTypeDescriptor {
    val ticker: String
    val fractionDigits: Int
    val tokenTypeIdentifier: String
}
