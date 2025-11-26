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
import net.corda.testing.core.BOB_NAME
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
    private lateinit var issuingBankParty: Party
    private lateinit var alice: StartedMockNode
    private lateinit var aliceParty: Party
    private lateinit var bob: StartedMockNode
    private lateinit var bobParty: Party
    private lateinit var bridgeAuthority: StartedMockNode
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
        private val bobIdentity = TestIdentity(BOB_NAME)
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
    private lateinit var aliceBridgeAuthorityRedemptionWallet: Signer
    private lateinit var bobBridgeAuthorityRedemptionWallet: Signer
    private lateinit var bridgeAuthorityMintWalletFile: Path
    private lateinit var bridgeAuthoirityMintWallet: Signer
    private lateinit var testValidator: SolanaTestValidator

    private lateinit var msftTokenMint: PublicKey
    private lateinit var aaplTokenMint: PublicKey
    private lateinit var aliceSigner: Signer
    private lateinit var bobSigner: Signer
    private lateinit var aliceMsftBridgeTokenAccount: PublicKey
    private lateinit var aliceAaplBridgeTokenAccount: PublicKey
    private lateinit var aliceMsftRedemptionTokenAccount: PublicKey
    private lateinit var aliceAaplRedemptionTokenAccount: PublicKey
    private lateinit var bobMsftBridgeTokenAccount: PublicKey
    private lateinit var bobAaplBridgeTokenAccount: PublicKey
    private lateinit var bobMsftRedemptionTokenAccount: PublicKey
    private lateinit var bobAaplRedemptionTokenAccount: PublicKey

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
        bridgeAuthorityMintWalletFile = randomKeypairFile(custodiedKeysDir)
        bridgeAuthoirityMintWallet = Signer.fromFile(bridgeAuthorityMintWalletFile)
        aliceBridgeAuthorityRedemptionWallet = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
        bobBridgeAuthorityRedemptionWallet = Signer.fromFile(randomKeypairFile(custodiedKeysDir))
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
        testValidator.fundAccount(10, aliceBridgeAuthorityRedemptionWallet)
        testValidator.fundAccount(10, bobBridgeAuthorityRedemptionWallet)
        testValidator.fundAccount(10, bridgeAuthoirityMintWallet)

        aliceSigner = Signer.random()
        bobSigner = Signer.random()
        testValidator.fundAccount(10, aliceSigner)
        testValidator.fundAccount(10, bobSigner)

        msftTokenMint = testValidator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        aaplTokenMint = testValidator.createToken(mintAuthoritySigner, decimals = TOKEN_DECIMALS.toByte())
        aliceMsftBridgeTokenAccount = AssociatedTokenProgram
            .deriveAddress(aliceSigner.account, Token2022.PROGRAM_ID.toPublicKey(), msftTokenMint)
            .address()
        aliceAaplBridgeTokenAccount = AssociatedTokenProgram
            .deriveAddress(aliceSigner.account, Token2022.PROGRAM_ID.toPublicKey(), aaplTokenMint)
            .address()
        // This will be ATA in real-life scenario. For the test we create it automatically.
        aliceMsftRedemptionTokenAccount =
            testValidator.createTokenAccount(aliceBridgeAuthorityRedemptionWallet, msftTokenMint)
        aliceAaplRedemptionTokenAccount =
            testValidator.createTokenAccount(aliceBridgeAuthorityRedemptionWallet, aaplTokenMint)
        bobMsftBridgeTokenAccount = AssociatedTokenProgram
            .deriveAddress(bobSigner.account, Token2022.PROGRAM_ID.toPublicKey(), msftTokenMint)
            .address()
        bobAaplBridgeTokenAccount = AssociatedTokenProgram
            .deriveAddress(bobSigner.account, Token2022.PROGRAM_ID.toPublicKey(), aaplTokenMint)
            .address()
        bobMsftRedemptionTokenAccount =
            testValidator.createTokenAccount(bobBridgeAuthorityRedemptionWallet, msftTokenMint)
        bobAaplRedemptionTokenAccount =
            testValidator.createTokenAccount(bobBridgeAuthorityRedemptionWallet, aaplTokenMint)
    }

    fun stopTestValidator() {
        if (::testValidator.isInitialized) {
            testValidator.close()
        }
    }

    private fun startCordaNetwork() {
        val bridgingContractsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.contracts")
        val bridgingFlowsCordapp = TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.flows")
        val baConfig = buildBridgeAuthorityConfig()
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
        alice = network.createPartyNode(aliceIdentity.name)
        bob = network.createPartyNode(bobIdentity.name)
        bobParty = bob.info.legalIdentities.first()
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

    fun buildBridgeAuthorityConfig(): Map<String, Any> {
        return mapOf(
            "participants" to mapOf(
                aliceIdentity.name.toString() to aliceSigner.account.base58(),
                bobIdentity.name.toString() to bobSigner.account.base58()
            ),
            "redemptionWalletAccountToHolder" to mapOf(
                aliceBridgeAuthorityRedemptionWallet.account.base58() to aliceIdentity.name.toString(),
                bobBridgeAuthorityRedemptionWallet.account.base58() to bobIdentity.name.toString()
            ),
            "mintsWithAuthorities" to mapOf(
                msftDescriptor.tokenTypeIdentifier to mapOf(
                    "tokenMint" to msftTokenMint.base58(),
                    "mintAuthority" to mintAuthoritySigner.account.base58(),
                ),
                aaplDescriptor.tokenTypeIdentifier to mapOf(
                    "tokenMint" to aaplTokenMint.base58(),
                    "mintAuthority" to mintAuthoritySigner.account.base58()
                ),
            ),
            "lockingIdentityLabel" to UUID.randomUUID().toString(),
            "solanaNotaryName" to solanaNotaryName.toString(),
            "generalNotaryName" to generalNotaryName.toString(),
            "solanaWsUrl" to SolanaTestValidator.WS_URL,
            "solanaRpcUrl" to SolanaTestValidator.RPC_URL,
            "bridgeAuthorityWalletFile" to bridgeAuthorityMintWalletFile.toString(),
        )
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

        assertNull(getAccountInfo(aliceMsftBridgeTokenAccount), "Alice MSFT ATA should not be created yet")
        assertNull(getAccountInfo(aliceAaplBridgeTokenAccount), "Alice AAPL ATA should not be created yet")
        assertNull(getAccountInfo(bobMsftBridgeTokenAccount), "Bob MSFT ATA should not be created yet")
        assertNull(getAccountInfo(bobAaplBridgeTokenAccount), "Bob AAPL ATA should not be created yet")

        move(issuingBank, aliceParty, ISSUING_QUANTITY, msftTokenType).get()
        move(issuingBank, aliceParty, ISSUING_QUANTITY, aaplTokenType).get()
        move(issuingBank, bobParty, ISSUING_QUANTITY, msftTokenType).get()
        move(issuingBank, bobParty, ISSUING_QUANTITY, aaplTokenType).get()

        // Bridge phase
        bridgeAndCheck(alice, aliceSigner, msftTokenType, aliceMsftBridgeTokenAccount, msftTokenMint)
        bridgeAndCheck(alice, aliceSigner, aaplTokenType, aliceAaplBridgeTokenAccount, aaplTokenMint)
        bridgeAndCheck(bob, bobSigner, msftTokenType, bobMsftBridgeTokenAccount, msftTokenMint)
        bridgeAndCheck(bob, bobSigner, aaplTokenType, bobAaplBridgeTokenAccount, aaplTokenMint)

        ensureLockedAmount(msftTokenType, MOVE_QUANTITY * BigDecimal(2))
        ensureLockedAmount(aaplTokenType, MOVE_QUANTITY * BigDecimal(2))

        // Redemption phase
        redeemAndCheck(alice, aliceSigner, msftTokenType, aliceMsftBridgeTokenAccount, aliceMsftRedemptionTokenAccount)
        ensureLockedAmount(msftTokenType, MOVE_QUANTITY)
        redeemAndCheck(alice, aliceSigner, aaplTokenType, aliceAaplBridgeTokenAccount, aliceAaplRedemptionTokenAccount)
        ensureLockedAmount(aaplTokenType, MOVE_QUANTITY)
        redeemAndCheck(bob, bobSigner, msftTokenType, bobMsftBridgeTokenAccount, bobMsftRedemptionTokenAccount)
        ensureLockedAmount(msftTokenType, BigDecimal.ZERO)
        redeemAndCheck(bob, bobSigner, aaplTokenType, bobAaplBridgeTokenAccount, bobAaplRedemptionTokenAccount)
        ensureLockedAmount(aaplTokenType, BigDecimal.ZERO)
    }

    private fun ensureLockedAmount(tokenType: TokenType, expectedLockedAmount: BigDecimal) {
        eventually(5.seconds) {
            val lockedFungibleTokens = bridgeAuthority.getAllFungibleTokens(issuingBankParty, tokenType).filter {
                it.holder !in listOf(aliceParty, bobParty, bridgeAuthorityParty) // Locking identity holds locked tokens
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

    private fun bridgeAndCheck(
        node: StartedMockNode,
        signer: Signer,
        tokenType: TokenType,
        tokenAccount: PublicKey,
        tokenMint: PublicKey,
    ) {
        move(node, bridgeAuthorityParty, MOVE_QUANTITY, tokenType).get()
        val party = node.party()
        assertEquals(
            ISSUING_QUANTITY - MOVE_QUANTITY,
            node.myTokenBalance(issuingBankParty, tokenType),
            "${party.name} transferred some of ${tokenType.tokenIdentifier} shares",
        )
        // We need to wait for the vault listener to process the newly received token
        eventually(duration = 10.seconds) {
            assertEquals(
                BigDecimal.ZERO,
                bridgeAuthority.myTokenBalance(issuingBankParty, tokenType),
                "Bridge Authority has no longer ${tokenType.tokenIdentifier} shares, they are under Locking Identity",
            )
        }
        val fungibleTokens = bridgeAuthority.getAllFungibleTokens(issuingBankParty, tokenType)
        assert(
            fungibleTokens.isNotEmpty(),
            { "There should be at least one ${tokenType.tokenIdentifier} fungible token in Bridge Authority vault" },
        )
        val accountInfo = getAccountInfo(tokenAccount)
        assertAtaAccount(accountInfo, tokenMint, signer.account)
        // SPL Token RPC returns decimal strings with trailing zeros trimmed,
        // BigDecimal.equals is scale-sensitive (1.0 != 1.00), so we compare numeric value instead.
        eventually(duration = 10.seconds) {
            assertThat(getSolanaTokenBalance(tokenAccount))
                .describedAs("Solana ${tokenType.tokenIdentifier} token amount numerically equals Corda bridged amount")
                .isEqualByComparingTo(MOVE_QUANTITY)
        }
    }

    private fun redeemAndCheck(
        node: StartedMockNode,
        signer: Signer,
        tokenType: TokenType,
        fromTokenAccount: PublicKey,
        toTokenAccount: PublicKey,
    ) {
        transfer(signer, fromTokenAccount, toTokenAccount, MOVE_QUANTITY.toRawAmount())
        val party = node.party()
        val balance = getSolanaTokenBalance(toTokenAccount)
        eventually(duration = 10.seconds) {
            assert(
                balance.compareTo(MOVE_QUANTITY) == 0,
            ) { "Redemption token account has $balance instead $MOVE_QUANTITY after transfer - party ${party.name}" }
        }
        eventually(duration = 10.seconds) {
            assertEquals(
                ISSUING_QUANTITY,
                node.myTokenBalance(issuingBankParty, tokenType),
                "${party.name} received redeemed ${tokenType.tokenIdentifier} shares back",
            )
        }
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
