package com.r3.corda.lib.solana.bridging.token.test

import com.r3.corda.lib.solana.bridging.token.test.FlowsTest.Companion.TOKEN_DECIMALS
import com.r3.corda.lib.solana.bridging.token.testing.IssueSimpleTokenFlow
import com.r3.corda.lib.tokens.contracts.types.TokenType
import net.corda.core.identity.CordaX500Name
import net.corda.testing.node.StartedMockNode
import org.junit.jupiter.api.Assertions.assertEquals
import java.math.BigDecimal

class SimpleDescriptor(
    override val ticker: String,
    override val fractionDigits: Int = TOKEN_DECIMALS,
) : TokenTypeDescriptor {
    override val tokenTypeIdentifier: String = ticker
}

class SimpleTokenFlowTests : FlowsTest() {
    override val msftDescriptor: TokenTypeDescriptor = SimpleDescriptor(MSFT_TICKER)
    override val aaplDescriptor: TokenTypeDescriptor = SimpleDescriptor(APPL_TICKER)

    override fun StartedMockNode.issue(
        tokenDescriptor: TokenTypeDescriptor,
        amount: BigDecimal,
        notaryName: CordaX500Name,
    ): TokenType {
        val tokenType = TokenType(tokenDescriptor.ticker, tokenDescriptor.fractionDigits)
        startFlow(IssueSimpleTokenFlow(tokenType, amount, notaryName)).get()
        assertEquals(amount, myTokenBalance(info.legalIdentities.first(), tokenType))
        return tokenType
    }
}
