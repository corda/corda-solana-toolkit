package com.r3.corda.lib.solana.bridging.token.test.driver

import com.r3.corda.lib.solana.bridging.token.test.MockNetworkTest.Companion.APPL_TICKER
import com.r3.corda.lib.solana.bridging.token.test.MockNetworkTest.Companion.MSFT_TICKER
import com.r3.corda.lib.solana.bridging.token.test.SimpleDescriptor
import com.r3.corda.lib.solana.bridging.token.test.TokenTypeDescriptor
import com.r3.corda.lib.solana.bridging.token.testing.IssueSimpleTokenFlow
import com.r3.corda.lib.tokens.contracts.types.TokenType
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import java.math.BigDecimal

class SimpleTokenDriverTests : DriverTest() {
    override val msftDescriptor: TokenTypeDescriptor = SimpleDescriptor(MSFT_TICKER)
    override val appleDescriptor: TokenTypeDescriptor = SimpleDescriptor(APPL_TICKER)

    override fun CordaParticipant.issue(
        tokenTypeDescriptor: TokenTypeDescriptor,
        amount: BigDecimal,
        notaryName: CordaX500Name,
    ): TokenType {
        val tokenType = TokenType(tokenTypeDescriptor.ticker, tokenTypeDescriptor.fractionDigits)
        node.rpc
            .startFlow(
                ::IssueSimpleTokenFlow,
                tokenType,
                amount,
                notaryName,
            ).returnValue
            .getOrThrow()
        return tokenType
    }
}
