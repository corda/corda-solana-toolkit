package com.r3.corda.lib.solana.bridging.testing

import com.r3.corda.lib.solana.bridging.token.testing.IssueSimpleTokenFlow
import com.r3.corda.lib.tokens.contracts.types.TokenType
import net.corda.core.identity.CordaX500Name
import net.corda.testing.node.StartedMockNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SimpleDescriptor(
    override val ticker: String,
    override val fractionDigits: Int,
) : Descriptor {
    override val tokenTypeIdentifier: String = ticker
}

class SimpleTokenFlowTests : TokenFlowTestBase() {
    override val msftDescriptor: Descriptor = SimpleDescriptor("MSFT", TOKEN_DECIMALS)
    override val aaplDescriptor: Descriptor = SimpleDescriptor("AAPL", TOKEN_DECIMALS)

    override fun StartedMockNode.issue(
        tokenDescriptor: Descriptor,
        amount: Long,
        notaryName: CordaX500Name,
    ): TokenType {
        val tokenType = TokenType(tokenDescriptor.ticker, tokenDescriptor.fractionDigits)
        startFlow(IssueSimpleTokenFlow(tokenType, amount, notaryName)).get()
        assertEquals(amount, myTokenBalance(info.legalIdentities.first(), tokenType))
        return tokenType
    }

    @Test
    override fun bridgeTest() {
        super.bridgeTest()
    }
}
