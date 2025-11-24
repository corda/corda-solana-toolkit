package com.r3.corda.lib.solana.bridging.token.test

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.client.api.AccountInfo
import com.lmax.solana4j.encoding.SolanaEncoding
import com.lmax.solana4j.programs.AssociatedTokenProgram
import com.lmax.solana4j.programs.Token2022Program
import com.r3.corda.lib.solana.bridging.token.flows.toPublicKey
import com.r3.corda.lib.solana.bridging.token.flows.tokenProgramId
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
import net.corda.core.utilities.seconds
import net.corda.solana.notary.common.Signer
import net.corda.solana.notary.common.rpc.DefaultRpcParams
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.notary.common.rpc.sendAndConfirm
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.common.internal.eventually
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
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import java.lang.IllegalStateException
import java.math.BigDecimal
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

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
    private lateinit var bridgeAuthorityWalletFile: Path
    private lateinit var testValidator: SolanaTestValidator

    private lateinit var msftTokenMint: PublicKey
    private lateinit var aaplTokenMint: PublicKey
    private lateinit var aliceSigner: Signer
    private lateinit var aliceMsftBridgeTokenAccount: PublicKey
    private lateinit var aliceAaplBridgeTokenAccount: PublicKey
    private lateinit var aliceMsftRedemptionTokenAccount: PublicKey
    private lateinit var aliceAaplRedemptionTokenAccount: PublicKey

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

        msftTokenMint = testValidator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        aaplTokenMint = testValidator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        aliceMsftBridgeTokenAccount = AssociatedTokenProgram
            .deriveAddress(
                aliceSigner.account,
                Token2022.PROGRAM_ID.toPublicKey(),
                msftTokenMint
            ).address()
        aliceAaplBridgeTokenAccount = AssociatedTokenProgram
            .deriveAddress(
                aliceSigner.account,
                Token2022.PROGRAM_ID.toPublicKey(),
                aaplTokenMint
            ).address()
        // This will be ATA in real-life scenario. For the test we create it automatically.
        aliceMsftRedemptionTokenAccount = testValidator.createTokenAccount(bridgeAuthoritySigner, msftTokenMint)
        aliceAaplRedemptionTokenAccount = testValidator.createTokenAccount(bridgeAuthoritySigner, aaplTokenMint)
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
            "participants" to mapOf(aliceIdentity.name.toString() to aliceSigner.account.base58()),
            "redemptionWalletAccountToHolder" to mapOf(
                bridgeAuthoritySigner.account.base58() to aliceIdentity.name.toString()
            ),
            "mints" to mapOf(
                msftDescriptor.tokenTypeIdentifier to msftTokenMint.base58(),
                aaplDescriptor.tokenTypeIdentifier to aaplTokenMint.base58(),
            ),
            "mintAuthorities" to mapOf(
                msftDescriptor.tokenTypeIdentifier to mintAuthoritySigner.account.base58(),
                aaplDescriptor.tokenTypeIdentifier to mintAuthoritySigner.account.base58(),
            ),
            "lockingIdentityLabel" to UUID.randomUUID().toString(),
            "solanaNotaryName" to solanaNotaryName.toString(),
            "generalNotaryName" to generalNotaryName.toString(),
            "solanaWsUrl" to SolanaTestValidator.WS_URL,
            "solanaRpcUrl" to SolanaTestValidator.RPC_URL,
            "bridgeAuthorityWalletFile" to bridgeAuthorityWalletFile.toString(),
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
        if (::network.isInitialized) {
            network.stopNodes()
        }
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
    @Suppress("LongMethod")
    fun e2eBridgeAndRedemption() {
        val msftTokenType = issuingBank.issue(msftDescriptor, ISSUING_QUANTITY, generalNotaryName)
        val aaplTokenType = issuingBank.issue(aaplDescriptor, ISSUING_QUANTITY, generalNotaryName)

        assertNull(getAccountInfo(aliceMsftBridgeTokenAccount), "Alice MSFT ATA should not be created yet")
        assertNull(getAccountInfo(aliceAaplBridgeTokenAccount), "Alice AAPL ATA should not be created yet")

        move(issuingBank, aliceParty, ISSUING_QUANTITY, msftTokenType).get()
        move(issuingBank, aliceParty, ISSUING_QUANTITY, aaplTokenType).get()
        move(alice, bridgeAuthorityParty, MOVE_QUANTITY, msftTokenType).get()
        move(alice, bridgeAuthorityParty, MOVE_QUANTITY, aaplTokenType).get()

        assertEquals(
            ISSUING_QUANTITY - MOVE_QUANTITY,
            alice.myTokenBalance(issuingBankParty, msftTokenType),
            "Alice transferred some of MSFT shares",
        )

        assertEquals(
            ISSUING_QUANTITY - MOVE_QUANTITY,
            alice.myTokenBalance(issuingBankParty, aaplTokenType),
            "Alice transferred some of AAPL shares",
        )

        // We need to wait for the vault listener to process the newly received MSFT token
        eventually(duration = 5.seconds) {
            assertEquals(
                BigDecimal.ZERO,
                bridgeAuthority.myTokenBalance(issuingBankParty, msftTokenType),
                "Bridge Authority has no longer MSFT shares, they are under Locking Identity"
            )
            assertEquals(
                BigDecimal.ZERO,
                bridgeAuthority.myTokenBalance(issuingBankParty, aaplTokenType),
                "Bridge Authority has no longer AAPL shares, they are under Locking Identity"
            )
        }

        val msftFungibleToken = bridgeAuthority.getAllFungibleTokens(issuingBankParty, msftTokenType).singleOrNull()
        val aaplFungibleToken = bridgeAuthority.getAllFungibleTokens(issuingBankParty, aaplTokenType).singleOrNull()
        assertNotNull(msftFungibleToken, "There should be single MSFT fungible token in Bridge Authority vault")
        assertNotNull(aaplFungibleToken, "There should be single AAPL fungible token in Bridge Authority vault")
        assertTrue(
            msftFungibleToken.holder !in setOf(aliceParty, bridgeAuthorityParty),
            "Bridge Authority moved MSFT under Lock Identity (CI) ownership as neither BA nor Alice holds the token",
        ) // We don't know Confidential Identity upfront, so indirect check know participants do not own the token
        assertTrue(
            aaplFungibleToken.holder !in setOf(aliceParty, bridgeAuthorityParty),
            "Bridge Authority moved AAPL under Lock Identity (CI) ownership as neither BA nor Alice holds the token",
        )
        assertEquals(
            MOVE_QUANTITY,
            msftFungibleToken.amount.toDecimal(),
            "Lock Identity received expected number of MSFT shares",
        )
        assertEquals(
            MOVE_QUANTITY,
            aaplFungibleToken.amount.toDecimal(),
            "Lock Identity received expected number of AAPL shares",
        )

        val msftToken: StateAndRef<FungibleToken>? = bridgeAuthority.queryStates<FungibleToken>().firstOrNull {
            it.state.data.amount.token.tokenType == msftTokenType
        }
        assertNotNull(msftToken)
        val aaplToken: StateAndRef<FungibleToken>? = bridgeAuthority.queryStates<FungibleToken>().firstOrNull {
            it.state.data.amount.token.tokenType == aaplTokenType
        }
        assertNotNull(aaplToken)

        val msftAccountInfo = getAccountInfo(aliceMsftBridgeTokenAccount)
        assertAtaAccount(msftAccountInfo, msftTokenMint, aliceSigner.account)
        val aaplAccountInfo = getAccountInfo(aliceAaplBridgeTokenAccount)
        assertAtaAccount(aaplAccountInfo, aaplTokenMint, aliceSigner.account)

        // SPL Token RPC returns decimal strings with trailing zeros trimmed,
        // BigDecimal.equals is scale-sensitive (1.0 != 1.00), so we compare numeric value instead.
        eventually(duration = 5.seconds) {
            assertThat(getSolanaTokenBalance(aliceMsftBridgeTokenAccount))
                .describedAs("Solana MSFT token amount numerically equals Corda bridged amount")
                .isEqualByComparingTo(MOVE_QUANTITY)
        }
        eventually(duration = 5.seconds) {
            assertThat(getSolanaTokenBalance(aliceAaplBridgeTokenAccount))
                .describedAs("Solana AAPL token amount numerically equals Corda bridged amount")
                .isEqualByComparingTo(MOVE_QUANTITY)
        }

        // Simulate redemption transfer for Alice MSFT account on Solana
        transfer(aliceSigner, aliceMsftBridgeTokenAccount, aliceMsftRedemptionTokenAccount, MOVE_QUANTITY.toRawAmount())
        // We need to wait for the websocket listener to process the newly received event
        eventually(duration = 10.seconds) {
            assertEquals(
                ISSUING_QUANTITY,
                alice.myTokenBalance(issuingBankParty, msftTokenType),
                "Alice received redeemed MSFT shares back",
            )
        }
        val msftFungibleTokens = bridgeAuthority.getAllFungibleTokens(issuingBankParty, msftTokenType)
        assertTrue(msftFungibleTokens.isEmpty(), "No  MSFT shares left in Bridge Authority vault")

        transfer(
            aliceSigner,
            aliceAaplBridgeTokenAccount,
            aliceAaplRedemptionTokenAccount,
            MOVE_QUANTITY.toRawAmount(),
        )
        eventually(duration = 5.seconds) {
            assertEquals(
                ISSUING_QUANTITY,
                alice.myTokenBalance(issuingBankParty, aaplTokenType),
                "Alice received redeemed AAPL shares back",
            )
        }
        val aaplFungibleTokens = bridgeAuthority.getAllFungibleTokens(issuingBankParty, aaplTokenType)
        assertTrue(aaplFungibleTokens.isEmpty(), "No  AAPL shares left in Bridge Authority vault")
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
            DefaultRpcParams()
        )
    }

    private fun BigDecimal.toRawAmount(): Long {
        return (this * BigDecimal(10L).pow(TOKEN_DECIMALS)).longValueExact()
    }

    private fun getSolanaTokenBalance(publicKey: PublicKey): BigDecimal {
        return testValidator
            .client
            .getTokenAccountBalance(publicKey.base58(), testValidator.rpcParams)
            .checkResponse("getTokenAccountBalance")!!
            .uiAmountString
            .toBigDecimal()
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
}

interface TokenTypeDescriptor {
    val ticker: String
    val fractionDigits: Int
    val tokenTypeIdentifier: String
}
