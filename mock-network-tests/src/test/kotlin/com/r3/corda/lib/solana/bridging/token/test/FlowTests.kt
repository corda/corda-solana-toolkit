package com.r3.corda.lib.solana.bridging.token.test

import com.r3.corda.lib.solana.testing.SolanaTestValidator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import software.sava.rpc.json.http.client.SolanaRpcClient
import java.math.BigDecimal

abstract class FlowTests : ValidatorTests() {
    @Test
    fun `e2e bridge and redemption`(validator: SolanaTestValidator) {
        val msftTokenType = issuingBank.issue(msftDescriptor, ISSUING_QUANTITY * BigDecimal(2), generalNotaryName)
        val aaplTokenType = issuingBank.issue(aaplDescriptor, ISSUING_QUANTITY * BigDecimal(2), generalNotaryName)

        assertNull(validator.client().call(SolanaRpcClient::getAccountInfo, alice.mintToAta[msftTokenMint]!!))
        assertNull(validator.client().call(SolanaRpcClient::getAccountInfo, alice.mintToAta[aaplTokenMint]!!))
        assertNull(validator.client().call(SolanaRpcClient::getAccountInfo, bob.mintToAta[msftTokenMint]!!))
        assertNull(validator.client().call(SolanaRpcClient::getAccountInfo, bob.mintToAta[aaplTokenMint]!!))

        move(issuingBank, alice.party, ISSUING_QUANTITY, msftTokenType).get()
        move(issuingBank, alice.party, ISSUING_QUANTITY, aaplTokenType).get()
        move(issuingBank, bob.party, ISSUING_QUANTITY, msftTokenType).get()
        move(issuingBank, bob.party, ISSUING_QUANTITY, aaplTokenType).get()

        // Set expected balances
        alice.setExpectedCordaBalance(msftTokenMint, ISSUING_QUANTITY)
        alice.setExpectedCordaBalance(aaplTokenMint, ISSUING_QUANTITY)
        bob.setExpectedCordaBalance(msftTokenMint, ISSUING_QUANTITY)
        bob.setExpectedCordaBalance(aaplTokenMint, ISSUING_QUANTITY)

        // Bridge phase. We are moving different amounts to ensure multiple token states (including changes)
        // are created and handled correctly.
        bridgeAndCheck(alice, msftTokenType, msftTokenMint, MOVE_QUANTITY_1)
        bridgeAndCheck(alice, msftTokenType, msftTokenMint, MOVE_QUANTITY_2)
        bridgeAndCheck(alice, aaplTokenType, aaplTokenMint, MOVE_QUANTITY_1)
        bridgeAndCheck(alice, aaplTokenType, aaplTokenMint, MOVE_QUANTITY_2)
        bridgeAndCheck(bob, msftTokenType, msftTokenMint, MOVE_QUANTITY_1)
        bridgeAndCheck(bob, msftTokenType, msftTokenMint, MOVE_QUANTITY_2)
        bridgeAndCheck(bob, aaplTokenType, aaplTokenMint, MOVE_QUANTITY_1)
        bridgeAndCheck(bob, aaplTokenType, aaplTokenMint, MOVE_QUANTITY_2)

        ensureLockedAmount(msftTokenType, MOVE_TOTAL_QUANTITY * BigDecimal(2))
        ensureLockedAmount(aaplTokenType, MOVE_TOTAL_QUANTITY * BigDecimal(2))

        // Redemption phase
        redeemAndCheck(alice, msftTokenMint, msftTokenType, MOVE_TOTAL_QUANTITY)
        ensureLockedAmount(msftTokenType, MOVE_TOTAL_QUANTITY)
        redeemAndCheck(alice, aaplTokenMint, aaplTokenType, MOVE_TOTAL_QUANTITY)
        ensureLockedAmount(aaplTokenType, MOVE_TOTAL_QUANTITY)
        // Bob redeems MOVE_QUANTITY_3 to force change outputs to be created and handled correctly
        redeemAndCheck(bob, msftTokenMint, msftTokenType, MOVE_QUANTITY_3)
        ensureLockedAmount(msftTokenType, MOVE_TOTAL_QUANTITY - MOVE_QUANTITY_3)
        redeemAndCheck(bob, aaplTokenMint, aaplTokenType, MOVE_QUANTITY_3)
        ensureLockedAmount(aaplTokenType, MOVE_TOTAL_QUANTITY - MOVE_QUANTITY_3)
    }
}
