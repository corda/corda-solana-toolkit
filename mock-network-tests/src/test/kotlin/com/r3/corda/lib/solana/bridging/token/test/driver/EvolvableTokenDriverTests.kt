package com.r3.corda.lib.solana.bridging.token.test.driver

import com.r3.corda.lib.solana.bridging.token.test.EvolvableDescriptor
import com.r3.corda.lib.solana.bridging.token.test.FlowTests.Companion.APPL_TICKER
import com.r3.corda.lib.solana.bridging.token.test.FlowTests.Companion.MSFT_TICKER
import com.r3.corda.lib.solana.bridging.token.test.TokenTypeDescriptor
import com.r3.corda.lib.solana.bridging.token.testing.IssueEvolvableTokenTypeFlow
import com.r3.corda.lib.tokens.contracts.types.TokenType
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import java.math.BigDecimal
import java.util.UUID

class EvolvableTokenDriverTests : DriverTests() {
    override val msftDescriptor: TokenTypeDescriptor = EvolvableDescriptor(MSFT_TICKER)
    override val appleDescriptor: TokenTypeDescriptor = EvolvableDescriptor(APPL_TICKER)

    override fun CordaParticipant.issue(
        tokenTypeDescriptor: TokenTypeDescriptor,
        amount: BigDecimal,
        notaryName: CordaX500Name,
    ): TokenType {
        val issuedTypeToken = node.rpc
            .startFlow(
                ::IssueEvolvableTokenTypeFlow,
                tokenTypeDescriptor.ticker,
                UUID.fromString(tokenTypeDescriptor.tokenTypeIdentifier),
                amount,
                tokenTypeDescriptor.fractionDigits,
                notaryName,
            ).returnValue
            .getOrThrow()
        return issuedTypeToken.tokenType
    }
}
