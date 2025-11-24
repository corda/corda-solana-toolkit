package com.r3.corda.lib.solana.bridging.token.test.driver

import com.lmax.solana4j.api.PublicKey
import com.lmax.solana4j.client.api.AccountInfo
import com.lmax.solana4j.encoding.SolanaEncoding
import com.lmax.solana4j.programs.AssociatedTokenProgram
import com.r3.corda.lib.solana.bridging.token.flows.toPublicKey
import com.r3.corda.lib.solana.bridging.token.flows.tokenProgramId
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
import net.corda.solana.notary.common.rpc.checkResponse
import net.corda.solana.sdk.internal.Token2022
import net.corda.testing.common.internal.eventually
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.TestIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.NodeParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import net.corda.testing.solana.SolanaTestValidator
import net.corda.testing.solana.randomKeypairFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

class DriverTests {
    companion object {
        private val ISSUING_QUANTITY = BigDecimal("2000.000")
        private val MOVE_QUANTITY = BigDecimal("10.250")
    }

    private val msftDescriptor: TokenTypeDescriptor = SimpleDescriptor(MSFT_TICKER)
    private lateinit var bridgeAuthorityParty: Party
    private lateinit var bridgeAuthority: NodeHandle
    private lateinit var mintAuthoritySigner: Signer
    private lateinit var bridgeAuthoritySigner: Signer
    private lateinit var bridgeAuthorityWalletFile: Path
    private val aliceIdentity = TestIdentity(ALICE_NAME)
    private lateinit var alice: NodeHandle
    private lateinit var aliceParty: Party
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
                        false,
                        customConfig = mapOf<String, Any>(
                            "notary.validating" to false,
                            "notary.notaryLegalIdentity" to "$solanaNotaryName",
                            "notary.solana.rpcUrl" to "$SolanaTestValidator.RPC_URL",
                            "notary.solana.notaryKeypairFile" to "$solanaNotaryKeyFile",
                            "notary.solana.custodiedKeysDir" to "$custodiedKeysDir",
                            "notary.solana.programWhitelist" to "[\"${Token2022.PROGRAM_ID}\"]",
                        ),
                        startInProcess = true,
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

            alice = startNode(
                providedName = aliceIdentity.name,
                rpcUsers = listOf(user)
            ).getOrThrow()
            aliceParty = alice.nodeInfo.legalIdentities.first()

            bridgeAuthority = startNode(
                NodeParameters(
                    providedName = bridgeAuthorityIdentity.name,
                    rpcUsers = listOf(user),
                ).withAdditionalCordapps(
                    setOf(bridgingFlowsCordapp.withConfig(baConfig), bridgingContractsCordapp)
                )
            ).getOrThrow()
            bridgeAuthorityParty = bridgeAuthority.nodeInfo.legalIdentities.first()

            val msftTokenType = alice.issue(msftDescriptor, ISSUING_QUANTITY, generalNotaryName)

            assertNull(getAccountInfo(aliceBridgeTokenAccount), "Alice ATA should not be created yet")

            val sig = alice.move(bridgeAuthorityParty, MOVE_QUANTITY, msftTokenType)
            assertNotNull(sig)

            val issuingBankParty = alice.nodeInfo.legalIdentities.first() // TODO
            Thread.sleep(30.seconds.toMillis())
            eventually(duration = 30.seconds) {
                assertEquals(
                    BigDecimal.ZERO,
                    bridgeAuthority.myTokenBalance(issuingBankParty, msftTokenType),
                    "Bridge Authority has no longer MSFT shares, they are under Locking Identity"
                )
            }
            val msftFungibleToken = bridgeAuthority
                .getAllFungibleTokens(issuingBankParty, msftTokenType)
                .singleOrNull()
            assertNotNull(msftFungibleToken, "There should be single MSFT fungible token in Bridge Authority vault")
            assertTrue(
                msftFungibleToken.holder !in setOf(aliceParty, bridgeAuthorityParty),
                "Bridge Authority moved MSFT under Lock Identity (CI) " +
                    "ownership as neither BA nor Alice holds the token",
            ) // We don't know Confidential Identity upfront, so indirect check who doesn't have the token
            assertEquals(
                MOVE_QUANTITY,
                msftFungibleToken.amount.toDecimal(),
                "Lock Identity received expected number of MSFT shares",
            )
            val token: StateAndRef<FungibleToken>? = bridgeAuthority.queryStates<FungibleToken>().firstOrNull {
                it.state.data.amount.token.tokenType == msftTokenType
            }
            assertNotNull(token)
            val accountInfo = getAccountInfo(aliceBridgeTokenAccount)
            assertAtaAccount(accountInfo, tokenMint, aliceSigner.account)
        }
    }

    fun NodeHandle.issue(
        tokenDescriptor: TokenTypeDescriptor,
        amount: BigDecimal,
        notaryName: CordaX500Name,
    ): TokenType {
        val tokenType = TokenType(tokenDescriptor.ticker, tokenDescriptor.fractionDigits)
        rpc
            .startFlow(
                ::IssueSimpleTokenFlow,
                tokenType,
                amount,
                notaryName,
            ).returnValue
            .getOrThrow()
        val myIdentity = nodeInfo.legalIdentities.first()
        assertEquals(amount, myTokenBalance(myIdentity, tokenType))
        return tokenType
    }

    private fun NodeHandle.getAllFungibleTokens(issuer: Party, stock: TokenType): List<FungibleToken> {
        val fungibleTokenType1 = rpc
            .startFlow(::QuerySimpleTokensFlow, issuer, stock)
            .returnValue
            .getOrThrow()
            .firstOrNull()
        val fungibleTokenType = fungibleTokenType1 ?: return emptyList()
        return rpc
            .vaultQueryBy<FungibleToken>()
            .states
            .map { it.state.data }
            .filter {
                it.amount.token == fungibleTokenType.state.data.issuedTokenType ||
                    it.amount.token == fungibleTokenType.state.data.tokenType
            }
    }

    fun NodeHandle.myTokenBalance(issuer: Party, tokenType: TokenType): BigDecimal {
        val myIdentity = nodeInfo.legalIdentities.first()
        val fungibleTokens = getAllFungibleTokens(issuer, tokenType).filter { it.holder == myIdentity }
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

    private fun NodeHandle.move(toParty: Party, quantity: BigDecimal, tokenType: TokenType) =
        rpc
            .startFlow(
                ::MoveFungibleTokens,
                Amount.fromDecimal(quantity, tokenType),
                toParty,
            ).returnValue
            .getOrThrow()

    private inline fun <reified T : ContractState> NodeHandle.queryStates(): List<StateAndRef<T>> {
        return rpc
            .vaultQueryBy<T>()
            .states
    }
}
