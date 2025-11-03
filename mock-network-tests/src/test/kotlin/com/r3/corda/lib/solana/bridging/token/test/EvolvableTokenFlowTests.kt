package com.r3.corda.lib.solana.bridging.token.test

import com.r3.corda.lib.solana.bridging.token.test.FlowsTest.Companion.TOKEN_DECIMALS
import com.r3.corda.lib.solana.bridging.token.testing.IssueEvolvableTokenTypeFlow
import com.r3.corda.lib.tokens.contracts.types.TokenType
import net.corda.core.identity.CordaX500Name
import net.corda.testing.node.StartedMockNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertNotNull
import java.util.UUID

class EvolvableDescriptor(
    override val ticker: String,
    override val fractionDigits: Int = TOKEN_DECIMALS,
) : TokenTypeDescriptor {
    override val tokenTypeIdentifier: String = UUID.randomUUID().toString()
}

class EvolvableTokenFlowTests : FlowsTest() {
    override val msftDescriptor: TokenTypeDescriptor = EvolvableDescriptor(MSFT_TICKER)
    override val aaplDescriptor: TokenTypeDescriptor = EvolvableDescriptor(APPL_TICKER)

    override fun StartedMockNode.issue(
        tokenDescriptor: TokenTypeDescriptor,
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
}
