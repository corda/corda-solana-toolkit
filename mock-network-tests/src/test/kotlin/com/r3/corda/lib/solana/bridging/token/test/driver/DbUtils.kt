package com.r3.corda.lib.solana.bridging.token.test.driver

import com.r3.corda.lib.tokens.contracts.types.TokenType
import net.corda.core.identity.Party
import net.corda.testing.driver.NodeHandle
import org.junit.jupiter.api.Assertions.assertEquals
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.use

fun NodeHandle.checkMyTokens(tokenType: TokenType, issuer: Party, expectedQuantity: Long) {
    val nodeIdentity = this.nodeInfo.legalIdentities.first()
    this.get(tokenType, issuer, nodeIdentity) {
        val dbQuantity = it.getLong(1)
        assertEquals(expectedQuantity, dbQuantity)
    }
}

fun NodeHandle.checkConfidentialIdentityTokens(tokenType: TokenType, issuer: Party, expectedQuantity: Long) {
    this.get(tokenType, issuer, null) {
        val dbQuantity = it.getLong(1)
        assertEquals(expectedQuantity, dbQuantity)
    }
}

fun NodeHandle.get(tokenType: TokenType, issuer: Party, holder: Party?, check: (ResultSet) -> Unit) {
    val baseDir = this.baseDirectory.toFile()
    val jdbcUrl = "jdbc:h2:file:${baseDir.resolve("persistence")};DB_CLOSE_ON_EXIT=FALSE;AUTOCOMMIT=ON"
    DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
        conn.createStatement().use { st ->
            st
                .executeQuery(
                    "SELECT SUM(AMOUNT) FROM FUNGIBLE_TOKEN WHERE TOKEN_IDENTIFIER = '${tokenType.tokenIdentifier}' " +
                        "AND ISSUER = '${issuer.name}' AND HOLDER ${if (holder != null) " = '$holder'" else " is null"}"
                ).use {
                    it.next()
                    check(it)
                }
        }
    }
}

fun NodeHandle.checkPendingFlows(check: (ResultSet) -> Unit) {
    val baseDir = this.baseDirectory.toFile()
    val jdbcUrl = "jdbc:h2:file:${baseDir.resolve("persistence")};DB_CLOSE_ON_EXIT=FALSE;AUTOCOMMIT=ON"
    DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM NODE_CHECKPOINTS").use {
                it.next()
                check(it)
            }
        }
    }
}
