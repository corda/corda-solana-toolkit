package com.r3.corda.lib.solana.bridging.token.test

import com.r3.corda.lib.solana.testing.SolanaTestValidator
import net.corda.core.identity.CordaX500Name
import net.corda.testing.node.StartedMockNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import java.math.BigDecimal

class RecoveryTests : ValidatorTests() {
    override val msftDescriptor: TokenTypeDescriptor = SimpleDescriptor(MSFT_TICKER)
    override val aaplDescriptor: TokenTypeDescriptor = SimpleDescriptor(APPL_TICKER)

    override fun StartedMockNode.issue(
        tokenDescriptor: TokenTypeDescriptor,
        amount: BigDecimal,
        notaryName: CordaX500Name,
    ) = issueSimpleTokenFlow(tokenDescriptor, amount, notaryName)

    @Test
    fun `e2e redemption recovery`() {
        val msftTokenType = issuingBank.issue(msftDescriptor, ISSUING_QUANTITY, generalNotaryName)

        assertNull(
            validator.accounts().getAccountInfo(bob.mintToAta[msftTokenMint]!!),
            "Bob MSFT ATA should not be created yet"
        )

        move(issuingBank, bob.party, ISSUING_QUANTITY, msftTokenType).get()

        bob.setExpectedCordaBalance(msftTokenMint, ISSUING_QUANTITY)

        bridgeAndCheck(bob, msftTokenType, msftTokenMint, MOVE_QUANTITY_1)
        bridgeAndCheck(bob, msftTokenType, msftTokenMint, MOVE_QUANTITY_2)

        ensureLockedAmount(msftTokenType, MOVE_TOTAL_QUANTITY)

        val originalRpcPort = validator.rpcPort()
        val ledger = validator.ledger()

        // Restart the validator on an alternative RPC port which simulates a connection drop
        validator.close()
        while (true) {
            validator = SolanaTestValidator
                .builder()
                .ledger(ledger)
                .dynamicPorts()
                .start()
                .waitForReadiness()
            if (validator.rpcPort() != originalRpcPort) {
                break
            }
            // The validator has coincidentally restarted on the original port, which we don't want so we shut it down
            // and try again.
            validator.close()
        }

        // Before continuing, ensure the alternative config validator is available and responds to RPC calls
        ensureSolanaTokenAccountBalance(bob, msftTokenType, msftTokenMint)

        redeemAndCheck(bob, msftTokenMint, msftTokenType, MOVE_QUANTITY_3, false)

        // Restart the validator on the original RPC port simulating the connection restoration
        validator.close()
        validator = SolanaTestValidator
            .builder()
            .ledger(ledger)
            .dynamicPorts()
            .rpcPort(originalRpcPort)
            .start()
            .waitForReadiness()
        val expectedBobNodeBalance = MOVE_TOTAL_QUANTITY - MOVE_QUANTITY_3
        ensureLockedAmount(msftTokenType, expectedBobNodeBalance)
    }
}
