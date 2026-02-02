package com.r3.corda.lib.solana.bridging.token.test

import com.r3.corda.lib.solana.bridging.token.test.ValidatorTests.Companion.TOKEN_DECIMALS
import net.corda.core.identity.CordaX500Name
import net.corda.testing.node.StartedMockNode
import java.math.BigDecimal

class SimpleDescriptor(
    override val ticker: String,
    override val fractionDigits: Int = TOKEN_DECIMALS,
) : TokenTypeDescriptor {
    override val tokenTypeIdentifier: String = ticker
}

class SimpleTokenFlowTests : FlowTests() {
    override val msftDescriptor: TokenTypeDescriptor = SimpleDescriptor(MSFT_TICKER)
    override val aaplDescriptor: TokenTypeDescriptor = SimpleDescriptor(APPL_TICKER)

    override fun StartedMockNode.issue(
        tokenDescriptor: TokenTypeDescriptor,
        amount: BigDecimal,
        notaryName: CordaX500Name,
    ) = issueSimpleTokenFlow(tokenDescriptor, amount, notaryName)
}
