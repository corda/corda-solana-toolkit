package com.r3.corda.lib.solana.bridging.token.test.driver

import com.r3.corda.lib.solana.bridging.token.testing.IssueSimpleTokenFlow
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.selection.tokenAmountWithIssuerCriteria
import com.r3.corda.lib.tokens.workflows.utilities.rowsToAmount
import com.r3.corda.lib.tokens.workflows.utilities.sumTokenCriteria
import net.corda.core.contracts.Amount
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.TestIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.use

// TODO this is an early test demonstrator
class DriverTests {
    private val aliceIdentity = TestIdentity(ALICE_NAME)
    private val generalNotaryName = CordaX500Name("Notary Service", "Zurich", "CH")

    @Suppress("LongMethod")
    @Test
    fun test() {
        driver(
            DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = true,
                cordappsForAllNodes = listOf(
                    TestCordapp.findCordapp("com.r3.corda.lib.tokens.contracts"),
                    TestCordapp.findCordapp("com.r3.corda.lib.tokens.workflows"),
                    TestCordapp.findCordapp("com.r3.corda.lib.solana.bridging.token.testing"),
                )
            )
        ) {
            val user = User("user1", "test", permissions = setOf("ALL"))
            var node = startNode(
                providedName = aliceIdentity.name,
                rpcUsers = listOf(user)
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

            var page = node.rpc.vaultQueryByCriteria(criteria, FungibleToken::class.java)
            var balanceBefore: Amount<TokenType> = rowsToAmount(tokenType, page)
            assertEquals(100000, balanceBefore.quantity)

            // TODO issue many token moved to Bridge Authority hoping some of them will be not bridged while node stops
            node.stop()

            val baseDir = node.baseDirectory.toFile()
            val jdbcUrl = "jdbc:h2:file:${baseDir.resolve("persistence")};DB_CLOSE_ON_EXIT=FALSE;AUTOCOMMIT=ON"
            DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
                conn.createStatement().use { st ->
                    val rs = st.executeQuery(
                        """
                        SELECT SUM(AMOUNT) FROM fungible_token
                        WHERE TOKEN_IDENTIFIER = '${tokenType.tokenIdentifier}' AND issuer = '${issuer.name}'
                        """.trimIndent()
                    )
                    rs.next()
                    val dbQuantity = rs.getLong(1)
                    assertEquals(100000, dbQuantity)
                    // TODO some or all tokens not moved yet from BridgeAuthority to  are in  progress
                }
            }
            DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
                conn.createStatement().use { st ->
                    val rs = st.executeQuery(
                        """
                        SELECT COUNT(*)
                        FROM NODE_CHECKPOINTS
                        """.trimIndent()
                    )
                    rs.next()
                    val inflightCount = rs.getInt(1)
                    assertEquals(0, inflightCount, "Expected no in-progress flows")
                    // TODO ideally some flows are in  progress
                }
            }

            node = startNode(providedName = aliceIdentity.name, rpcUsers = listOf(user)).getOrThrow()
            page = node.rpc.vaultQueryByCriteria(criteria, FungibleToken::class.java)
            balanceBefore = rowsToAmount(tokenType, page)
            assertEquals(100000, balanceBefore.quantity)

            // TODO check if all is bridged
        }
    }
}
