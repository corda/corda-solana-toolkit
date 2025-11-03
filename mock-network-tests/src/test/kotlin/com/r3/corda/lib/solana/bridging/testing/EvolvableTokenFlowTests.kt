package com.r3.corda.lib.solana.bridging.testing

import com.r3.corda.lib.solana.bridging.token.testing.IssueEvolvableTokenTypeFlow
import com.r3.corda.lib.tokens.contracts.types.TokenType
import net.corda.core.identity.CordaX500Name
import net.corda.testing.node.StartedMockNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import java.util.UUID

class EvolvableDescriptor(
    override val ticker: String,
    override val fractionDigits: Int,
) : Descriptor {
    override val tokenTypeIdentifier: String = UUID.randomUUID().toString()
}

class EvolvableTokenFlowTests : TokenFlowTestBase() {
    override val msftDescriptor: Descriptor = EvolvableDescriptor("MSFT", TOKEN_DECIMALS)
    override val aaplDescriptor: Descriptor = EvolvableDescriptor("AAPL", TOKEN_DECIMALS)

    override fun StartedMockNode.issue(
        tokenDescriptor: Descriptor,
        amount: Long,
        notaryName: CordaX500Name,
    ): TokenType {
        val issuedTypeToken = startFlow(
            IssueEvolvableTokenTypeFlow(
                tokenDescriptor.ticker,
                UUID.fromString(tokenDescriptor.tokenTypeIdentifier),
                amount,
                tokenDescriptor.fractionDigits,
                notaryName,
            )
        ).get()
        assertNotNull(issuedTypeToken)
        val tokenType = issuedTypeToken.tokenType
        assertEquals(amount, myTokenBalance(info.legalIdentities.first(), tokenType))
        return tokenType
    }

    @Test
    override fun bridgeTest() {
        super.bridgeTest()
    }
}
