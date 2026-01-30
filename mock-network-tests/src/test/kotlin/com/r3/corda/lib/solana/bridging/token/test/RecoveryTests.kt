package com.r3.corda.lib.solana.bridging.token.test

import com.r3.corda.lib.solana.testing.SolanaTestValidator
import net.corda.core.identity.CordaX500Name
import net.corda.testing.node.StartedMockNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import software.sava.rpc.json.http.client.SolanaRpcClient
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
    fun e2eRedemptionRecovery(validator: SolanaTestValidator) {
        val msftTokenType = issuingBank.issue(msftDescriptor, ISSUING_QUANTITY, generalNotaryName)

        assertNull(validator.client().call(SolanaRpcClient::getAccountInfo, bob.mintToAta[msftTokenMint]!!))

        move(issuingBank, bob.party, ISSUING_QUANTITY, msftTokenType).get()

        bob.setExpectedCordaBalance(msftTokenMint, ISSUING_QUANTITY)

        bridgeAndCheck(bob, msftTokenType, msftTokenMint, MOVE_QUANTITY_1)
        bridgeAndCheck(bob, msftTokenType, msftTokenMint, MOVE_QUANTITY_2)

        ensureLockedAmount(msftTokenType, MOVE_TOTAL_QUANTITY)

        // Restart the validator on an alternative configuration simulating the connection drop
//        validator.stopIfRunning()
//        validator = SolanaTestValidator().also {
//            it.start(validator.ledgerDir, SolanaTestValidator.ALTERNATIVE_RPC_PORT)
//        }

        // Before continuing, ensure the alternative config validator is available and responds to RPC calls
        ensureSolanaTokenAccountBalance(bob, msftTokenType, msftTokenMint)

        redeemAndCheck(bob, msftTokenMint, msftTokenType, MOVE_QUANTITY_3, false)

        // Restart the validator on the default configuration simulating the connection restore
//        validator.stopIfRunning()
//        validator = SolanaTestValidator().also {
//            it.start(validator.ledgerDir, SolanaTestValidator.DEFAULT_RPC_PORT)
//        }
        val expectedBobNodeBalance = MOVE_TOTAL_QUANTITY - MOVE_QUANTITY_3
        ensureLockedAmount(msftTokenType, expectedBobNodeBalance)
    }
}
